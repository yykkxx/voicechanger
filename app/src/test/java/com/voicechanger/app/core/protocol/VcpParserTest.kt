package com.voicechanger.app.core.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VcpParserTest {

    private fun pcmMessage(seq: Long, samples: ShortArray): ByteArray =
        VcpMessages.pcm(
            type = Vcp.TYPE_PCM_INPUT,
            sequence = seq,
            timestampUs = 123456789L,
            flags = 0,
            pcm = samples,
        )

    @Test
    fun singleCompleteMessage() {
        val parser = VcpParser()
        val bytes = pcmMessage(42, shortArrayOf(1, -2, 3))
        parser.feed(bytes)

        val msg = parser.next()
        assertNotNull(msg)
        assertEquals(Vcp.TYPE_PCM_INPUT, msg!!.type)
        assertEquals(42L, msg.sequence)
        assertEquals(123456789L, msg.timestampUs)
        assertEquals(6, msg.payload.size)

        // payload 是 S16LE
        assertEquals(1, (msg.payload[0].toInt() or (msg.payload[1].toInt() shl 8)).toShort().toInt())
        assertEquals(-2, (msg.payload[2].toInt() or (msg.payload[3].toInt() shl 8)).toShort().toInt())

        assertNull(parser.next())
    }

    @Test
    fun splitAcrossFeeds() {
        val parser = VcpParser()
        val bytes = pcmMessage(7, shortArrayOf(100, 200))

        // 逐字节喂入，模拟 TCP 拆包
        for (b in bytes) {
            parser.feed(byteArrayOf(b))
        }
        val msg = parser.next()
        assertNotNull(msg)
        assertEquals(7L, msg!!.sequence)
        assertEquals(4, msg.payload.size)
        assertNull(parser.next())
    }

    @Test
    fun stickyTwoMessagesInOneFeed() {
        val parser = VcpParser()
        val m1 = pcmMessage(1, shortArrayOf(11))
        val m2 = pcmMessage(2, shortArrayOf(22))
        val combined = m1 + m2
        parser.feed(combined)

        val a = parser.next()
        val b = parser.next()
        assertNotNull(a)
        assertNotNull(b)
        assertEquals(1L, a!!.sequence)
        assertEquals(2L, b!!.sequence)
        assertNull(parser.next())
    }

    @Test
    fun partialHeaderThenRestSplit() {
        val parser = VcpParser()
        val bytes = pcmMessage(9, shortArrayOf(7, 8))
        // 前半段（含部分 header）
        parser.feed(bytes, 0, 10)
        assertNull(parser.next())
        // 剩余部分
        parser.feed(bytes, 10, bytes.size - 10)
        assertNotNull(parser.next())
    }

    @Test
    fun badMagicTriggersErrorAndReset() {
        var error: String? = null
        val parser = VcpParser { error = it }

        val garbage = ByteArray(32) { 0x41 } // "AAAA..."
        parser.feed(garbage)
        assertNull(parser.next())
        assertNotNull("error callback expected", error)

        // 重置后仍可继续工作
        parser.reset()
        parser.feed(pcmMessage(5, shortArrayOf(1)))
        assertNotNull(parser.next())
    }

    @Test
    fun oversizedPayloadRejected() {
        var error: String? = null
        val parser = VcpParser { error = it }

        val header = ByteArray(Vcp.HEADER_SIZE)
        VcpCodec.writeHeader(
            header, 0,
            type = Vcp.TYPE_PCM_INPUT,
            flags = 0,
            sequence = 0,
            timestampUs = 0,
            payloadLength = 0, // 先写 0，再手工改长度到超限（绕过 writeHeader 的校验）
        )
        // 手工覆盖 payloadLength 字段为大值（big-endian, offset 20）
        header[20] = 0x7F
        header[21] = 0xFF.toByte()
        header[22] = 0xFF.toByte()
        header[23] = 0xFF.toByte()

        parser.feed(header)
        assertNull(parser.next())
        assertNotNull("oversized payload must trigger error", error)
    }

    @Test
    fun flagsParsedCorrectly() {
        val parser = VcpParser()
        val flags = Vcp.FLAG_DISCONTINUITY or Vcp.FLAG_MUTED
        val bytes = VcpMessages.pcm(
            type = Vcp.TYPE_PCM_OUTPUT,
            sequence = 3,
            timestampUs = 0,
            flags = flags,
            pcm = shortArrayOf(0),
        )
        parser.feed(bytes)
        val msg = parser.next()
        assertNotNull(msg)
        assertTrue(msg!!.hasDiscontinuity)
        assertTrue(msg.isMuted)
    }

    @Test
    fun controlMessageWithJsonPayload() {
        val parser = VcpParser()
        val json = """{"accepted":true}"""
        parser.feed(VcpMessages.jsonPayload(Vcp.TYPE_HELLO_ACK, json))
        val msg = parser.next()
        assertNotNull(msg)
        assertEquals(Vcp.TYPE_HELLO_ACK, msg!!.type)
        assertEquals(json, String(msg.payload, Charsets.UTF_8))
    }
}