package com.voicechanger.app.core.protocol

/**
 * VCP/1 消息构造便利函数。
 * 这些函数会产生少量分配，属于控制面/网络线程用途；
 * 实时 PCM 热路径应在更高层复用缓冲（后续 socket 实现时处理）。
 */
object VcpMessages {

    fun control(
        type: Int,
        sequence: Long = 0,
        timestampUs: Long = 0,
        flags: Int = 0,
        payload: ByteArray = ByteArray(0),
    ): ByteArray {
        val dst = ByteArray(Vcp.HEADER_SIZE + payload.size)
        VcpCodec.writeHeader(dst, 0, type, flags, sequence, timestampUs, payload.size)
        payload.copyInto(dst, Vcp.HEADER_SIZE)
        return dst
    }

    fun pcm(
        type: Int,
        sequence: Long,
        timestampUs: Long,
        flags: Int,
        pcm: ShortArray,
        sampleCount: Int = pcm.size,
    ): ByteArray {
        val payloadBytes = sampleCount * 2
        val dst = ByteArray(Vcp.HEADER_SIZE + payloadBytes)
        VcpCodec.writeHeader(dst, 0, type, flags, sequence, timestampUs, payloadBytes)
        var di = Vcp.HEADER_SIZE
        var si = 0
        repeat(sampleCount) {
            val v = pcm[si].toInt()
            dst[di] = (v and 0xFF).toByte()
            dst[di + 1] = ((v shr 8) and 0xFF).toByte()
            si += 1
            di += 2
        }
        return dst
    }

    fun jsonPayload(type: Int, json: String, sequence: Long = 0, flags: Int = 0): ByteArray =
        control(type, sequence = sequence, flags = flags, payload = json.toByteArray(Charsets.UTF_8))
}