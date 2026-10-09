package ai.bridge

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

sealed interface TransferReply {
    data class Ack(val fileId: Long, val nextOffset: Long) : TransferReply
    data class Nack(val fileId: Long, val expectedOffset: Long) : TransferReply
    data class Complete(val fileId: Long, val mime: Int, val bytes: ByteArray) : TransferReply
    data class Failed(val reason: String) : TransferReply
}

/** Bounded phone-side reassembly. The Flipper only forwards chunks and never decodes an image. */
class ScreenshotReceiver(private val maxSize: Int = MAX_FILE_SIZE) {
    private var fileId = 0L
    private var expectedSize = 0
    private var expectedCrc = 0L
    private var nextOffset = 0
    private var mime = 0
    private var bytes: ByteArray? = null
    private var completedFileId = -1L
    private var completedSize = -1
    private var completedCrc = -1L

    fun progressPercent(): Int = if(expectedSize <= 0) 0 else
        ((nextOffset.toLong() * 100L) / expectedSize).toInt().coerceIn(0, 100)

    fun resetTransfer() {
        reset()
        completedFileId = -1L
        completedSize = -1
        completedCrc = -1L
    }

    fun accept(frame: BridgeFrame): TransferReply = try {
        when(frame.type) {
            BridgeType.SCREENSHOT_BEGIN -> begin(frame.payload)
            BridgeType.SCREENSHOT_CHUNK -> chunk(frame.payload)
            BridgeType.SCREENSHOT_END -> end(frame.payload)
            else -> TransferReply.Failed("Not a file-transfer message")
        }
    } catch(error: Exception) {
        TransferReply.Failed(error.message ?: "Invalid transfer packet")
    }

    private fun begin(payload: ByteArray): TransferReply {
        if(payload.size != 13) return TransferReply.Failed("Invalid screenshot header")
        val input = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        fileId = input.int.toLong() and 0xFFFF_FFFFL
        expectedSize = input.int
        mime = input.get().toInt() and 0xFF
        expectedCrc = input.int.toLong() and 0xFFFF_FFFFL
        if(expectedSize !in 1..maxSize || mime != PNG_MIME) {
            reset()
            return TransferReply.Failed("Unsupported screenshot size or MIME")
        }
        bytes = ByteArray(expectedSize)
        nextOffset = 0
        return TransferReply.Ack(fileId, 0)
    }

    private fun chunk(payload: ByteArray): TransferReply {
        val target = bytes ?: return TransferReply.Failed("No active screenshot")
        if(payload.size < 9 || payload.size > 8 + MAX_CHUNK_SIZE) {
            return TransferReply.Nack(fileId, nextOffset.toLong())
        }
        val input = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        val incomingId = input.int.toLong() and 0xFFFF_FFFFL
        val offset = input.int.toLong() and 0xFFFF_FFFFL
        val dataSize = payload.size - 8
        if(incomingId != fileId) return TransferReply.Nack(fileId, nextOffset.toLong())
        if(offset < nextOffset) return TransferReply.Ack(fileId, nextOffset.toLong()) // sender retry
        if(offset != nextOffset.toLong() || offset + dataSize > expectedSize) {
            return TransferReply.Nack(fileId, nextOffset.toLong())
        }
        input.get(target, nextOffset, dataSize)
        nextOffset += dataSize
        return TransferReply.Ack(fileId, nextOffset.toLong())
    }

    private fun end(payload: ByteArray): TransferReply {
        if(payload.size != 8) return TransferReply.Nack(fileId, nextOffset.toLong())
        val input = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        val incomingId = input.int.toLong() and 0xFFFF_FFFFL
        val checksum = input.int.toLong() and 0xFFFF_FFFFL
        val target = bytes ?: return if(incomingId == completedFileId && checksum == completedCrc) {
            TransferReply.Ack(completedFileId, completedSize.toLong())
        } else {
            TransferReply.Failed("No active screenshot")
        }
        if(incomingId != fileId || nextOffset != expectedSize || checksum != expectedCrc) {
            return TransferReply.Nack(fileId, nextOffset.toLong())
        }
        val crc = CRC32().apply { update(target) }.value
        if(crc != expectedCrc) {
            reset()
            return TransferReply.Failed("Screenshot checksum mismatch")
        }
        val result = TransferReply.Complete(fileId, mime, target)
        completedFileId = fileId
        completedSize = expectedSize
        completedCrc = expectedCrc
        reset()
        return result
    }

    private fun reset() {
        bytes = null
        fileId = 0
        expectedSize = 0
        expectedCrc = 0
        nextOffset = 0
        mime = 0
    }

    companion object {
        const val MAX_FILE_SIZE = 2 * 1024 * 1024
        const val MAX_CHUNK_SIZE = 200
        const val PNG_MIME = 1
    }
}
