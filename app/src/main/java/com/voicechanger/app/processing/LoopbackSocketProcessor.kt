package com.voicechanger.app.processing

import com.voicechanger.app.domain.AudioStandard
import com.voicechanger.app.domain.EffectParams
import com.voicechanger.app.domain.StreamFormat
import java.util.concurrent.atomic.AtomicLong

/**
 * 本地端口处理器：VCP/1 客户端（Phase 4，plan/03 第 3–5 节）。
 *
 * 数据流：
 * ```
 * process(in)  → transport.txQueue.offer(in)      （TX 线程发给 server）
 * process(out) ← transport.rxQueue.poll(out)      （RX 线程收到的 PCM）
 * ```
 *
 * 故障策略（默认）：
 * - 未连接 / 断线 / 输出超时 → 持续输出静音（绝不放过未处理音频）；
 * - 输出在 [OUTPUT_STALE_US] 内无更新 → 视为过期，输出静音并计入 underrun；
 * - 连接状态经由 [connectionState] 供 UI 显示。
 *
 * 注意：本类 `process` 在实时音频线程运行，仅做队列读写与数组拷贝，无阻塞调用。
 */
class LoopbackSocketProcessor(
    host: String,
    port: Int,
    allowNonLoopback: Boolean = false,
) : ProcessorEngine {

    companion object {
        /** 输出超过该时长未更新即视为过期（微秒），约 3 帧。 */
        const val OUTPUT_STALE_US = 30_000L
    }

    data class ConnectionState(
        val transport: LoopbackSocketTransport.TransportState =
            LoopbackSocketTransport.TransportState.DISCONNECTED,
        val serverName: String = "",
        val algorithmicDelayFrames: Int = 0,
        val lastError: String? = null,
        val reconnectCount: Long = 0,
    )

    @Volatile
    var connectionState: ConnectionState = ConnectionState()
        private set

    val transport: LoopbackSocketTransport = LoopbackSocketTransport(
        host = host,
        port = port,
        allowNonLoopback = allowNonLoopback,
        callbacks = LoopbackSocketTransport.Callbacks(
            onStateChanged = { st ->
                connectionState = connectionState.copy(transport = st)
            },
            onServerInfo = { name, delay ->
                connectionState = connectionState.copy(serverName = name, algorithmicDelayFrames = delay)
            },
            onError = { err ->
                connectionState = connectionState.copy(lastError = err)
            },
        ),
    )

    // 实时线程侧无锁状态
    private val lastOutputUs = AtomicLong(0)
    private val underrunCount = AtomicLong(0)
    private val droppedInputCount = AtomicLong(0)

    /** 测试/诊断用：最近一次 process 的统计。 */
    fun underruns(): Long = underrunCount.get()
    fun droppedInputs(): Long = droppedInputCount.get()

    override fun configure(format: StreamFormat, params: EffectParams) {
        if (format.frameSamples != AudioStandard.SAMPLES_PER_FRAME) {
            throw IllegalArgumentException(
                "LoopbackSocketProcessor 仅支持标准帧 ${AudioStandard.SAMPLES_PER_FRAME}，收到 ${format.frameSamples}"
            )
        }
        transport.start()
    }

    override fun update(params: EffectParams) {
        // TODO(协议扩展): 通过 PARAMS 消息下发参数（v1 暂不消费参数）
        // 控制面实现后再启用；当前参数由 server 自行管理。
    }

    override fun process(input: ShortArray, length: Int, output: ShortArray): Int {
        // 1) 采集 → TX 队列（有界，满了丢新帧）
        val seq = txFrameCounter.incrementAndGet()
        if (!transport.txQueue.offer(input, length, seq, System.nanoTime() / 1000)) {
            droppedInputCount.incrementAndGet()
        }

        // 2) RX 队列 → 输出
        val polled = transport.rxQueue.poll(output)
        if (polled >= 0) {
            lastOutputUs.set(System.nanoTime() / 1000)
            return AudioStandard.SAMPLES_PER_FRAME
        }

        // 3) 无输出：过期判断 + 静音
        val nowUs = System.nanoTime() / 1000
        val stale = lastOutputUs.get() == 0L || (nowUs - lastOutputUs.get()) > OUTPUT_STALE_US
        java.util.Arrays.fill(output, 0, length, 0)
        if (stale) underrunCount.incrementAndGet()
        return length
    }

    private val txFrameCounter = AtomicLong(0)

    override fun reset(discontinuity: Boolean) {
        lastOutputUs.set(0)
    }

    override fun close() {
        transport.stop()
    }
}