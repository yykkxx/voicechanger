package com.voicechanger.app.processing.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/**
 * 第二套轻量效果器（专业人声调制），全部实时线程零分配：
 *
 * - [Chorus]：3 条延迟线 + 慢速 LFO，人声加厚（天使/群感）；
 * - [Flanger]：短延迟 + 扫频梳状滤波，太空/电子感；
 * - [Tremolo]：低频幅度调制，唱歌/机械感；
 * - [Telephone]：300Hz–3.4kHz 带通 + 轻微饱和，电话/对讲机感；
 * - [BassBoost]：一阶低通分频 + 低频段增益，浑厚低沉；
 * - [Presence]：2–4kHz 峰式提升，人声靠前清晰（临场感）。
 */
class Chorus(
    private val sampleRate: Int,
    maxDelayMs: Int = 40,
) {
    private val buffer = FloatArray(sampleRate * maxDelayMs / 1000)
    private var writePos = 0

    @Volatile
    var amount: Float = 0f

    private val dPhase1 = 2.0 * PI * 0.18 / sampleRate
    private val dPhase2 = 2.0 * PI * 0.31 / sampleRate
    private val dPhase3 = 2.0 * PI * 0.47 / sampleRate
    private var phase1 = 0.0
    private var phase2 = 0.0
    private var phase3 = 0.0

    fun process(buf: FloatArray, len: Int) {
        val a = amount
        if (a <= 0f) return
        val size = buffer.size
        // 延迟线深度：干音 + 3 路湿声，LFO 慢扫 ±
        val base = (10L * sampleRate / 1000).toInt().coerceIn(1, size / 4)
        val depth = (6L * sampleRate / 1000).toInt().coerceAtLeast(1)
        var wp = writePos
        var p1 = phase1; var p2 = phase2; var p3 = phase3
        val wet = a * 0.22f
        for (i in 0 until len) {
            buffer[wp] = buf[i]
            val d1 = base + (depth * (0.5 + 0.5 * cos(p1))).toInt()
            val d2 = base + (depth * (0.5 + 0.5 * cos(p2))).toInt()
            val d3 = base + (depth * (0.5 + 0.5 * cos(p3))).toInt()
            val r1 = readDelayed(buffer, size, wp, d1)
            val r2 = readDelayed(buffer, size, wp, d2)
            val r3 = readDelayed(buffer, size, wp, d3)
            buf[i] = buf[i] + (r1 + r2 + r3) * wet
            wp++
            if (wp >= size) wp = 0
            p1 += dPhase1; if (p1 >= 2.0 * PI) p1 -= 2.0 * PI
            p2 += dPhase2; if (p2 >= 2.0 * PI) p2 -= 2.0 * PI
            p3 += dPhase3; if (p3 >= 2.0 * PI) p3 -= 2.0 * PI
        }
        writePos = wp
        phase1 = p1; phase2 = p2; phase3 = p3
    }

    fun reset() {
        buffer.fill(0f)
        writePos = 0
        phase1 = 0.0; phase2 = 0.0; phase3 = 0.0
    }
}

/** 镶边：短延迟（1–12ms）+ 正弦扫频，产生移动梳状滤波。 */
class Flanger(
    private val sampleRate: Int,
    maxDelayMs: Int = 15,
) {
    private val buffer = FloatArray(sampleRate * maxDelayMs / 1000)
    private var writePos = 0

    @Volatile
    var amount: Float = 0f

    private val dPhase = 2.0 * PI * 0.4 / sampleRate
    private var phase = 0.0

    fun process(buf: FloatArray, len: Int) {
        val a = amount
        if (a <= 0f) return
        val size = buffer.size
        val base = (1L * sampleRate / 1000).toInt().coerceIn(1, size / 3)
        val depth = (10L * sampleRate / 1000).toInt().coerceAtLeast(1)
        var wp = writePos
        var ph = phase
        val wet = a * 0.7f
        for (i in 0 until len) {
            buffer[wp] = buf[i]
            val d = base + (depth * (0.5 + 0.5 * sin(ph))).toInt()
            val delayed = readDelayed(buffer, size, wp, d)
            // 干湿混合：经典镶边为 (x + delayed) / 2
            buf[i] = buf[i] + (delayed - buf[i]) * wet
            wp++
            if (wp >= size) wp = 0
            ph += dPhase
            if (ph >= 2.0 * PI) ph -= 2.0 * PI
        }
        writePos = wp
        phase = ph
    }

    fun reset() {
        buffer.fill(0f)
        writePos = 0
        phase = 0.0
    }
}

