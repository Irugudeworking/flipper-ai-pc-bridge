package ai.bridge

import java.nio.ByteBuffer
import java.nio.ByteOrder

sealed interface AgentAction {
    data class Key(val usage: Int) : AgentAction
    data class KeyDown(val usage: Int) : AgentAction
    data class KeyUp(val usage: Int) : AgentAction
    data class Hotkey(val modifiers: Int, val usage: Int) : AgentAction
    data class Text(val value: String) : AgentAction
    data class MouseMove(val dx: Int, val dy: Int) : AgentAction
    data class MouseClick(val button: Int) : AgentAction
    data class MouseScroll(val delta: Int) : AgentAction
    data class Wait(val millis: Long) : AgentAction
    data object RequestScreenshot : AgentAction
    data object Finish : AgentAction
}

data class AgentResponse(
    val finished: Boolean,
    val reason: String,
    val actions: List<AgentAction>,
    val requestScreenshotAfter: Boolean,
)

data class ActionResult(val sequenceId: Long, val resultCode: Int, val completedActions: Int, val detail: Int)

object ActionValidator {
    const val MAX_BATCH = 16
    const val MAX_TEXT = 96
    const val MAX_ACTION_MS = 5_000L
    const val MAX_BATCH_MS = 30_000L

    fun encodeBatch(actions: List<AgentAction>): ByteArray {
        require(actions.isNotEmpty() && actions.size <= MAX_BATCH) { "Batch must contain 1..$MAX_BATCH actions" }
        val encoded = actions.map(::encodeAction)
        require(actions.filterIsInstance<AgentAction.Wait>().sumOf { it.millis } <= MAX_BATCH_MS) {
            "Batch wait time exceeds limit"
        }
        val size = 1 + encoded.sumOf { 3 + it.second.size }
        require(size <= BridgeProtocol.MAX_PAYLOAD)
        val output = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
        output.put(actions.size.toByte())
        encoded.forEach { (type, payload) ->
            output.put(type.toByte()).putShort(payload.size.toShort()).put(payload)
        }
        return output.array()
    }

    private fun encodeAction(action: AgentAction): Pair<Int, ByteArray> = when (action) {
        is AgentAction.Key -> {
            require(action.usage in 1..0xE7)
            BridgeActionType.KEY_TAP to u16(action.usage)
        }
        is AgentAction.KeyDown, is AgentAction.KeyUp -> error("KEY_DOWN/KEY_UP are sent as individual bridge messages")
        is AgentAction.Hotkey -> {
            require(action.modifiers in 0..0x0F && action.usage in 1..0xE7)
            BridgeActionType.HOTKEY to ByteBuffer.allocate(3).order(ByteOrder.LITTLE_ENDIAN)
                .put(action.modifiers.toByte()).putShort(action.usage.toShort()).array()
        }
        is AgentAction.Text -> {
            val data = action.value.toByteArray(Charsets.US_ASCII)
            require(action.value.all { it.code in 0x20..0x7E } && data.size <= MAX_TEXT)
            BridgeActionType.TEXT to data
        }
        is AgentAction.MouseMove -> {
            require(action.dx in -2048..2048 && action.dy in -2048..2048)
            BridgeActionType.MOUSE_MOVE to ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
                .putShort(action.dx.toShort()).putShort(action.dy.toShort()).array()
        }
        is AgentAction.MouseClick -> {
            require(action.button == 1 || action.button == 2 || action.button == 4)
            BridgeActionType.MOUSE_CLICK to byteArrayOf(action.button.toByte())
        }
        is AgentAction.MouseScroll -> {
            require(action.delta in -120..120)
            BridgeActionType.MOUSE_SCROLL to u16(action.delta)
        }
        is AgentAction.Wait -> {
            require(action.millis in 0..MAX_ACTION_MS)
            BridgeActionType.WAIT to ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(action.millis.toInt()).array()
        }
        AgentAction.RequestScreenshot -> BridgeActionType.REQUEST_SCREENSHOT to byteArrayOf()
        AgentAction.Finish -> BridgeActionType.FINISH to byteArrayOf()
    }

    fun validate(actions: List<AgentAction>, maxActions: Int = MAX_BATCH) {
        require(actions.isNotEmpty() && actions.size <= maxActions) { "Action count must be within 1..$maxActions" }
        require(actions.filterIsInstance<AgentAction.Wait>().sumOf { it.millis } <= MAX_BATCH_MS) {
            "Batch wait time exceeds limit"
        }
        actions.forEach { action ->
            when(action) {
                is AgentAction.Key -> require(action.usage in 1..0xE7)
                is AgentAction.KeyDown -> require(action.usage in 1..0xE7)
                is AgentAction.KeyUp -> require(action.usage in 1..0xE7)
                is AgentAction.Hotkey -> require(action.modifiers in 0..0x0F && action.usage in 1..0xE7)
                is AgentAction.Text -> require(action.value.length <= MAX_TEXT && action.value.all { it.code in 0x20..0x7E })
                is AgentAction.MouseMove -> require(action.dx in -2048..2048 && action.dy in -2048..2048)
                is AgentAction.MouseClick -> require(action.button == 1 || action.button == 2 || action.button == 4)
                is AgentAction.MouseScroll -> require(action.delta in -120..120)
                is AgentAction.Wait -> require(action.millis in 0..MAX_ACTION_MS)
                AgentAction.RequestScreenshot, AgentAction.Finish -> Unit
            }
        }
    }

    private fun u16(value: Int): ByteArray = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN)
        .putShort(value.toShort()).array()
}

private object BridgeActionType {
    const val KEY_TAP = 1
    const val HOTKEY = 2
    const val TEXT = 3
    const val MOUSE_MOVE = 4
    const val MOUSE_CLICK = 5
    const val MOUSE_SCROLL = 6
    const val WAIT = 7
    const val REQUEST_SCREENSHOT = 8
    const val FINISH = 9
}
