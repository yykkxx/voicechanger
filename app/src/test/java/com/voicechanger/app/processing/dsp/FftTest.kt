package com.voicechanger.app.processing.dsp

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class FftTest {

    @Test
    fun roundTripIdentity() {
        val m = 8 // 复数点数量
        val data = FloatArray(2 * m) { i -> (i % 5).toFloat() }
        val original = data.copyOf()

        Fft.transform(data, 2 * m, -1)
        Fft.transform(data, 2 * m, +1)

        // 正逆成对使用后，按复数点数（m）归一化还原
        for (i in 0 until 2 * m) {
            val v = data[i] / m
            assertTrue(
                "index $i: expected ${original[i]} got $v",
                kotlin.math.abs(v - original[i]) < 0.01f,
            )
        }
    }

    @Test
    fun detectsKnownFrequencyBin() {
        val m = 64 // 复数点数量
        // 复指数 e^{i2π·3·i/m} → 能量集中在复 bin 3
        val data = FloatArray(2 * m)
        for (i in 0 until m) {
            data[2 * i] = cos(2.0 * PI * 3 * i / m).toFloat()
            data[2 * i + 1] = sin(2.0 * PI * 3 * i / m).toFloat()
        }
        // n 参数是浮点数组长度 = 2 × 复数点
        Fft.transform(data, 2 * m, -1)

        var maxBin = 0
        var maxMag = 0f
        for (k in 0 until m) {
            val re = data[2 * k]
            val im = data[2 * k + 1]
            val mag = re * re + im * im
            if (mag > maxMag) {
                maxMag = mag
                maxBin = k
            }
        }
        assertTrue("expected bin 3, got $maxBin", maxBin == 3)
    }

    @Test
    fun powerOfTwoRequirement() {
        val data = FloatArray(64)
        var threw = false
        try {
            // 24 个复数点（48 浮点）不是 2 的幂 → 必须抛异常
            Fft.transform(data, 48, -1)
        } catch (_: IllegalArgumentException) {
            threw = true
        }
        assertTrue("non-power-of-two must throw", threw)
    }
}