package com.voicechanger.app.core.audio

import kotlin.math.exp
import kotlin.math.sqrt

/**
 * 轻量实时输入降噪（plan/09 第 7.8 节）：高通（去低频隆隆声）+ 自适应噪声门。
 *
 * - 高通：一阶 IIR，~120 Hz 以下滚降，去掉空调/风扇/风噪等低频底噪；
 * - 自适应噪声门：慢速估计背景噪声 RMS，信号弱于噪声阈值时平滑衰减增益
 *   （attack/release 平滑，避免门控咔哒声）；语音段快速恢复。
 *
 * 实时约束：process() 内零分配；所有状态为实例字段。
 */
class NoiseSuppressor(private val sampleRate: Int) {

    private val hpAlpha = exp(-2.0 * Math.PI * 120.0 / sampleRate).toFloat() // ~0.984 @48k
    private var hpPrevX = 0f
    private var hpPrevY = 0f

    /** 自适应噪声底噪（RMS 量级），慢速跟随。 */
    private var noiseFloor = 1e-4f

    /** 门控增益（平滑过渡，避免咔哒）。 */
    private var gateGain = 1f

    /** 噪声门开启状态（诊断用）。 */
    var isGating: Boolean = false
        private set

    fun process(buf: FloatArray, len: Int) {
        // 1) 高通滤波（去低频底噪）
        val a = hpAlpha
        var px = hpPrevX
        var py = hpPrevY
        for (i in 0 until len) {
            val x = buf[i]
            val y = x - px + a * py
            px = x
            py = y
            buf[i] = y
        }
        hpPrevX = px
        hpPrevY = py

        // 2) RMS 估计
        var sum = 0.0
        for (i in 0 until len) {
            val v = buf[i].toDouble()
            sum += v * v
        }
        val rms = (sqrt(sum / len)).toFloat()

        // 3) 自适应噪声门
        //    噪声底噪慢速跟随（仅当信号弱时更新，避免语音抬高底噪）
        if (rms < noiseFloor * 2.5f) {
            noiseFloor += (rms - noiseFloor) * 0.02f
            noiseFloor = noiseFloor.coerceAtLeast(1e-5f)
        }
        //    阈值 = 底噪 × 3（约 9.5 dB 余量）；低于阈值则压到 -40dB 以下
        val threshold = noiseFloor * 3f
        val target = if (rms < threshold) 0.01f else 1f
        //    attack 快（语音进入立即开），release 慢（尾音不咔哒）
        val coeff = if (target < gateGain) 0.9f else 0.05f
        gateGain += (target - gateGain) * coeff
        isGating = gateGain < 0.5f

        // 4) 应用增益
        val g = gateGain
        if (g != 1f) {
            for (i in 0 until len) buf[i] *= g
        }
    }

    fun reset() {
        hpPrevX = 0f
        hpPrevY = 0f
        noiseFloor = 1e-4f
        gateGain = 1f
        isGating = false
    }
}