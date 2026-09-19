package com.voicechanger.app.processing

import com.voicechanger.app.core.protocol.Vcp
import com.voicechanger.app.core.protocol.VcpMessages
import com.voicechanger.app.core.protocol.VcpParser
import com.voicechanger.app.domain.AudioStandard
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * 本地端口传输层：VCP/1 over TCP 全双工（plan/03 第 3–5 节）。
 *
 * 设计要点：
 * - 仅允许 loopback 地址（构造时校验，见 plan/04 第 8 节）；
 * - TX 线程：process 线程 → [txQueue] → socket；RX 线程：socket → [rxQueue] → process 线程；
 * - 严格有界队列：写满丢新帧（drop-newest），绝不无限积压；
 * - 全双工，**不**逐帧同步等待（避免抖动直接叠加到采集）；
 * - HELLO/HELLO_ACK 握手（1s 连接 / 2s 握手超时）、START、PING/PONG 保活；
 * - 断线后按 250ms/500ms/1s/2s/5s 退避重连；重连成功清空旧帧，不重放历史语音；
 * - 音频超时保护：连续 [AUDIO_TIMEOUT_MS] 无有效输出时报告 disconnected 回调。
 *
 * 本类不依赖 android.*，可直接在 JVM 单测中使用（配合假 server）。
 */
