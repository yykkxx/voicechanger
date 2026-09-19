package com.voicechanger.app.audio

import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import com.voicechanger.app.domain.AudioStandard

/**
 * 注入端点：把处理后的 PCM 写入目标路由（plan/02 第 3 节）。
 *
 * 当前阶段职责：
 * - 支持绑定到指定输出设备（例如厂商虚拟设备 `127.0.0.x`）；
 * - 支持 48 kHz / mono 或 stereo（由 [stereoExpansion] 控制）；
 * - 写入零帧预热与延迟注入均由上层流水线控制。
 *
 * 说明：该端点本身只负责“向所选设备播放”。
 * 是否真正成为目标应用麦克风输入，由 RouteBackend 的方案决定，
 * 必须通过 Phase 1 标记音验证后才可认定（见 plan/02 第 10 节）。
 */
class InjectionEndpoint(
    private val preferredDevice: AudioDeviceInfo? = null,
    private val stereoExpansion: Boolean = false,
    /** 输出流 usage；默认媒体语音，避免抢占通话/媒体主输出（plan/09 第 4.2 节）。 */
    val usage: Int = AudioAttributes.USAGE_MEDIA,
    /** 输出流 content type；默认语音。 */
    val contentType: Int = AudioAttributes.CONTENT_TYPE_SPEECH,
) : Injector {
    companion object {
        private const val TAG = "VC/Inject"
    }

    private var track: AudioTrack? = null

    // ---- 写入统计（诊断，plan/09 第 7.4 节）----
    private val writtenSamplesLock = java.util.concurrent.atomic.AtomicLong(0)
    private val writeErrorLock = java.util.concurrent.atomic.AtomicLong(0)
    private val totalWritesLock = java.util.concurrent.atomic.AtomicLong(0)

    /** 累计写入样本数（mono 计）。 */
    val writtenSamples: Long get() = writtenSamplesLock.get()

    /** 累计写入错误次数（write 负返回）。 */
    val writeErrors: Long get() = writeErrorLock.get()

    /** 累计写入调用次数。 */
    val totalWrites: Long get() = totalWritesLock.get()

    /** 会话期实际使用的通道 mask。 */
    val channelMask: Int =
        if (stereoExpansion) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO

    override fun open() {
        close()
        val minBuf = AudioTrack.getMinBufferSize(
            AudioStandard.SAMPLE_RATE,
            channelMask,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuf <= 0) {
            throw IllegalStateException("AudioTrack.getMinBufferSize returned $minBuf")
        }
        // 目标：12 帧（120ms）缓冲，兼顾延迟与写阻塞（plan/09 第 7.8 节：延迟优先）
        val targetBuf = maxOf(minBuf, AudioStandard.FRAME_BYTES * 12)

        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(usage)
                    .setContentType(contentType)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(AudioStandard.SAMPLE_RATE)
                    .setChannelMask(channelMask)
                    .build()
            )
            .setBufferSizeInBytes(targetBuf)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        if (t.state != AudioTrack.STATE_INITIALIZED) {
            t.release()
            throw IllegalStateException("AudioTrack state=${t.state} (buffer=$targetBuf)")
        }
        if (preferredDevice != null) {
            val ok = t.setPreferredDevice(preferredDevice)
            Log.i(TAG, "setPreferredDevice ${preferredDevice.address} -> $ok")
        }
        track = t
        // 预热：open 后先写静音填满缓冲（plan/09 第 7.4/7.5 节），
        // 避免 play 后首帧数据尚未到达时 underrun（缓冲 24 帧，循环写 12 帧预热）。
        // 注意：writeSilence 内部读 track（须先赋值），且按帧写避免 mono 缓冲越界。
        repeat(12) { writeSilence(AudioStandard.SAMPLES_PER_FRAME) }
    }

    override fun start() {
        val t = track ?: throw IllegalStateException("not opened")
        if (t.playState != AudioTrack.PLAYSTATE_PLAYING) {
            // 预热：play 前先写静音填缓冲，避免首帧数据未到达时 underrun
            // （plan/09 第 7.4 节；缓冲过小会由 AudioTrack 内部 padding）
            writeSilence(AudioStandard.SAMPLES_PER_FRAME)
            t.play()
        }
    }

    /** 注入端批量消费的最大帧数（80ms；plan/09 第 7.5 节）。 */
    private val maxBatchFrames = 8

    /** mono→stereo 展开缓冲（翻倍容量；write 由注入线程单线程调用，容量容纳整批）。 */
    private val stereoBuf = ShortArray(AudioStandard.SAMPLES_PER_FRAME * 2 * maxBatchFrames)

    /** mono 写入缓冲（writeSilence 用；零分配复用，容量容纳整批）。 */
    private val frameBuf = ShortArray(AudioStandard.SAMPLES_PER_FRAME * maxBatchFrames)

    /** 写入一帧（mono 输入）。返回写入样本数；负值为错误码。 */
    override fun write(frame: ShortArray, length: Int): Int {
        val t = track ?: return -1
        totalWritesLock.incrementAndGet()
        val res = if (stereoExpansion) {
            // mono → stereo：展开到翻倍缓冲（不能原地写，帧只有 mono 容量）
            val buf = stereoBuf
            for (i in 0 until length) {
                buf[i * 2] = frame[i]
                buf[i * 2 + 1] = frame[i]
            }
            t.write(buf, 0, length * 2)
        } else {
            t.write(frame, 0, length)
        }
        if (res < 0) {
            writeErrorLock.incrementAndGet()
        } else {
            writtenSamplesLock.addAndGet(length.toLong())
        }
        return if (res >= 0) length else res
    }

    /** 写入静音帧（用于预热与安全静音）。 */
    override fun writeSilence(length: Int): Int {
        val t = track ?: return -1
        val n = if (stereoExpansion) length * 2 else length
        val buf = if (stereoExpansion) stereoBuf else frameBuf
        for (i in 0 until n) buf[i] = 0
        val res = t.write(buf, 0, n)
        if (res >= 0) writtenSamplesLock.addAndGet(length.toLong())
        return res
    }

    /**
     * 尚未被硬件播放的帧数（写入总帧 - 播放头已消费帧）。
     * 注：writtenSamples 按 mono 样本计，AudioTrack 写入 mono 样本数与
     * audio frame 数数值一致（stereo 展开时 mono 样本数 = stereo frame 数），
     * playbackHeadPosition 单位也是 audio frame，直接相减即可。
     * 用于注入端“水位节拍”：缓冲低于目标水位才写入，避免盲轮询/写阻塞
     * （plan/09 第 7.7 节：此前 injectLoop 无节拍，数据间隙 AudioTrack 补零 → 断续）。
     * 返回 -1 表示 track 不可用（调用方按“缓冲充足”处理，不触发补写）。
     */
    fun bufferedFrames(): Int {
        val t = track ?: return -1
        val writtenFrames = writtenSamplesLock.get()
        val headFrames = t.playbackHeadPosition
        return (writtenFrames - headFrames).toInt().coerceAtLeast(0)
    }

    override val isPlaying: Boolean
        get() = track?.playState == AudioTrack.PLAYSTATE_PLAYING

    override fun stop() {
        try {
            track?.stop()
        } catch (_: Throwable) {
        }
    }

    override fun close() {
        stop()
        track?.release()
        track = null
    }
}

