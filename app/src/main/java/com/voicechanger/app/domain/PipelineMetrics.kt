package com.voicechanger.app.domain

/**
 * 实时流水线指标：由音频线程以原子计数写入，UI 侧低频读取。
 * 具体数值含义见 plan/05 第 9 节。
 */
data class PipelineMetrics(
    val captureFrames: Long = 0,
    val processedFrames: Long = 0,
    val inputDropped: Long = 0,
    val outputDropped: Long = 0,
    val underruns: Long = 0,
    val discontinuities: Long = 0,
    val avgProcessUs: Long = 0,
    /** 归一化 RMS（0..1），用于 UI 电平显示。 */
    val rms: Float = 0f,
) {
    val queueHealthText: String
        get() = buildString {
            append("drop(in/out)=").append(inputDropped).append('/').append(outputDropped)
            append(" underrun=").append(underruns)
        }
}