package com.voicechanger.app.core.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpscShortRingBufferTest {

    @Test
    fun writeReadRoundTrip() {
        val ring = SpscShortRingBuffer(capacity = 4, frameSamples = 3)
        val frame = shortArrayOf(1, 2, 3)
        assertTrue(ring.write(frame))

        val out = ShortArray(3)
        assertEquals(3, ring.read(out))
        assertEquals(1, out[0].toInt())
        assertEquals(2, out[1].toInt())
        assertEquals(3, out[2].toInt())
        assertTrue(ring.isEmpty)
    }

    @Test
    fun fullPolicyDropNewest() {
        val ring = SpscShortRingBuffer(capacity = 2, frameSamples = 1)
        assertTrue(ring.write(shortArrayOf(10)))
        assertTrue(ring.write(shortArrayOf(20)))
        // 第 3 帧应被丢弃（drop-newest 策略）
        assertFalse(ring.write(shortArrayOf(30)))
        assertEquals(1, ring.droppedCount.get())

        val out = ShortArray(1)
        ring.read(out)
        assertEquals(10, out[0].toInt())
        ring.read(out)
        assertEquals(20, out[0].toInt())
        assertTrue(ring.isEmpty)
    }

    @Test
    fun wrapAroundKeepsOrder() {
        val ring = SpscShortRingBuffer(capacity = 3, frameSamples = 1)
        val out = ShortArray(1)
        // 读入读出多轮，验证回绕索引
        for (round in 0 until 10) {
            for (i in 0 until 3) {
                assertTrue(ring.write(shortArrayOf((round * 10 + i).toShort())))
            }
            for (i in 0 until 3) {
                assertEquals(1, ring.read(out))
                assertEquals((round * 10 + i).toShort().toInt(), out[0].toInt())
            }
        }
    }

    @Test
    fun shortFrameZeroPadsTail() {
        val ring = SpscShortRingBuffer(capacity = 2, frameSamples = 4)
        assertTrue(ring.write(shortArrayOf(7, 8), length = 2))
        val out = ShortArray(4)
        assertEquals(4, ring.read(out))
        assertEquals(7, out[0].toInt())
        assertEquals(8, out[1].toInt())
        assertEquals(0, out[2].toInt())
        assertEquals(0, out[3].toInt())
    }

    @Test
    fun clearResetsAll() {
        val ring = SpscShortRingBuffer(capacity = 2, frameSamples = 2)
        ring.write(shortArrayOf(1, 2))
        ring.clear()
        assertTrue(ring.isEmpty)
        assertEquals(0, ring.available())

        val out = ShortArray(2)
        assertEquals(0, ring.read(out))
    }
}