/** 颤音：y = x · (1 − a·(1 − cos(2πft))/2)，t ≈ 5.5Hz。 */
class Tremolo(
    private val sampleRate: Int,
    frequency: Float = 5.5f,
) {
    private val dPhase = 2.0 * PI * frequency / sampleRate
    private var phase = 0.0

    @Volatile
    var amount: Float = 0f

    fun process(buf: FloatArray, len: Int) {
        val a = amount
        if (a <= 0f) return
        var ph = phase
        val depth = a.coerceIn(0f, 1f) * 0.9f
        for (i in 0 until len) {
            val mod = 1f - depth * (0.5f - 0.5f * cos(ph).toFloat())
            buf[i] *= mod
            ph += dPhase
            if (ph >= 2.0 * PI) ph -= 2.0 * PI
        }
        phase = ph
    }

    fun reset() {
        phase = 0.0
    }
}

/**
 * 电话音：二阶带通（中心 ~1.2kHz，Q≈0.9）+ 轻微饱和 + 输出增益补偿。
 * 极简状态变量滤波（Chamberlin SVF），零分配。
 */
class Telephone(private val sampleRate: Int) {
    // 二阶 SVF 系数
    private var f = 0f
    private var q = 0f
    private var lp = 0f
    private var bp = 0f
    private var hp = 0f
    private var low = 0f
    private var band = 0f
    private var high = 0f

    @Volatile
    var amount: Float = 0f

    private fun tune() {
        val center = 1200.0
        val qv = 0.9
        val w0 = 2.0 * PI * center / sampleRate
        val c = 1.0 / qv
        f = (2.0 * sin(w0 / 2.0)).toFloat()
        q = c.toFloat()
    }

    fun process(buf: FloatArray, len: Int) {
        val a = amount
        if (a <= 0f) return
        tune()
        var l = low; var b = band; var h = high
        for (i in 0 until len) {
            val x = buf[i]
            l += f * b
            h = x - l - q * b
            b += f * h
            // 带通输出 + 轻微饱和
            val shaped = (b * 3.0f).let { v -> if (v > 1f) 1f else if (v < -1f) -1f else v }
            val wet = shaped * 1.2f
            buf[i] = x + (wet - x) * a
        }
        low = l; band = b; high = h
    }

    fun reset() {
        lp = 0f; bp = 0f; hp = 0f
        low = 0f; band = 0f; high = 0f
    }
}

/** 低音增强：一阶低通分频（~250Hz），提升低频段并做输出补偿。 */
class BassBoost(private val sampleRate: Int) {
    private var lp = 0f
    private val alpha = (1.0 - exp(-2.0 * PI * 250.0 / sampleRate)).toFloat()

    @Volatile
    var gainDb: Float = 0f

    fun process(buf: FloatArray, len: Int) {
        val g = gainDb
        if (g == 0f) return
        val boost = exp(g * 0.115129f) // dB → 线性
        val a = alpha
        var l = lp
        for (i in 0 until len) {
            val x = buf[i]
            l += a * (x - l)
            val bass = l
            val rest = x - bass
            buf[i] = rest + bass * boost
        }
        lp = l
    }

    fun reset() {
        lp = 0f
    }
}

/** 临场感：二阶峰值 EQ（中心 ~3kHz，Q=1.2）提升人声清晰度。 */
class Presence(private val sampleRate: Int) {
    private var low = 0f
    private var band = 0f
    private var high = 0f

    @Volatile
    var gainDb: Float = 0f

    fun process(buf: FloatArray, len: Int) {
        val g = gainDb
        if (g == 0f) return
        val center = 3000.0
        val qv = 1.2
        val w0 = 2.0 * PI * center / sampleRate
        val c = 1.0 / qv
        val f = (2.0 * sin(w0 / 2.0)).toFloat()
        val gain = exp(g * 0.115129f)
        var l = low; var b = band; var h = high
        for (i in 0 until len) {
            val x = buf[i]
            l += f * b
            h = x - l - c.toFloat() * b
            b += f * h
            buf[i] = x + b * (gain - 1f)
        }
        low = l; band = b; high = h
    }

    fun reset() {
        low = 0f; band = 0f; high = 0f
    }
}

// ---------------- 内部工具 ----------------

private fun readDelayed(buf: FloatArray, size: Int, wp: Int, delay: Int): Float {
    var rp = wp - delay
    if (rp < 0) rp += size
    return buf[rp]
}
