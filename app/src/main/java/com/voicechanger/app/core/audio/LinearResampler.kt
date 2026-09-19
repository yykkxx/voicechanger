package com.voicechanger.app.core.audio

import kotlin.math.floor

/**
 * 基础线性插值流式重采样器（实验性）。
 *
 * 用途：仅当设备边界无法原生提供 48 kHz 时启用（例如部分 Bluetooth 链路）。
 * 维护跨块相位与上一采样，discontinuity 时必须调用 [reset]。
 *
 * 注意：为线性插值，音质有限；生产期可替换为更高阶实现。
 * 不依赖 android.*，可直接 JVM 单测（长度与连续性）。
 */
class LinearResampler(
    private val srcRate: Int,
    private val dstRate: Int,
) {
    init {
        require(srcRate > 0 && dstRate > 0)
    }

    private var prevSample: Float = 0f
    private var sourcePos: Double = 0.0

    fun reset() {
        prevSample = 0f
        sourcePos = 0.0
    }

    /**
     * 处理一块输入，写入 [output]，返回输出样本数。
     * [output] 建议不低于 inputLen * dstRate / srcRate + 8。
     */
    fun process(input: ShortArray, inputLen: Int, output: ShortArray): Int {
        if (inputLen <= 0) return 0
        if (srcRate == dstRate) {
            val n = if (inputLen < output.size) inputLen else output.size
            System.arraycopy(input, 0, output, 0, n)
            return n
        }
        var outN = 0
        val step = srcRate.toDouble() / dstRate.toDouble()
        // sourcePos 语义：0 = 上一块最后采样；k = 当前块 input[k-1]
        while (outN < output.size) {
            val floorPos = floor(sourcePos).toInt()
            if (floorPos >= inputLen) break
            if (floorPos < 0) break
            val frac = (sourcePos - floorPos).toFloat()
            val a = if (floorPos - 1 < 0) prevSample else input[floorPos - 1].toFloat()
            val b = input[floorPos].toFloat()
            val v = a + (b - a) * frac
            output[outN++] = v.toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                .toShort()
            sourcePos += step
        }
        // 块的尾部（如果有）丢弃，保证延迟有界；相位与新 prev 重置到块末尾。
        sourcePos -= inputLen
        if (sourcePos < 0.0) sourcePos = 0.0
        prevSample = input[inputLen - 1].toFloat()
        return outN
    }
}