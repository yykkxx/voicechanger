package com.voicechanger.app.core.protocol

/**
 * VCP/1 增量解析器：必须同时支持 TCP 的拆包与粘包。
 *
 * usage:
 * ```
 * val parser = VcpParser()
 * parser.feed(bytes, 0, n)  // 返回 error 时断开连接
 * while (true) { val msg = parser.next() ?: break; handle(msg) }
 * ```
 */
class VcpParser(
    private val onProtocolError: ((String) -> Unit)? = null,
) {
    sealed interface ParseResult {
        data object Ok : ParseResult
        data class Error(val reason: String) : ParseResult
    }

    private var buffer = ByteArray(8 * 1024)
    private var length = 0

    companion object {
        /** 单条消息最大总长 = header + max payload。 */
        const val MAX_MESSAGE = Vcp.HEADER_SIZE + Vcp.MAX_PAYLOAD
    }

    fun feed(src: ByteArray, offset: Int = 0, count: Int = src.size - offset): ParseResult {
        if (count <= 0) return ParseResult.Ok
        ensureCapacity(length + count)
        System.arraycopy(src, offset, buffer, length, count)
        length += count
        return ParseResult.Ok
    }

    /** 尝试取出一条完整消息；不足一条时返回 null。 */
    fun next(): VcpMessage? {
        if (length < Vcp.HEADER_SIZE) return null
        val header = VcpCodec.readHeader(buffer, 0)
            ?: return error("bad magic")
        if (header.version != Vcp.VERSION) {
            return error("unsupported version ${header.version}")
        }
        if (header.payloadLength < 0 || header.payloadLength > Vcp.MAX_PAYLOAD) {
            return error("payloadLength out of range ${header.payloadLength}")
        }
        val total = Vcp.HEADER_SIZE + header.payloadLength.toInt()
        if (total > MAX_MESSAGE) return error("message too large")
        if (length < total) return null

        val payload = ByteArray(header.payloadLength.toInt())
        System.arraycopy(buffer, Vcp.HEADER_SIZE, payload, 0, payload.size)
        shift(total)
        return VcpMessage(
            type = header.type,
            flags = header.flags,
            sequence = header.sequence,
            timestampUs = header.timestampUs,
            payload = payload,
        )
    }

    fun reset() {
        length = 0
    }

    private fun shift(n: Int) {
        if (n <= 0) return
        val remain = length - n
        if (remain > 0) {
            System.arraycopy(buffer, n, buffer, 0, remain)
        }
        length = remain
    }

    private fun error(reason: String): VcpMessage? {
        reset()
        onProtocolError?.invoke(reason)
        return null
    }

    private fun ensureCapacity(required: Int) {
        if (required <= buffer.size) return
        var newSize = buffer.size
        while (newSize < required) newSize = newSize shl 1
        if (newSize > MAX_MESSAGE * 2 - Vcp.HEADER_SIZE) {
            newSize = MAX_MESSAGE * 2 - Vcp.HEADER_SIZE
        }
        buffer = buffer.copyOf(newSize)
    }
}