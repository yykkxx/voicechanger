package com.voicechanger.app.processing.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/**
 * 高质量重采样与音频净化工具（会话 #12）。
 *
 * 背景：早期版本 48k→16k 降采样使用「3 点平均」或「手写 23 阶 FIR」，
 * 其中 FIR 实现的延迟线索引有误（最老样本被当前样本重复计入），
 * 导致：
 *  1. 阻带衰减不足 → 8kHz 以上镜像频率折叠回带内 → **金属杂音**；
 *  2. 延迟线错位 → 每个样本的滤波结果都不正确 → **持续性沙沙噪声**。
 *
 * 本文件提供：
 *  - [Design.firLowpass] 窗函数法（Blackman-Harris）线性相位 FIR 设计；
 *  - [FirDecimator] 正确的整数比抽取器（延迟线语义严格正确）；
 *  - [DcBlocker] 一阶高通，消除直流漂移与极低频嗡嗡声；
 *  - [NoiseGate] 软噪声门，静音段不放大底噪（AI 模型输出常带底噪）。
 */
object Design {

    /**
     * 窗函数法线性相位低通 FIR（Blackman-Harris 窗，阻带衰减 > 90 dB）。
     *
     * @param taps   抽头数（奇数 → 整数群延迟，相位线性）
     * @param cutoff 截止频率（Hz）
     * @param fs     采样率（Hz）
     */
    fun firLowpass(taps: Int, cutoff: Double, fs: Double): FloatArray {
        require(taps % 2 == 1) { "taps must be odd for linear phase" }
        val fc = cutoff / fs
        val n = taps
        val a0 = 0.35875
        val a1 = 0.48829
        val a2 = 0.14128
        val a3 = 0.01168
        val h = DoubleArray(n)
        var sum = 0.0
        for (i in 0 until n) {
            val m = i - (n - 1) / 2.0
            val sinc = if (kotlin.math.abs(m) < 1e-12) 2.0 * fc
            else sin(2.0 * PI * fc * m) / (PI * m)
            val w = a0 - a1 * cos(2.0 * PI * i / (n - 1)) +
                a2 * cos(4.0 * PI * i / (n - 1)) - a3 * cos(6.0 * PI * i / (n - 1))
            h[i] = sinc * w
            sum += h[i]
        }
        // 归一化：直流增益 = 1，保证通带电平不变（否则整体音量会变小）
        return FloatArray(n) { (h[it] / sum).toFloat() }
    }
}

/**
 * 整数比 FIR 抽取器（48 kHz → 16 kHz，ratio = 3）。
 *
 * 延迟线语义（**关键修正**）：
 * - `delay` 保存最近 [taps] 个输入样本，`delay[0]` = 最新；
 * - 输出 y[n] = Σ h[k]·x[n-k]；
 * - 旧实现用 `if (j < dN) firDelay[j] else x` 把「最新样本」重复用两次，
 *   等于给滤波器叠加了一个错误的一阶项 → 通带起伏 + 阻带泄漏。
 */
class FirDecimator(
    private val ratio: Int,
    taps: Int,
    cutoffHz: Double,
    fs: Double,
) {
    private val h = Design.firLowpass(taps, cutoffHz, fs)
    private val n = h.size
    /** 延迟线：delay[0] 为最新样本。 */
    private val delay = FloatArray(n)
    private var phase = 0

    /** 处理一个输入样本；当到达抽取点时返回 true，并把结果写入 [out] 数组的第 [outIdx] 个位置。 */
    fun process(x: Float, out: FloatArray, outIdx: Int): Boolean {
        // 右移延迟线并写入最新样本
        System.arraycopy(delay, 0, delay, 1, n - 1)
        delay[0] = x
        phase++
        if (phase < ratio) return false
        phase = 0
        var acc = 0f
        for (k in 0 until n) acc += h[k] * delay[k]
        out[outIdx] = acc
        return true
    }

    fun reset() {
        java.util.Arrays.fill(delay, 0f)
        phase = 0
    }
}

