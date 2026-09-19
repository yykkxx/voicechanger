package com.voicechanger.app.domain

/**
 * 标准内部流格式：48 kHz、单声道、PCM 16-bit little-endian、10 ms 帧。
 *
 * 该文件刻意不依赖 android.* ，以便 JVM 单元测试直接覆盖。
 * [Pcm.ENCODING_PCM_16BIT] 与 android.media.AudioFormat.ENCODING_PCM_16BIT 数值一致（=2）。
 */
object Pcm {
    const val ENCODING_PCM_16BIT = 2
}

object AudioStandard {
    const val SAMPLE_RATE = 48_000
    const val CHANNELS = 1
    const val FRAME_MS = 10
    const val SAMPLES_PER_FRAME = SAMPLE_RATE / 1000 * FRAME_MS // 480
    const val BYTES_PER_SAMPLE = 2
    const val FRAME_BYTES = SAMPLES_PER_FRAME * BYTES_PER_SAMPLE // 960

    /** 有界实时队列的默认容量（帧数），约 60 ms。 */
    const val DEFAULT_QUEUE_FRAMES = 6
}

data class StreamFormat(
    val sampleRate: Int = AudioStandard.SAMPLE_RATE,
    val channels: Int = AudioStandard.CHANNELS,
    val encoding: Int = Pcm.ENCODING_PCM_16BIT,
    val frameSamples: Int = AudioStandard.SAMPLES_PER_FRAME,
) {
    val bytesPerFrame: Int
        get() = frameSamples * channels * AudioStandard.BYTES_PER_SAMPLE

    val frameDurationMs: Int
        get() = frameSamples * 1000 / sampleRate

    companion object {
        val STANDARD = StreamFormat()
    }
}