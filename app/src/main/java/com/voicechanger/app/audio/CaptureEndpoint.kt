package com.voicechanger.app.audio

import android.annotation.SuppressLint
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.voicechanger.app.domain.AudioStandard

/**
 * 采集端点：封装 AudioRecord 生命周期与读取循环（plan/02 第 5 节）。
 *
 * - 音频源优先级：UNPROCESSED → VOICE_RECOGNITION → MIC（尽量少系统处理）；
 * - 输出固定为 48 kHz / mono / S16LE，帧边界对齐 [AudioStandard.SAMPLES_PER_FRAME]；
 * - 读取线程只做读+回调写入，无分配。
 */
class CaptureEndpoint(
    private val preferredDevice: AudioDeviceInfo? = null,
) {
    companion object {
        private const val TAG = "VC/Capture"
        private const val NATIVE_SAMPLE_RATE = AudioStandard.SAMPLE_RATE
        private const val NATIVE_CHANNELS = AudioFormat.CHANNEL_IN_MONO
        private const val NATIVE_ENCODING = AudioFormat.ENCODING_PCM_16BIT
    }

    interface Listener {
        /** 在采集线程调用。frame.length == AudioStandard.SAMPLES_PER_FRAME。 */
        fun onFrame(frame: ShortArray, timestampUs: Long)

        /** 采集停止或出错（reason 为稳定字符串）。 */
        fun onStopped(reason: String)
    }

    private var record: AudioRecord? = null
    private var thread: Thread? = null
    @Volatile
    private var running = false

    /** 实际打开的音频源（诊断用）。 */
    var actualSource: Int = MediaRecorder.AudioSource.MIC
        private set

    /**
     * 初始化 AudioRecord。
     * @throws IllegalStateException 初始化失败
     */
    @SuppressLint("MissingPermission")
    fun open(): AudioRecord {
        close()
        val minBuf = AudioRecord.getMinBufferSize(NATIVE_SAMPLE_RATE, NATIVE_CHANNELS, NATIVE_ENCODING)
        if (minBuf <= 0) {
            throw IllegalStateException("AudioRecord.getMinBufferSize returned $minBuf")
        }
        val targetBuf = maxOf(minBuf, AudioStandard.FRAME_BYTES * 4)

        // 采集源优先级：MIC 优先（由平台 AGC/麦克风增益处理，音量正常）；
        // VOICE_RECOGNITION 次之（同样有平台增益）；UNPROCESSED 仅兜底
        // （plan/09 第 7.5 节：UNPROCESSED 绕过平台 AGC 导致 rms≈0.002 音量过小）。
        val candidates = listOf(
            MediaRecorder.AudioSource.MIC,
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.UNPROCESSED,
        )

        var lastError: String? = null
        for (source in candidates) {
            try {
                val r = AudioRecord.Builder()
                    .setAudioSource(source)
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(NATIVE_ENCODING)
                            .setSampleRate(NATIVE_SAMPLE_RATE)
                            .setChannelMask(NATIVE_CHANNELS)
                            .build()
                    )
                    .setBufferSizeInBytes(targetBuf)
                    .build()
                if (r.state == AudioRecord.STATE_INITIALIZED) {
                    if (preferredDevice != null) {
                        r.preferredDevice = preferredDevice
                    }
                    record = r
                    actualSource = source
                    Log.i(TAG, "AudioRecord opened: source=$source buf=$targetBuf")
                    return r
                }
                r.release()
                lastError = "state != INITIALIZED for source=$source"
            } catch (t: Throwable) {
                lastError = "source=$source: ${t.message}"
                Log.w(TAG, "AudioRecord open failed", t)
            }
        }
        throw IllegalStateException("cannot open AudioRecord: $lastError")
    }

    /**
     * 启动采集线程。
     * @param frameConsumer 由采集线程调用：写入 input ring，返回 false 表示丢弃。
     */
    fun start(listener: Listener) {
        val r = record ?: throw IllegalStateException("not opened")
        if (running) return
        running = true
        r.startRecording()
        if (r.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            running = false
            throw IllegalStateException("AudioRecord failed to start (recordingState=${r.recordingState})")
        }
        thread = Thread({
            val frame = ShortArray(AudioStandard.SAMPLES_PER_FRAME)
            val accumulator = ShortArray(AudioStandard.SAMPLES_PER_FRAME * 16)
            var accLen = 0
            val chunk = ShortArray(AudioStandard.SAMPLES_PER_FRAME * 4)
            try {
                while (running) {
                    val n = r.read(chunk, 0, chunk.size)
                    if (n <= 0) {
                        if (n < 0) {
                            listener.onStopped("read error $n")
                            return@Thread
                        }
                        continue
                    }
                    var off = 0
                    while (off < n && running) {
                        val take = minOf(AudioStandard.SAMPLES_PER_FRAME - accLen, n - off)
                        System.arraycopy(chunk, off, accumulator, accLen, take)
                        accLen += take
                        off += take
                        if (accLen == AudioStandard.SAMPLES_PER_FRAME) {
                            System.arraycopy(accumulator, 0, frame, 0, AudioStandard.SAMPLES_PER_FRAME)
                            listener.onFrame(frame, System.nanoTime() / 1000)
                            accLen = 0
                        }
                    }
                }
            } catch (t: Throwable) {
                if (running) {
                    Log.e(TAG, "capture loop died", t)
                    listener.onStopped("capture loop: ${t.message}")
                }
            }
        }, "vc-capture").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    fun stop() {
        running = false
        try {
            record?.stop()
        } catch (_: Throwable) {
        }
        thread?.join(500)
        thread = null
    }

    fun close() {
        stop()
        record?.release()
        record = null
    }
}