/**
 * 一阶直流阻断高通（DC blocker）。
 * y[n] = x[n] - x[n-1] + R·y[n-1]，R≈0.995 → 截止约 38 Hz @ 48 kHz。
 *
 * 用途：AI 声码器输出常带直流偏置与极低频漂移，叠加到原始信号上会产生
 * 「嗡嗡」声与削波；此外 DC 偏置会让噪声门误判为有信号。
 */
class DcBlocker(private val r: Float = 0.995f) {
    private var x1 = 0f
    private var y1 = 0f

    fun process(buf: FloatArray, len: Int) {
        for (i in 0 until len) {
            val x = buf[i]
            val y = x - x1 + r * y1
            x1 = x
            y1 = y
            buf[i] = y
        }
    }

    fun reset() {
        x1 = 0f; y1 = 0f
    }
}

/**
 * 软噪声门（soft noise gate）：
 * - 低于 [thresholdDb] 的帧按 [floorGain] 衰减（默认 -18 dB），消除模型底噪；
 * - 门限附近用平滑曲线过渡，避免「开关门」的呼吸感；
 * - 带 attack/release 包络跟踪，避免瞬态被切掉。
 */
class NoiseGate(
    private val sampleRate: Int,
    thresholdDb: Float = -52f,
    private val floorGain: Float = 0.12f,
    private val attackMs: Float = 2f,
    private val releaseMs: Float = 120f,
) {
    private val threshold = dbToLin(thresholdDb)
    private val attack = expCoef(attackMs)
    private val release = expCoef(releaseMs)
    private var env = 0f
    private var gain = 1f

    private fun expCoef(ms: Float): Float {
        if (ms <= 0f) return 0f
        return exp(-1.0 / (ms * 0.001 * sampleRate)).toFloat()
    }

    private fun dbToLin(db: Float): Float = Math.pow(10.0, (db / 20f).toDouble()).toFloat()

    fun process(buf: FloatArray, len: Int) {
        for (i in 0 until len) {
            val a = kotlin.math.abs(buf[i])
            // 包络跟踪：上升快（attack）、下降慢（release）
            env = if (a > env) a + (env - a) * attack else a + (env - a) * release
            // 目标增益：低于阈值线性映射到 floorGain
            val target = if (env >= threshold) 1f
            else {
                val t = if (threshold > 0f) env / threshold else 1f
                floorGain + (1f - floorGain) * t
            }
            gain += (target - gain) * 0.25f
            buf[i] = buf[i] * gain
        }
    }

    fun reset() {
        env = 0f; gain = 1f
    }
}

/**
 * 轻量频谱去齿音/去金属声（spectral smoothing）：对高频段做时间轴平滑。
 *
 * RVC 等模型的 net_g 解码器在帧边界会引入高频「颗粒噪声」，
 * 单靠交叉淡入淡出无法完全消除。这里用一阶 IIR 在 6 kHz 以上做轻微平滑，
 * 保留语音清晰度的同时显著降低嘶嘶声。
 */
class HighBandSmoother(
    private val sampleRate: Int,
    /** 起始平滑频率（Hz）。 */
    startHz: Float = 6000f,
    /** 平滑强度 0..1（0 = 关闭）。 */
    private var amount: Float = 0.35f,
) {
    private var lp = 0f
    private var lp2 = 0f
    private val alpha: Float

    init {
        // 一阶低通系数：fc = startHz
        val rc = 1.0 / (2.0 * PI * startHz)
        val dt = 1.0 / sampleRate
        alpha = (dt / (rc + dt)).toFloat()
    }

    fun setAmount(a: Float) {
        amount = a.coerceIn(0f, 1f)
    }

    fun process(buf: FloatArray, len: Int) {
        if (amount <= 0f) return
        for (i in 0 until len) {
            val x = buf[i]
            lp += alpha * (x - lp)
            lp2 += alpha * (lp - lp2)
            // 高频分量 = 原信号 - 低通；对其做衰减后再叠加
            val high = x - lp2
            buf[i] = x - high * amount
        }
    }

    fun reset() {
        lp = 0f; lp2 = 0f
    }
}