/** AudioManager 的小工具：按地址查找设备（用于厂商虚拟设备后端，plan/02 第 3.2 节）。 */
object AudioDeviceLookup {

    fun findByAddress(manager: AudioManager, address: String, isInput: Boolean): AudioDeviceInfo? {
        val devices = manager.getDevices(
            if (isInput) AudioManager.GET_DEVICES_INPUTS else AudioManager.GET_DEVICES_OUTPUTS
        )
        return devices.firstOrNull { it.address == address }
    }

    /**
     * 厂商虚拟输出（ip_out）。plan/09 第 7 节：ColorOS 16 上 address 为空（`@:`），
     * 必须用 [AudioDeviceInfo.TYPE_IP] 识别；address 仅作辅助日志。
     */
    fun findVirtualOut(manager: AudioManager): AudioDeviceInfo? =
        manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .firstOrNull {
                it.type == AudioDeviceInfo.TYPE_IP ||
                    (it.address?.startsWith("127.0.0") == true)
            }

    /** 厂商虚拟输入（ip_input_device）。同样以 TYPE_IP 为准。 */
    fun findVirtualIn(manager: AudioManager): AudioDeviceInfo? =
        manager.getDevices(AudioManager.GET_DEVICES_INPUTS)
            .firstOrNull {
                it.type == AudioDeviceInfo.TYPE_IP ||
                    (it.address?.startsWith("127.0.1") == true)
            }

    /**
     * 选择安全的监听/返听输出设备（plan/09 第 7.3 节）：
     * - **排除 TYPE_BLUETOOTH_SCO**：ColorOS 未通话时也枚举 SCO，误绑会导致
     *   监听无声 + 抢占媒体路由（先开变声→后开网易云无声）；
     * - 优先级：有线耳机/头戴 > USB > 蓝牙 A2DP（媒体通道，可与其他媒体混音）；
     * - 找不到返回 null：调用方不要 setPreferredDevice，跟随系统默认媒体路由。
     */
    fun findSafeMonitorDevice(manager: AudioManager): AudioDeviceInfo? {
        val devs = manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val priority = listOf(
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        )
        for (type in priority) {
            devs.firstOrNull { it.type == type }?.let { return it }
        }
        return null
    }
}