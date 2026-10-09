package ai.bridge

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class ScreenshotTransferTest {
    @Test
    fun reassemblesOrderedChunksAndAcknowledgesRetries() {
        val data = byteArrayOf(1, 2, 3, 4)
        val crc = CRC32().apply { update(data) }.value.toInt()
        val receiver = ScreenshotReceiver()
        val begin = ByteBuffer.allocate(13).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(9).putInt(data.size).put(1).putInt(crc).array()
        assertEquals(TransferReply.Ack(9, 0), receiver.accept(BridgeFrame(BridgeType.SCREENSHOT_BEGIN, 1, begin)))
        val chunk = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(9).putInt(0).put(data).array()
        assertEquals(TransferReply.Ack(9, 4), receiver.accept(BridgeFrame(BridgeType.SCREENSHOT_CHUNK, 2, chunk)))
        assertEquals(TransferReply.Ack(9, 4), receiver.accept(BridgeFrame(BridgeType.SCREENSHOT_CHUNK, 3, chunk)))
        val end = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putInt(9).putInt(crc).array()
        val result = receiver.accept(BridgeFrame(BridgeType.SCREENSHOT_END, 4, end)) as TransferReply.Complete
        assertContentEquals(data, result.bytes)
        assertEquals(TransferReply.Ack(9, 4), receiver.accept(BridgeFrame(BridgeType.SCREENSHOT_END, 5, end)))
    }

    @Test
    fun rejectsOutOfOrderChunk() {
        val receiver = ScreenshotReceiver()
        val begin = ByteBuffer.allocate(13).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(9).putInt(4).put(1).putInt(0).array()
        receiver.accept(BridgeFrame(BridgeType.SCREENSHOT_BEGIN, 1, begin))
        val chunk = ByteBuffer.allocate(9).order(ByteOrder.LITTLE_ENDIAN).putInt(9).putInt(1).put(1).array()
        assertEquals(TransferReply.Nack(9, 0), receiver.accept(BridgeFrame(BridgeType.SCREENSHOT_CHUNK, 2, chunk)))
    }

    @Test
    fun detectsMissingChunkAndRejectsCorruptChecksum() {
        val data = byteArrayOf(1, 2, 3, 4)
        val crc = CRC32().apply { update(data) }.value.toInt()
        val receiver = ScreenshotReceiver()
        val begin = ByteBuffer.allocate(13).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(11).putInt(data.size).put(1).putInt(crc).array()
        receiver.accept(BridgeFrame(BridgeType.SCREENSHOT_BEGIN, 1, begin))
        val missingFirst = ByteBuffer.allocate(10).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(11).putInt(2).put(3).put(4).array()
        assertEquals(TransferReply.Nack(11, 0), receiver.accept(BridgeFrame(BridgeType.SCREENSHOT_CHUNK, 2, missingFirst)))
        val whole = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(11).putInt(0).put(data).array()
        assertEquals(TransferReply.Ack(11, 4), receiver.accept(BridgeFrame(BridgeType.SCREENSHOT_CHUNK, 3, whole)))
        val badEnd = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putInt(11).putInt(crc xor 1).array()
        assertEquals(TransferReply.Nack(11, 4), receiver.accept(BridgeFrame(BridgeType.SCREENSHOT_END, 4, badEnd)))
    }
}
