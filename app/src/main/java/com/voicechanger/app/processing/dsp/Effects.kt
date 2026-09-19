package com.voicechanger.app.processing.dsp

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.tanh

/**
 * 轻量效果器集合：全部工作在 float 缓冲上，实时线程零分配。
 *
 * - [TiltEq]：高低频倾斜（明亮/低沉）；
 * - [RingModulator]：环形调制（机器人）；
 * - [Distortion]：软饱和（怪兽/嘶吼）；
 * - [Echo]：单抽头延迟回声（空灵）。
 */

/** 一阶倾斜 EQ：低通分频 ~1.2kHz，提升/衰减高频段。 */
class TiltEq(private val sampleRate: Int) {
    private var lp = 0f
    private val alpha = (1.0 - exp(-2.0 * PI * 1200.0 / sampleRate)).toFloat()

    /** 高频段相对增益（线性，1.0 = 平坦）。 */
    @Volatile
    var tiltGain: Float = 1f

    fun process(buf: FloatArray, len: Int) {
        val g = tiltGain
        if (g == 1f) return
        val a = alpha
        var l = lp
        for (i in 0 until len) {
            val x = buf[i]
            l += a * (x - l)
            val hp = x - l
            buf[i] = l + hp * g
        }
        lp = l
    }

    fun reset() {
        lp = 0f
    }
}

/** 环形调制：y = x * ((1-a) + a*cos(2πft))；a=1 时为纯环形调制（机械音）。 */
class RingModulator(
    private val sampleRate: Int,
    frequency: Float = 90f,
) {
    private val dPhase = 2.0 * PI * frequency / sampleRate
    private var phase = 0.0

    /** 调制深度 0..1。 */
    @Volatile
    var amount: Float = 0f

    fun process(buf: FloatArray, len: Int) {
        val a = amount
        if (a <= 0f) return
        var ph = phase
        for (i in 0 until len) {
            val carrier = (1f - a) + a * cos(ph).toFloat()
            buf[i] *= carrier
            ph += dPhase
            if (ph >= 2.0 * PI) ph -= 2.0 * PI
        }
        phase = ph
    }

    fun reset() {
        phase = 0.0
    }
}

/** 软饱和失真：tanh 驱动 + 干湿混合。 */
class Distortion {
    /** 驱动量 0..1。 */
    @Volatile
    var amount: Float = 0f

    fun process(buf: FloatArray, len: Int) {
        val a = amount
        if (a <= 0f) return
        val k = 1.0 + a * 9.0
        val norm = (1.0 / tanh(k)).toFloat()
        for (i in 0 until len) {
            val x = buf[i]
            val shaped = (tanh(k * x).toFloat()) * norm
            buf[i] = x + (shaped - x) * a
        }
    }

    fun reset() = Unit
}

/** 单抽头回声：out = x + a * z^-d；buffer = x + fb * z^-d。 */
class Echo(
    private val sampleRate: Int,
    maxDelayMs: Int = 600,
) {
    private val buffer = FloatArray(sampleRate * maxDelayMs / 1000)
    private var writePos = 0

    /** 湿声比例 0..1。 */
    @Volatile
    var amount: Float = 0f

    /** 延迟毫秒（10..600）。 */
    @Volatile
    var delayMs: Int = 140

    /** 反馈 0..0.9。 */
    @Volatile
    var feedback: Float = 0.3f

    fun process(buf: FloatArray, len: Int) {
        val a = amount
        if (a <= 0f) return
        val size = buffer.size
        val d = (delayMs.coerceIn(10, 600).toLong() * sampleRate / 1000).toInt()
            .coerceIn(1, size - 1)
        val fb = feedback.coerceIn(0f, 0.9f)
        var wp = writePos
        for (i in 0 until len) {
            var rp = wp - d
            if (rp < 0) rp += size
            val delayed = buffer[rp]
            buffer[wp] = buf[i] + delayed * fb
            buf[i] = buf[i] + delayed * a
            wp++
            if (wp >= size) wp = 0
        }
        writePos = wp
    }

    fun reset() {
        buffer.fill(0f)
        writePos = 0
    }
}

/** 公共软限幅（float 域）：阈值 0.8，超出部分平滑压缩。 */
object FloatSoftClip {
    fun process(buf: FloatArray, len: Int, threshold: Float = 0.8f) {
        val t = threshold.coerceIn(0.1f, 0.99f)
        for (i in 0 until len) {
            val x = buf[i]
            val ax = abs(x)
            if (ax <= t) continue
            val over = (ax - t) / (1f - t)
            val compressed = t + (1f - t) * (over / (1f + over))
            buf[i] = if (x < 0) -compressed else compressed
        }
    }
}