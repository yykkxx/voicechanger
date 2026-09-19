package com.voicechanger.app.core.protocol

/**
 * VCP/1（Voice Changer Protocol v1）消息类型。
 * 完整规范见 plan/03 第 4 节。
 */
object Vcp {
    val MAGIC = byteArrayOf('V'.code.toByte(), 'C'.code.toByte(), 'P'.code.toByte(), '1'.code.toByte())
    const val VERSION: Int = 1

    const val HEADER_SIZE = 24
    const val MAX_PAYLOAD = 64 * 1024

    // 消息类型
    const val TYPE_HELLO = 0x01
    const val TYPE_HELLO_ACK = 0x02
    const val TYPE_START = 0x03
    const val TYPE_STOP = 0x04
    const val TYPE_PCM_INPUT = 0x10
    const val TYPE_PCM_OUTPUT = 0x11
    const val TYPE_PARAMS = 0x12
    const val TYPE_STATS = 0x20
    const val TYPE_PING = 0x30
    const val TYPE_PONG = 0x31
    const val TYPE_ERROR = 0x7F

    // flags
    const val FLAG_DISCONTINUITY = 0x0001
    const val FLAG_MUTED = 0x0002
    const val FLAG_END_OF_STREAM = 0x0004

    fun typeName(type: Int): String = when (type) {
        TYPE_HELLO -> "HELLO"
        TYPE_HELLO_ACK -> "HELLO_ACK"
        TYPE_START -> "START"
        TYPE_STOP -> "STOP"
        TYPE_PCM_INPUT -> "PCM_INPUT"
        TYPE_PCM_OUTPUT -> "PCM_OUTPUT"
        TYPE_PARAMS -> "PARAMS"
        TYPE_STATS -> "STATS"
        TYPE_PING -> "PING"
        TYPE_PONG -> "PONG"
        TYPE_ERROR -> "ERROR"
        else -> "UNKNOWN(0x" + Integer.toHexString(type) + ")"
    }
}

/** 解析出的单个 VCP/1 消息。 */
data class VcpMessage(
    val type: Int,
    val flags: Int,
    val sequence: Long,
    val timestampUs: Long,
    val payload: ByteArray,
) {
    val hasDiscontinuity: Boolean get() = (flags and Vcp.FLAG_DISCONTINUITY) != 0
    val isMuted: Boolean get() = (flags and Vcp.FLAG_MUTED) != 0
    val isEndOfStream: Boolean get() = (flags and Vcp.FLAG_END_OF_STREAM) != 0

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is VcpMessage) return false
        return type == other.type &&
            flags == other.flags &&
            sequence == other.sequence &&
            timestampUs == other.timestampUs &&
            payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int {
        var result = type
        result = 31 * result + flags
        result = 31 * result + sequence.hashCode()
        result = 31 * result + timestampUs.hashCode()
        result = 31 * result + payload.contentHashCode()
        return result
    }
}