package ai.bridge

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

enum class BridgeType(val id: Int) {
    HELLO(1), HELLO_ACK(2), GET_CAPABILITIES(3), CAPABILITIES(4),
    KEY_DOWN(5), KEY_UP(6), KEY_TAP(7), TEXT_INPUT(8), MOUSE_MOVE(9),
    MOUSE_CLICK(10), MOUSE_SCROLL(11), HOTKEY(12), WAIT(13),
    REQUEST_SCREENSHOT(14), SCREENSHOT_BEGIN(15), SCREENSHOT_CHUNK(16),
    SCREENSHOT_END(17), FILE_ACK(18), FILE_NACK(19), ACTION_RESULT(20),
    PING(21), PONG(22), CANCEL(23), EMERGENCY_STOP(24), ERROR(25), ACTION_BATCH(26);

    companion object {
        fun fromId(id: Int): BridgeType = entries.firstOrNull { it.id == id }
            ?: throw IllegalArgumentException("Unknown bridge message type $id")
    }
}

data class BridgeFrame(
    val type: BridgeType,
    val sequenceId: Long,
    val payload: ByteArray = byteArrayOf(),
    val flags: Int = 0,
    val version: Int = BridgeProtocol.VERSION,
)

object BridgeProtocol {
    const val VERSION = 1
    const val HEADER_SIZE = 16
    const val MAX_PAYLOAD = 240
    const val MAX_FRAME = HEADER_SIZE + MAX_PAYLOAD

    fun encode(frame: BridgeFrame): ByteArray {
        require(frame.version == VERSION) { "Unsupported protocol version ${frame.version}" }
        require(frame.sequenceId in 0..0xFFFF_FFFFL)
        require(frame.flags in 0..0xFFFF)
        require(frame.payload.size <= MAX_PAYLOAD)
        val output = ByteBuffer.allocate(HEADER_SIZE + frame.payload.size).order(ByteOrder.LITTLE_ENDIAN)
        output.put(0x46.toByte()).put(0x42.toByte())
        output.put(frame.version.toByte()).put(frame.type.id.toByte())
        output.putInt(frame.sequenceId.toInt())
        output.putShort(frame.payload.size.toShort()).putShort(frame.flags.toShort())
        output.putInt(0)
        output.put(frame.payload)
        val crc = CRC32()
        crc.update(output.array(), 2, 10)
        crc.update(frame.payload)
        output.putInt(12, crc.value.toInt())
        return output.array()
    }

    fun decode(bytes: ByteArray): BridgeFrame {
        require(bytes.size >= HEADER_SIZE) { "Truncated bridge frame" }
        val input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require((input.get().toInt() and 0xFF) == 0x46 && (input.get().toInt() and 0xFF) == 0x42) { "Invalid magic" }
        val version = input.get().toInt() and 0xFF
        require(version == VERSION) { "Unsupported protocol version $version" }
        val type = BridgeType.fromId(input.get().toInt() and 0xFF)
        val sequence = input.int.toLong() and 0xFFFF_FFFFL
        val payloadSize = input.short.toInt() and 0xFFFF
        val flags = input.short.toInt() and 0xFFFF
        val expectedCrc = input.int.toLong() and 0xFFFF_FFFFL
        require(payloadSize <= MAX_PAYLOAD && bytes.size == HEADER_SIZE + payloadSize) { "Invalid frame length" }
        val payload = ByteArray(payloadSize)
        input.get(payload)
        val crc = CRC32()
        crc.update(bytes, 2, 10)
        crc.update(payload)
        require(crc.value == expectedCrc) { "Invalid frame CRC" }
        return BridgeFrame(type, sequence, payload, flags, version)
    }
}
