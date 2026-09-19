package com.voicechanger.app.processing.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * 原位复数 FFT（radix-2，交错 re/im 排布）。
 *
 * 约定（重要）：
 * - [n] = **浮点数组长度**（= 2 × 复数点数）；实际复数点数 = n / 2；
 * - sign = -1 正变换；+1 逆变换（不自动缩放：正逆成对使用后需除以复数点数 n/2）；
 * - 仅支持 n/2 为 2 的幂；构造后无内存分配（传入数组原位运算）。
 *
 * 用于 [SmbPitchShifter]（STFT 相位声码器）。
 */
object Fft {

    /**
     * 对 data 原位做 FFT。data 的前 n 个浮点被使用：n 必须为偶数，
     * 且 n/2 为 2 的幂。
     */
    fun transform(data: FloatArray, n: Int, sign: Int) {
        require(n > 1 && n % 2 == 0) { "n must be even: $n" }
        val m = n / 2 // 复数点个数
        require(m and (m - 1) == 0) { "complex points (n/2) must be a power of two: $m" }

        // 1) 位反转置换
        var j = 0
        for (i in 1 until m) {
            var bit = m shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j or bit
            if (i < j) {
                val a = i * 2
                val b = j * 2
                val tr = data[a]; data[a] = data[b]; data[b] = tr
                val ti = data[a + 1]; data[a + 1] = data[b + 1]; data[b + 1] = ti
            }
        }

        // 2) 迭代蝶形
        var len = 2
        while (len <= m) {
            val ang = sign * 2.0 * PI / len
            val wr = cos(ang)
            val wi = sin(ang)
            val half = len / 2
            var i = 0
            while (i < m) {
                var wRe = 1.0
                var wIm = 0.0
                for (k in 0 until half) {
                    val u = (i + k) * 2
                    val v = (i + k + half) * 2
                    val vRe = data[v] * wRe - data[v + 1] * wIm
                    val vIm = data[v] * wIm + data[v + 1] * wRe
                    val uRe = data[u]
                    val uIm = data[u + 1]
                    data[u] = (uRe + vRe).toFloat()
                    data[u + 1] = (uIm + vIm).toFloat()
                    data[v] = (uRe - vRe).toFloat()
                    data[v + 1] = (uIm - vIm).toFloat()
                    val nwr = wRe * wr - wIm * wi
                    wIm = wRe * wi + wIm * wr
                    wRe = nwr
                }
                i += len
            }
            len = len shl 1
        }
    }
}