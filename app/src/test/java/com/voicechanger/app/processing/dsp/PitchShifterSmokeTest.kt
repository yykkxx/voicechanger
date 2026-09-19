package com.voicechanger.app.processing.dsp

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.PI

class PitchShifterSmokeTest {

    @Test
    fun passthroughRatioKeepsSignalEnergy() {
        val shifter = SmbPitchShifter(fftFrameSize = 1024, osamp = 4, sampleRate = 48_000)
        shifter.pitchRatio = 1f

        // 整 bin 频率：48k/1024 × 21 = 984.375Hz
        val n = 24_000
        val input = FloatArray(n) { i -> sin(2.0 * PI * 984.375 * i / 48_000.0).toFloat() * 0.5f }
        val output = FloatArray(n)

        val block = 480
        var off = 0
        while (off < n) {
            val len = minOf(block, n - off)
            val inBlock = input.copyOfRange(off, off + len)
            val outBlock = FloatArray(len)
            shifter.process(inBlock, outBlock, len)
            System.arraycopy(outBlock, 0, output, off, len)
            off += len
        }

        // 后半段（稳态）能量与峰值
        var inputAcc = 0.0
        var outputAcc = 0.0
        var maxIn = 0f
        var maxOut = 0f
        val start = n / 2
        for (i in start until n) {
            inputAcc += input[i] * input[i]
            outputAcc += output[i] * output[i]
            maxIn = maxOf(maxIn, kotlin.math.abs(input[i]))
            maxOut = maxOf(maxOut, kotlin.math.abs(output[i]))
        }
        val ratio = outputAcc / inputAcc
        println("DEBUG passthrough: energyRatio=$ratio peakIn=$maxIn peakOut=$maxOut")
        assertTrue("energy ratio should be ~1, got $ratio", ratio > 0.5 && ratio < 1.6)
    }

    @Test
    fun upsweepProducesBoundedOutput() {
        val shifter = SmbPitchShifter(fftFrameSize = 1024, osamp = 4, sampleRate = 48_000)
        shifter.pitchRatio = 2f

        val n = 9_600
        val input = FloatArray(n) { i -> sin(2.0 * PI * 440.0 * i / 48_000.0).toFloat() * 0.8f }
        val output = FloatArray(n)

        val block = 480
        var off = 0
        while (off < n) {
            val len = minOf(block, n - off)
            val inBlock = input.copyOfRange(off, off + len)
            val outBlock = FloatArray(len)
            shifter.process(inBlock, outBlock, len)
            System.arraycopy(outBlock, 0, output, off, len)
            off += len
        }

        // 输出必须有界（无 NaN/Inf，±3 以内）
        for (i in 0 until n) {
            val v = output[i]
            assertTrue("must be finite", v.isFinite())
            assertTrue("must be bounded: $v", abs(v) < 3f)
        }
        // 稳定段应有可观能量
        var acc = 0.0
        for (i in n / 2 until n) acc += output[i] * output[i]
        assertTrue("steady-state energy expected > 0, got $acc", acc > 1.0)
    }

    @Test
    fun resetClearsState() {
        val shifter = SmbPitchShifter()
        shifter.pitchRatio = 1.5f
        val input = FloatArray(2048) { 0.3f }
        val output = FloatArray(2048)
        shifter.process(input, output, input.size)
        shifter.reset()
        // 复位后延迟线清空：立即处理应输出接近 0 的开头
        val input2 = FloatArray(100) { 0f }
        val output2 = FloatArray(100)
        shifter.process(input2, output2, input2.size)
        var acc = 0f
        for (v in output2) acc += abs(v)
        assertTrue("after reset output should be ~silent near start, acc=$acc", acc < 0.01f)
    }
}