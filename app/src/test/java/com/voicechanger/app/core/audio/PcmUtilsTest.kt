package com.voicechanger.app.core.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PcmUtilsTest {

    @Test
    fun bytesShortsRoundTrip() {
        val samples = shortArrayOf(0, 1, -1, Short.MAX_VALUE, Short.MIN_VALUE, 12345, -12345)
        val bytes = ByteArray(samples.size * 2)
        PcmUtils.shortsToBytesLE(samples, 0, bytes, 0, samples.size)

        val back = ShortArray(samples.size)
        PcmUtils.bytesToShortsLE(bytes, 0, back, 0, samples.size)
        for (i in samples.indices) {
            assertEquals("index $i", samples[i].toInt(), back[i].toInt())
        }
    }

    @Test
    fun s16leEndianness() {
        val bytes = ByteArray(2)
        PcmUtils.shortsToBytesLE(shortArrayOf(0x1234), 0, bytes, 0, 1)
        // LE: 低字节在前
        assertEquals(0x34, bytes[0].toInt() and 0xFF)
        assertEquals(0x12, bytes[1].toInt() and 0xFF)
    }

    @Test
    fun applyGainClamps() {
        val buf = shortArrayOf(1000, -1000, 32000, -32000)
        PcmUtils.applyGainInPlace(buf, 4, 2f)
        assertEquals(2000, buf[0].toInt())
        assertEquals(-2000, buf[1].toInt())
        assertEquals(Short.MAX_VALUE.toInt(), buf[2].toInt())
        assertEquals(Short.MIN_VALUE.toInt(), buf[3].toInt())
    }

    @Test
    fun softClipCompressesAboveThreshold() {
        val buf = shortArrayOf(Short.MAX_VALUE)
        PcmUtils.softClipInPlace(buf, 1, threshold = 0.5f)
        assertTrue("soft clip must not exceed max", buf[0] <= Short.MAX_VALUE)
        assertTrue("soft clip must compress below original", buf[0] < Short.MAX_VALUE)
    }

    @Test
    fun monoToStereoAndBack() {
        val interleaved = ShortArray(8)
        interleaved[0] = 100
        interleaved[1] = 200
        // 先构造 mono [100,200] → stereo [100,100,200,200]
        val mono = ShortArray(4)
        mono[0] = 100
        mono[1] = 200
        PcmUtils.monoToStereoInPlace(mono, 2)
        assertEquals(100, mono[0].toInt())
        assertEquals(100, mono[1].toInt())
        assertEquals(200, mono[2].toInt())
        assertEquals(200, mono[3].toInt())

        // stereo → mono 平均
        val frames = PcmUtils.stereoToMonoInPlace(mono, 2)
        assertEquals(2, frames)
        assertEquals(100, mono[0].toInt())
        assertEquals(200, mono[1].toInt())
    }

    @Test
    fun rmsOfFullScaleSilenceAndSquare() {
        assertEquals(0f, PcmUtils.rms(ShortArray(10), 10), 0.0001f)

        val full = ShortArray(10) { Short.MAX_VALUE }
        assertTrue(PcmUtils.rms(full, 10) > 0.99f)

        val half = ShortArray(10) { (Short.MAX_VALUE / 2).toShort() }
        val r = PcmUtils.rms(half, 10)
        assertTrue("rms ~0.5, got $r", r > 0.45f && r < 0.55f)
    }

    @Test
    fun mixInPlaceWeights() {
        val wet = shortArrayOf(1000, 1000)
        val dry = shortArrayOf(0, 2000)
        PcmUtils.mixInPlace(wet, dry, 2, 0.5f)
        assertEquals(500, wet[0].toInt())
        assertEquals(1500, wet[1].toInt())
    }

    @Test
    fun dbConversions() {
        assertEquals(1f, PcmUtils.dbToLinear(0f), 0.001f)
        assertEquals(0.5f, PcmUtils.dbToLinear(-6.0206f), 0.01f)
        assertEquals(-6.0206f, PcmUtils.linearToDb(0.5f), 0.01f)
        assertTrue(PcmUtils.linearToDb(0f) <= -120f)
    }
}