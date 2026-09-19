package com.voicechanger.app.core.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class VcpCodecTest {

    @Test
    fun headerRoundTrip() {
        val dst = ByteArray(Vcp.HEADER_SIZE)
        VcpCodec.writeHeader(
            dst, 0,
            type = Vcp.TYPE_PCM_OUTPUT,
            flags = Vcp.FLAG_DISCONTINUITY,
            sequence = 0x89ABCDEFL,
            timestampUs = 0x1122334455667788L,
            payloadLength = 960,
        )
        val h = VcpCodec.readHeader(dst, 0)
        assertNotNull(h)
        assertEquals(Vcp.VERSION, h!!.version)
        assertEquals(Vcp.TYPE_PCM_OUTPUT, h.type)
        assertEquals(Vcp.FLAG_DISCONTINUITY, h.flags)
        assertEquals(0x89ABCDEFL, h.sequence)
        assertEquals(0x1122334455667788L, h.timestampUs)
        assertEquals(960L, h.payloadLength)
    }

    @Test
    fun magicBytesAreVcp1() {
        val dst = ByteArray(Vcp.HEADER_SIZE)
        VcpCodec.writeHeader(dst, 0, type = 0, flags = 0, sequence = 0, timestampUs = 0, payloadLength = 0)
        assertEquals('V'.code.toByte(), dst[0])
        assertEquals('C'.code.toByte(), dst[1])
        assertEquals('P'.code.toByte(), dst[2])
        assertEquals('1'.code.toByte(), dst[3])
    }

    @Test
    fun badMagicReturnsNull() {
        val garbage = ByteArray(Vcp.HEADER_SIZE + 4)
        assertNull(VcpCodec.readHeader(garbage, 0))
    }

    @Test
    fun u16BigEndianFormat() {
        val dst = ByteArray(2)
        VcpCodec.putU16(dst, 0, 0xABCD)
        assertEquals(0xAB, dst[0].toInt() and 0xFF)
        assertEquals(0xCD, dst[1].toInt() and 0xFF)
        assertEquals(0xABCD, VcpCodec.getU16(dst, 0))
    }

    @Test
    fun u32BigEndianFormat() {
        val dst = ByteArray(4)
        VcpCodec.putU32(dst, 0, 0x01020304L)
        assertEquals(0x01, dst[0].toInt() and 0xFF)
        assertEquals(0x02, dst[1].toInt() and 0xFF)
        assertEquals(0x03, dst[2].toInt() and 0xFF)
        assertEquals(0x04, dst[3].toInt() and 0xFF)
        assertEquals(0x01020304L, VcpCodec.getU32(dst, 0))
    }

    @Test
    fun u64RoundTrip() {
        val dst = ByteArray(8)
        VcpCodec.putU64(dst, 0, Long.MIN_VALUE)
        assertEquals(Long.MIN_VALUE, VcpCodec.getU64(dst, 0))

        VcpCodec.putU64(dst, 0, -1L)
        assertEquals(-1L, VcpCodec.getU64(dst, 0))
    }
}