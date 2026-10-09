package ai.bridge

/** Splits protobuf RPC streams to BLE writes and reassembles fragmented notifications. */
object BlePacketFragmenter {
    fun fragment(bytes: ByteArray, payloadLimit: Int): List<ByteArray> {
        require(payloadLimit > 0) { "BLE payload limit must be positive" }
        if(bytes.isEmpty()) return emptyList()
        return bytes.asList().chunked(payloadLimit).map { chunk -> chunk.toByteArray() }
    }
}

class RpcPacketAssembler(
    private val maxMessageBytes: Int = RpcWire.MAX_RPC_MESSAGE,
    private val maxBufferedBytes: Int = 4096,
) {
    private var pending = ByteArray(0)

    fun append(fragment: ByteArray): List<ByteArray> {
        require(pending.size + fragment.size <= maxBufferedBytes) { resetAndFail("RPC receive buffer exceeded limit") }
        pending += fragment
        val messages = ArrayList<ByteArray>()
        var offset = 0
        while(offset < pending.size) {
            val header = readLength(offset) ?: break
            val (length, prefixSize) = header
            require(length <= maxMessageBytes) { resetAndFail("RPC message exceeds transport bound") }
            val start = offset + prefixSize
            if(start + length > pending.size) break
            messages += pending.copyOfRange(start, start + length)
            offset = start + length
        }
        if(offset > 0) pending = pending.copyOfRange(offset, pending.size)
        return messages
    }

    fun reset() { pending = ByteArray(0) }

    private fun readLength(start: Int): Pair<Int, Int>? {
        var value = 0L
        var shift = 0
        var offset = start
        while(offset < pending.size && shift < 35) {
            val byte = pending[offset++].toInt() and 0xFF
            value = value or ((byte and 0x7F).toLong() shl shift)
            if(byte and 0x80 == 0) {
                require(value <= maxMessageBytes) { resetAndFail("RPC message exceeds transport bound") }
                return value.toInt() to (offset - start)
            }
            shift += 7
            if(offset - start >= 5) resetAndFail("Malformed RPC varint length")
        }
        return null
    }

    private fun resetAndFail(message: String): Nothing {
        reset()
        throw IllegalArgumentException(message)
    }
}
