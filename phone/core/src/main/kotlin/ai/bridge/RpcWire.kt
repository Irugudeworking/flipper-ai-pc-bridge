package ai.bridge

import java.io.ByteArrayOutputStream

/** Minimal, bounded encoder/decoder for the official length-delimited Flipper RPC envelope. */
object RpcWire {
    const val RPC_START_APP = 16
    const val RPC_APP_STATE = 58
    const val RPC_APP_DATA_EXCHANGE = 65
    const val MAX_RPC_MESSAGE = 486

    fun encodeStartApp(commandId: Int, appId: String, args: String = "RPC"): ByteArray {
        val body = lengthDelimited(1, appId.toByteArray(Charsets.UTF_8)) +
            lengthDelimited(2, args.toByteArray(Charsets.UTF_8))
        val request = lengthDelimited(16, body)
        return delimited(mainEnvelope(commandId, request))
    }

    fun encodeStorageStat(commandId: Int, path: String): ByteArray {
        val body = lengthDelimited(1, path.toByteArray(Charsets.UTF_8))
        return delimited(mainEnvelope(commandId, lengthDelimited(24, body)))
    }

    fun encodeStorageDelete(commandId: Int, path: String): ByteArray {
        val body = lengthDelimited(1, path.toByteArray(Charsets.UTF_8))
        return delimited(mainEnvelope(commandId, lengthDelimited(12, body)))
    }

    fun encodeDataExchange(commandId: Int, data: ByteArray): ByteArray {
        require(data.size <= BridgeProtocol.MAX_FRAME)
        val inner = lengthDelimited(1, data) // DataExchangeRequest.data = 1
        return delimited(mainEnvelope(commandId, lengthDelimited(RPC_APP_DATA_EXCHANGE, inner)))
    }

    fun parseDelimited(buffer: ByteArray): List<ByteArray> {
        val result = ArrayList<ByteArray>()
        var offset = 0
        while (offset < buffer.size) {
            val (length, prefix) = readVarint(buffer, offset)
            require(length in 0L..MAX_RPC_MESSAGE.toLong()) { "RPC message exceeds transport bound" }
            require(offset.toLong() + prefix + length <= buffer.size.toLong()) { "Incomplete RPC message" }
            result += buffer.copyOfRange(offset + prefix, offset + prefix + length.toInt())
            offset += prefix + length.toInt()
        }
        return result
    }

    fun parseFields(message: ByteArray): List<Pair<Int, ByteArray>> {
        val fields = ArrayList<Pair<Int, ByteArray>>()
        var offset = 0
        while (offset < message.size) {
            val (tag, tagBytes) = readVarint(message, offset)
            offset += tagBytes
            val field = (tag ushr 3).toInt()
            when ((tag and 7).toInt()) {
                0 -> {
                    val (value, consumed) = readVarint(message, offset)
                    fields += field to varint(value)
                    offset += consumed
                }
                2 -> {
                    val (length, consumed) = readVarint(message, offset)
                    offset += consumed
                    require(length >= 0 && length <= (message.size - offset).toLong()) { "Truncated protobuf field" }
                    fields += field to message.copyOfRange(offset, offset + length.toInt())
                    offset += length.toInt()
                }
                else -> throw IllegalArgumentException("Unsupported protobuf wire type")
            }
        }
        return fields
    }

    fun decodeVarint(value: ByteArray): Long {
        val (result, consumed) = readVarint(value, 0)
        require(consumed == value.size) { "Trailing bytes after protobuf varint" }
        return result
    }

    private fun mainEnvelope(commandId: Int, content: ByteArray): ByteArray =
        varintField(1, commandId.toLong()) + content

    private fun lengthDelimited(field: Int, data: ByteArray): ByteArray =
        varint(((field shl 3) or 2).toLong()) + varint(data.size.toLong()) + data

    private fun varintField(field: Int, value: Long): ByteArray =
        varint((field shl 3).toLong()) + varint(value)

    private fun delimited(message: ByteArray): ByteArray = varint(message.size.toLong()) + message

    private fun varint(value: Long): ByteArray {
        var remaining = value
        val out = ByteArrayOutputStream(5)
        while (remaining >= 0x80L) {
            out.write(((remaining and 0x7F) or 0x80).toInt())
            remaining = remaining ushr 7
        }
        out.write(remaining.toInt())
        return out.toByteArray()
    }

    private fun readVarint(data: ByteArray, start: Int): Pair<Long, Int> {
        var value = 0L
        var shift = 0
        var offset = start
        while (offset < data.size && shift < 64) {
            val next = data[offset++].toInt() and 0xFF
            value = value or ((next and 0x7F).toLong() shl shift)
            if (next and 0x80 == 0) return value to (offset - start)
            shift += 7
        }
        throw IllegalArgumentException("Malformed protobuf varint")
    }
}
