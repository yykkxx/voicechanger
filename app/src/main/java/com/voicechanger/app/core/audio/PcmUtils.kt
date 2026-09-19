package com.voicechanger.app.core.audio

import kotlin.math.pow
import kotlin.math.sqrt

/**
 * PCM 工具：字节序转换、增益、限幅、声道转换、RMS。
 * 全部为静态方法、无分配（写入由调用方提供的目标缓冲）。
 * 不依赖 android.*，可直接 JVM 单测。
 */
object PcmUtils {

    // ---------- 字节 与 S16LE 互转 ----------

    /** 将 S16LE 字节读入 short[]。返回写入的样本数。 */
    fun bytesToShortsLE(
        src: ByteArray,
        srcOffset: Int,
        dst: ShortArray,
        dstOffset: Int,
        sampleCount: Int,
    ): Int {
        require(srcOffset + sampleCount * 2 <= src.size) { "src overflow" }
        require(dstOffset + sampleCount <= dst.size) { "dst overflow" }
        var si = srcOffset
        var di = dstOffset
        repeat(sampleCount) {
            val lo = src[si].toInt() and 0xFF
            val hi = src[si + 1].toInt()
            dst[di] = ((hi shl 8) or lo).toShort()
            si += 2
            di += 1
        }
        return sampleCount
    }

    /** 将 short[] 写为 S16LE 字节。返回写入的字节数。 */
    fun shortsToBytesLE(
        src: ShortArray,
        srcOffset: Int,
        dst: ByteArray,
        dstOffset: Int,
        sampleCount: Int,
    ): Int {
        require(srcOffset + sampleCount <= src.size) { "src overflow" }
        require(dstOffset + sampleCount * 2 <= dst.size) { "dst overflow" }
        var si = srcOffset
        var di = dstOffset
        repeat(sampleCount) {
            val v = src[si].toInt()
            dst[di] = (v and 0xFF).toByte()
            dst[di + 1] = ((v shr 8) and 0xFF).toByte()
            si += 1
            di += 2
        }
        return sampleCount * 2
    }

    // ---------- 增益 / 限幅 ----------

    fun dbToLinear(db: Float): Float = 10f.pow(db / 20f)

    fun linearToDb(linear: Float): Float =
        if (linear <= 0f) -120f else 20f * kotlin.math.log10(linear)

    /** 原地应用线性增益（含饱和保护）。 */
    fun applyGainInPlace(buf: ShortArray, length: Int, gainLinear: Float) {
        if (gainLinear == 1f) return
        for (i in 0 until length) {
            val v = (buf[i] * gainLinear).toInt()
            buf[i] = when {
                v > Short.MAX_VALUE -> Short.MAX_VALUE
                v < Short.MIN_VALUE -> Short.MIN_VALUE
                else -> v.toShort()
            }
        }
    }

    /** 原地硬限幅到 [-ceiling, ceiling]。 */
    fun limitInPlace(buf: ShortArray, length: Int, ceiling: Int = Short.MAX_VALUE.toInt()) {
        val hi = ceiling.coerceAtMost(Short.MAX_VALUE.toInt())
        val lo = -hi
        for (i in 0 until length) {
            val v = buf[i].toInt()
            if (v > hi) buf[i] = hi.toShort()
            else if (v < lo) buf[i] = lo.toShort()
        }
    }

    /**
     * 原地软限幅（tanh 近似）：超过阈值的部分平滑压缩，避免硬削波爆音。
     * threshold 为归一化幅值（0..1）。
     */
    fun softClipInPlace(buf: ShortArray, length: Int, threshold: Float = 0.8f) {
        val t = threshold.coerceIn(0.1f, 0.99f)
        val tSamp = t * Short.MAX_VALUE
        for (i in 0 until length) {
            val v = buf[i].toFloat()
            val abs = kotlin.math.abs(v)
            if (abs <= tSamp) continue
            val over = (abs - tSamp) / (Short.MAX_VALUE - tSamp)
            // 1 - 1/(1+over) 的平滑曲线
            val compressed = tSamp + (Short.MAX_VALUE - tSamp) * (over / (1f + over))
            buf[i] = (if (v < 0) -compressed else compressed)
                .toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
    }

    // ---------- 声道转换 ----------

    /** mono → stereo 复制（每个样本写两遍）。返回写入样本数（= frames*2）。 */
    fun monoToStereoInPlace(mono: ShortArray, frames: Int): Int {
        require(frames * 2 <= mono.size) { "buffer too small for stereo expansion" }
        for (i in frames - 1 downTo 0) {
            val v = mono[i]
            mono[i * 2] = v
            mono[i * 2 + 1] = v
        }
        return frames * 2
    }

    /** stereo 交错 → mono 混合平均。返回 mono 帧数。 */
    fun stereoToMonoInPlace(stereo: ShortArray, frames: Int): Int {
        for (i in 0 until frames) {
            val l = stereo[i * 2].toInt()
            val r = stereo[i * 2 + 1].toInt()
            stereo[i] = ((l + r) / 2).toShort()
        }
        return frames
    }

    // ---------- 电平 ----------

    /** 归一化 RMS（0..1）。 */
    fun rms(buf: ShortArray, length: Int): Float {
        if (length <= 0) return 0f
        var acc = 0.0
        for (i in 0 until length) {
            val v = buf[i].toDouble() / Short.MAX_VALUE
            acc += v * v
        }
        return sqrt(acc / length).toFloat().coerceIn(0f, 1f)
    }

    /** dry/wet 混合（原地）：out = wet*mix + dry*(1-mix)。 */
    fun mixInPlace(wet: ShortArray, dry: ShortArray, length: Int, mix: Float) {
        val m = mix.coerceIn(0f, 1f)
        val inv = 1f - m
        for (i in 0 until length) {
            val v = wet[i] * m + dry[i] * inv
            wet[i] = v.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
    }
}