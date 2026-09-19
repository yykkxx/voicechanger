package com.voicechanger.app.core.protocol

/**
 * VCP/1 包头编解码。
 *
 * 包头固定 24 字节，big-endian（network byte order）：
 * ```
 * offset 0  : magic    4B  "VCP1"
 * offset 4  : version  1B
 * offset 5  : type     1B
 * offset 6  : flags    2B
 * offset 8  : sequence 4B
 * offset 12 : tsUs     8B
 * offset 20 : payloadLength 4B
 * ```
 * 注意：PCM payload 本身为小端 S16LE，与包头字节序不同（见 plan/03 第 4.1 节）。
 */
object VcpCodec {

    fun writeHeader(
        dst: ByteArray,
        offset: Int,
        type: Int,
        flags: Int,
        sequence: Long,
        timestampUs: Long,
        payloadLength: Int,
    ) {
        require(offset + Vcp.HEADER_SIZE <= dst.size) { "dst too small" }
        require(payloadLength in 0..Vcp.MAX_PAYLOAD) { "payloadLength out of range: $payloadLength" }
        Vcp.MAGIC.copyInto(dst, offset)
        dst[offset + 4] = Vcp.VERSION.toByte()
        dst[offset + 5] = type.toByte()
        putU16(dst, offset + 6, flags)
        putU32(dst, offset + 8, sequence)
        putU64(dst, offset + 12, timestampUs)
        putU32(dst, offset + 20, payloadLength.toLong())
    }

    /** 读取并校验包头字段；magic 不匹配返回 null。仅做最小校验，其他交给解析器状态机。 */
    fun readHeader(src: ByteArray, offset: Int): Header? {
        if (offset + Vcp.HEADER_SIZE > src.size) return null
        if (src[offset] != Vcp.MAGIC[0] || src[offset + 1] != Vcp.MAGIC[1] ||
            src[offset + 2] != Vcp.MAGIC[2] || src[offset + 3] != Vcp.MAGIC[3]
        ) return null
        return Header(
            version = src[offset + 4].toInt() and 0xFF,
            type = src[offset + 5].toInt() and 0xFF,
            flags = getU16(src, offset + 6),
            sequence = getU32(src, offset + 8),
            timestampUs = getU64(src, offset + 12),
            payloadLength = getU32(src, offset + 20),
        )
    }

    data class Header(
        val version: Int,
        val type: Int,
        val flags: Int,
        val sequence: Long,
        val timestampUs: Long,
        val payloadLength: Long,
    )

    // ---------- big-endian helpers ----------

    fun putU16(dst: ByteArray, offset: Int, value: Int) {
        dst[offset] = ((value ushr 8) and 0xFF).toByte()
        dst[offset + 1] = (value and 0xFF).toByte()
    }

    fun getU16(src: ByteArray, offset: Int): Int =
        ((src[offset].toInt() and 0xFF) shl 8) or (src[offset + 1].toInt() and 0xFF)

    fun putU32(dst: ByteArray, offset: Int, value: Long) {
        dst[offset] = ((value ushr 24) and 0xFF).toByte()
        dst[offset + 1] = ((value ushr 16) and 0xFF).toByte()
        dst[offset + 2] = ((value ushr 8) and 0xFF).toByte()
        dst[offset + 3] = (value and 0xFF).toByte()
    }

    fun getU32(src: ByteArray, offset: Int): Long =
        ((src[offset].toLong() and 0xFF) shl 24) or
            ((src[offset + 1].toLong() and 0xFF) shl 16) or
            ((src[offset + 2].toLong() and 0xFF) shl 8) or
            (src[offset + 3].toLong() and 0xFF)

    fun putU64(dst: ByteArray, offset: Int, value: Long) {
        for (i in 0 until 8) {
            dst[offset + i] = ((value ushr (56 - 8 * i)) and 0xFF).toByte()
        }
    }

    fun getU64(src: ByteArray, offset: Int): Long {
        var v = 0L
        for (i in 0 until 8) {
            v = (v shl 8) or (src[offset + i].toLong() and 0xFF)
        }
        return v
    }
}