class LoopbackSocketTransport(
    private val host: String,
    private val port: Int,
    /** 允许非 loopback（仅测试/高级用途；默认 false）。 */
    private val allowNonLoopback: Boolean = false,
    private val callbacks: Callbacks = Callbacks(),
) {
    /** 回调均在传输内部线程调用；实现方不得阻塞。 */
    class Callbacks(
        val onStateChanged: (TransportState) -> Unit = {},
        val onServerInfo: (serverName: String, algorithmicDelayFrames: Int) -> Unit = { _, _ -> },
        val onError: (String) -> Unit = {},
    )

    enum class TransportState { DISCONNECTED, CONNECTING, HANDSHAKING, READY, STREAMING, CLOSING }

    companion object {
        const val CONNECT_TIMEOUT_MS = 1_000
        const val HANDSHAKE_TIMEOUT_MS = 2_000
        const val PING_INTERVAL_MS = 2_000L
        const val AUDIO_TIMEOUT_MS = 500L
        const val QUEUE_CAPACITY_FRAMES = 6
        /** 待发送的 PCM 帧上限（in-flight 控制）。 */
        const val MAX_IN_FLIGHT = 6
        const val MAX_PAYLOAD = Vcp.MAX_PAYLOAD

        private val BACKOFF_MS = longArrayOf(250, 500, 1_000, 2_000, 5_000)
    }

    // ---- 有界队列 ----
    @Volatile
    var txQueue = PcmFrameQueue(QUEUE_CAPACITY_FRAMES, AudioStandard.SAMPLES_PER_FRAME)
        private set

    @Volatile
    var rxQueue = PcmFrameQueue(QUEUE_CAPACITY_FRAMES, AudioStandard.SAMPLES_PER_FRAME)
        private set

    // ---- 状态 ----
    @Volatile
    var state: TransportState = TransportState.DISCONNECTED
        private set

    /** 最近一次收到的 server 名称（HELLO_ACK）。 */
    @Volatile
    var serverName: String = ""
        private set

    /** server 声明的算法延迟（帧数）。 */
    @Volatile
    var algorithmicDelayFrames: Int = 0
        private set

    /** 最近一次错误（稳定描述）。 */
    @Volatile
    var lastError: String? = null
        private set

    val reconnectCount = AtomicLong(0)
    val txFrames = AtomicLong(0)
    val rxFrames = AtomicLong(0)
    val rxDropped = AtomicLong(0)
    val protoErrors = AtomicLong(0)

    private val running = AtomicBoolean(false)
    private var txThread: Thread? = null
    private var rxThread: Thread? = null
    private var controlThread: Thread? = null

    @Volatile
    private var lastRxUs: Long = 0

    private fun isLoopback(host: String): Boolean =
        host == "127.0.0.1" || host == "localhost" || host == "::1" ||
            host.startsWith("127.")

    /** 启动传输（异步）；重复调用无副作用。连接建立前 rxQueue 为空。 */
    fun start() {
        if (!running.compareAndSet(false, true)) return
        if (!allowNonLoopback && !isLoopback(host)) {
            lastError = "非 loopback 地址被拒绝: $host"
            callbacks.onError(lastError!!)
            running.set(false)
            return
        }
        txQueue = PcmFrameQueue(QUEUE_CAPACITY_FRAMES, AudioStandard.SAMPLES_PER_FRAME)
        rxQueue = PcmFrameQueue(QUEUE_CAPACITY_FRAMES, AudioStandard.SAMPLES_PER_FRAME)

        controlThread = Thread({ controlLoop() }, "vc-vcp-control").apply { start() }
    }

    /** 停止传输；不抛异常。 */
    fun stop() {
        running.set(false)
        try { controlThread?.join(1_000) } catch (_: InterruptedException) {}
        controlThread = null
        txThread = null
        rxThread = null
        setState(TransportState.CLOSING)
        setState(TransportState.DISCONNECTED)
    }

    /** 发送 STOP（尽力而为，异步）。 */
    fun sendStop() {
        pendingStop.set(true)
    }

    private val pendingStop = AtomicBoolean(false)

    // ---------------- 内部：控制循环（连接 + 重连 + 握手） ----------------

    private fun controlLoop() {
        var backoffIndex = 0
        while (running.get()) {
            setState(TransportState.CONNECTING)
            val socket = try {
                Socket().apply {
                    tcpNoDelay = true
                    soTimeout = 0
                    connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
                }
            } catch (t: Throwable) {
                lastError = "connect failed: ${t.message}"
                callbacks.onError(lastError!!)
                setState(TransportState.DISCONNECTED)
                if (!sleepBackoff(backoffIndex)) break
                backoffIndex = (backoffIndex + 1).coerceAtMost(BACKOFF_MS.size - 1)
                continue
            }

            try {
                if (session(socket)) {
                    // 正常结束会话（stop 被调用或远端关闭）
                    backoffIndex = 0
                }
            } catch (t: Throwable) {
                lastError = "session error: ${t.message}"
            } finally {
                try { socket.close() } catch (_: Throwable) {}
                setState(TransportState.DISCONNECTED)
            }

            if (running.get()) {
                reconnectCount.incrementAndGet()
                // 重连期间清空旧帧：绝不重放历史语音（plan/03 第 5 节）
                txQueue = PcmFrameQueue(QUEUE_CAPACITY_FRAMES, AudioStandard.SAMPLES_PER_FRAME)
                rxQueue = PcmFrameQueue(QUEUE_CAPACITY_FRAMES, AudioStandard.SAMPLES_PER_FRAME)
                if (!sleepBackoff(backoffIndex)) break
                backoffIndex = (backoffIndex + 1).coerceAtMost(BACKOFF_MS.size - 1)
            }
        }
    }

    /** @return true 表示本轮会话正常结束（非异常）。 */
    private fun session(socket: Socket): Boolean {
        val input = socket.getInputStream()
        val output = socket.getOutputStream()

        // ---- HELLO 握手 ----
        setState(TransportState.HANDSHAKING)
        val hello = buildHelloJson()
        writeFully(output, VcpMessages.jsonPayload(Vcp.TYPE_HELLO, hello))

        val parser = VcpParser { reason ->
            protoErrors.incrementAndGet()
            lastError = "protocol error: $reason"
        }
        val deadline = System.currentTimeMillis() + HANDSHAKE_TIMEOUT_MS
        var ackReceived = false
        val readBuf = ByteArray(8 * 1024)

        while (System.currentTimeMillis() < deadline && running.get()) {
            val readTimeout = (deadline - System.currentTimeMillis()).coerceAtLeast(1).toInt()
            socket.soTimeout = readTimeout
            val n = try {
                input.read(readBuf)
            } catch (t: java.net.SocketTimeoutException) {
                -2
            }
            if (n == -2) break // 超时
            if (n < 0) return true // 远端关闭
            parser.feed(readBuf, 0, n)
            var msg = parser.next()
            while (msg != null) {
                if (msg.type == Vcp.TYPE_HELLO_ACK) {
                    ackReceived = parseHelloAck(String(msg.payload, Charsets.UTF_8))
                } else if (msg.type == Vcp.TYPE_ERROR) {
                    lastError = "server error: ${String(msg.payload, Charsets.UTF_8)}"
                    callbacks.onError(lastError!!)
                    return true
                }
                msg = parser.next()
            }
            if (ackReceived) break
        }
        if (!ackReceived) {
            lastError = "handshake timeout / rejected"
            callbacks.onError(lastError!!)
            return false
        }

        // ---- START ----
        writeFully(output, VcpMessages.jsonPayload(Vcp.TYPE_START, "{}"))
        setState(TransportState.READY)

        // ---- 启动收发线程 ----
        socket.soTimeout = 250
        val tx = Thread({ txLoop(output) }, "vc-vcp-tx")
        val rx = Thread({ rxLoop(input, parser) }, "vc-vcp-rx")
        txThread = tx
        rxThread = rx
        lastRxUs = System.nanoTime() / 1000
        tx.start()
        rx.start()
        setState(TransportState.STREAMING)

        // ---- 控制循环：等待 stop / 线程退出 ----
        while (running.get() && (tx.isAlive || rx.isAlive)) {
            if (pendingStop.compareAndSet(true, false)) {
                try { writeFully(output, VcpMessages.control(Vcp.TYPE_STOP)) } catch (_: Throwable) {}
            }
            try { Thread.sleep(50) } catch (_: InterruptedException) { break }
        }
        tx.join(500)
        rx.join(500)
        return true
    }

    private fun txLoop(output: OutputStream) {
        val frame = ShortArray(AudioStandard.SAMPLES_PER_FRAME)
        val meta = PcmFrameQueue.Meta()
        var lastPingUs = 0L
        while (running.get()) {
            val seq = txQueue.poll(frame, meta)
            val nowUs = System.nanoTime() / 1000
            if (seq >= 0) {
                val bytes = VcpMessages.pcm(
                    type = Vcp.TYPE_PCM_INPUT,
                    sequence = meta.sequence,
                    timestampUs = meta.timestampUs,
                    flags = 0,
                    pcm = frame,
                )
                try {
                    writeFully(output, bytes)
                    txFrames.incrementAndGet()
                } catch (t: Throwable) {
                    lastError = "tx failed: ${t.message}"
                    return
                }
            } else {
                // 保活：静默超过 2s 时发送 PING
                if (nowUs - lastPingUs > PING_INTERVAL_MS * 1000) {
                    try {
                        writeFully(output, VcpMessages.control(Vcp.TYPE_PING))
                        lastPingUs = nowUs
                    } catch (t: Throwable) {
                        lastError = "ping failed: ${t.message}"
                        return
                    }
                }
                try { Thread.sleep(2) } catch (_: InterruptedException) { return }
            }
        }
    }

    private fun rxLoop(input: InputStream, parser: VcpParser) {
        val readBuf = ByteArray(16 * 1024)
        val frame = ShortArray(AudioStandard.SAMPLES_PER_FRAME)
        while (running.get()) {
            val n = try {
                input.read(readBuf)
            } catch (t: java.net.SocketTimeoutException) {
                -2
            } catch (t: Throwable) {
                lastError = "rx failed: ${t.message}"
                return
            }
            if (n == -2) {
                checkAudioTimeout()
                continue
            }
            if (n < 0) return

            val nowUs = System.nanoTime() / 1000
            lastRxUs = nowUs
            parser.feed(readBuf, 0, n)
            var msg = parser.next()
            while (msg != null && running.get()) {
                when (msg.type) {
                    Vcp.TYPE_PCM_OUTPUT -> handlePcmOutput(msg, frame)
                    Vcp.TYPE_PONG -> Unit
                    Vcp.TYPE_PING -> Unit // 对方保活；TCP 即可，无需回
                    Vcp.TYPE_ERROR -> {
                        lastError = "server error: ${String(msg.payload, Charsets.UTF_8)}"
                        callbacks.onError(lastError!!)
                    }
                    Vcp.TYPE_STATS -> Unit // 低频统计，暂未消费
                }
                msg = parser.next()
            }
        }
    }

    private fun handlePcmOutput(msg: com.voicechanger.app.core.protocol.VcpMessage, frame: ShortArray) {
        val sampleCount = msg.payload.size / 2
        if (sampleCount <= 0 || sampleCount > frame.size * 2) {
            protoErrors.incrementAndGet()
            return
        }
        com.voicechanger.app.core.audio.PcmUtils.bytesToShortsLE(
            msg.payload, 0, frame, 0, sampleCount.coerceAtMost(frame.size),
        )
        val ok = rxQueue.offer(frame, sampleCount.coerceAtMost(frame.size), msg.sequence, msg.timestampUs)
        if (ok) {
            rxFrames.incrementAndGet()
        } else {
            rxDropped.incrementAndGet()
        }
    }

    private fun checkAudioTimeout() {
        if (state != TransportState.STREAMING) return
        val nowUs = System.nanoTime() / 1000
        if (lastRxUs > 0 && nowUs - lastRxUs > AUDIO_TIMEOUT_MS * 1000) {
            lastError = "audio timeout (>${AUDIO_TIMEOUT_MS}ms)"
            callbacks.onError(lastError!!)
        }
    }

    // ---------------- 辅助 ----------------

    private fun setState(newState: TransportState) {
        if (state == newState) return
        state = newState
        callbacks.onStateChanged(newState)
    }

    private fun sleepBackoff(index: Int): Boolean {
        val ms = BACKOFF_MS[index.coerceIn(0, BACKOFF_MS.size - 1)]
        var remaining = ms
        while (remaining > 0 && running.get()) {
            val step = remaining.coerceAtMost(50)
            try { Thread.sleep(step) } catch (_: InterruptedException) { return false }
            remaining -= step
        }
        return running.get()
    }

    private fun buildHelloJson(): String {
        return buildString {
            append("{")
            append("\"client\":\"android-voice-changer\",")
            append("\"protocol\":1,")
            append("\"formats\":[{")
            append("\"sampleRate\":").append(AudioStandard.SAMPLE_RATE).append(',')
            append("\"channels\":1,")
            append("\"encoding\":\"pcm_s16le\",")
            append("\"frameSamples\":").append(AudioStandard.SAMPLES_PER_FRAME)
            append("}],")
            append("\"maxInFlightFrames\":").append(MAX_IN_FLIGHT)
            append("}")
        }
    }

    /** 解析 HELLO_ACK（简单字段提取；协议面 JSON 约束极窄）。 */
    private fun parseHelloAck(json: String): Boolean {
        val accepted = json.contains("\"accepted\":true") || json.contains("\"accepted\": true")
        if (!accepted) {
            lastError = "server rejected handshake: $json"
            callbacks.onError(lastError!!)
            return false
        }
        serverName = extractJsonString(json, "server") ?: ""
        algorithmicDelayFrames = extractJsonInt(json, "algorithmicDelayFrames") ?: 0
        callbacks.onServerInfo(serverName, algorithmicDelayFrames)
        return true
    }

    private fun extractJsonString(json: String, key: String): String? {
        val idx = json.indexOf("\"$key\"")
        if (idx < 0) return null
        val colon = json.indexOf(':', idx)
        if (colon < 0) return null
        var start = colon + 1
        while (start < json.length && json[start].isWhitespace()) start++
        if (start >= json.length || json[start] != '"') return null
        start++
        val end = json.indexOf('"', start)
        if (end < 0) return null
        return json.substring(start, end)
    }

    private fun extractJsonInt(json: String, key: String): Int? {
        val idx = json.indexOf("\"$key\"")
        if (idx < 0) return null
        val colon = json.indexOf(':', idx)
        if (colon < 0) return null
        var start = colon + 1
        while (start < json.length && json[start].isWhitespace()) start++
        val end = json.indexOfFirst(start) { !it.isDigit() && it != '-' }
        val numStr = if (end < 0) json.substring(start) else json.substring(start, end)
        return numStr.toIntOrNull()
    }

    private inline fun CharSequence.indexOfFirst(start: Int, predicate: (Char) -> Boolean): Int {
        for (i in start until length) {
            if (predicate(this[i])) return i
        }
        return -1
    }

    private fun writeFully(output: OutputStream, bytes: ByteArray) {
        output.write(bytes)
        output.flush()
    }
}