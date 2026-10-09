package ai.bridge

import java.util.concurrent.atomic.AtomicBoolean

interface BridgeLink {
    fun send(frame: BridgeFrame)
    fun await(type: BridgeType, sequenceId: Long, timeoutMillis: Long): BridgeFrame?
    fun awaitScreenshot(requestSequence: Long, timeoutMillis: Long): ByteArray?
}

data class ControllerLimits(
    val maxIterations: Int = 20,
    val maxDurationMillis: Long = 600_000,
    val responseTimeoutMillis: Long = 10_000,
)

class BridgeController(
    private val link: BridgeLink,
    private val agent: AiAgent,
    private val limits: ControllerLimits = ControllerLimits(),
) {
    private val cancelled = AtomicBoolean(false)
    private val paused = AtomicBoolean(false)
    private var nextSequence = 100L

    fun cancel() {
        cancelled.set(true)
        link.send(BridgeFrame(BridgeType.CANCEL, nextSequence++))
    }

    fun emergencyStop() {
        cancelled.set(true)
        link.send(BridgeFrame(BridgeType.EMERGENCY_STOP, nextSequence++))
    }

    fun pause(value: Boolean) {
        paused.set(value)
    }

    fun run(task: String): List<ActionResult> {
        require(task.isNotBlank()) { "Task is empty" }
        cancelled.set(false)
        val startedAt = System.currentTimeMillis()
        val history = ArrayList<ActionResult>()
        var screenshot: ByteArray? = requestScreenshot()

        repeat(limits.maxIterations) {
            check(!cancelled.get()) { "Task cancelled" }
            if(System.currentTimeMillis() - startedAt > limits.maxDurationMillis) {
                emergencyStop()
                error("Task duration exceeded")
            }
            while(paused.get() && !cancelled.get()) Thread.sleep(50)
            check(!cancelled.get()) { "Task cancelled" }

            val response = agent.nextAction(task, screenshot, history.toList())
            require(response.actions.size <= ActionValidator.MAX_BATCH)
            val actionList = response.actions.filterNot { it == AgentAction.RequestScreenshot }
            if(actionList.isNotEmpty()) {
                val sequence = nextSequence++
                val payload = ActionValidator.encodeBatch(actionList)
                link.send(BridgeFrame(BridgeType.ACTION_BATCH, sequence, payload))
                val resultFrame = link.await(BridgeType.ACTION_RESULT, sequence, limits.responseTimeoutMillis)
                    ?: error("Flipper action response timed out")
                val result = parseResult(sequence, resultFrame.payload)
                history += result
                if(result.resultCode != SUCCESS) error("Flipper rejected the action batch (${result.resultCode})")
            }
            if(response.finished) return history
            if(response.requestScreenshotAfter || actionList.size != response.actions.size) {
                screenshot = requestScreenshot()
            }
        }
        emergencyStop()
        error("AI iteration limit exceeded")
    }

    private fun requestScreenshot(): ByteArray? {
        val sequence = nextSequence++
        link.send(BridgeFrame(BridgeType.REQUEST_SCREENSHOT, sequence))
        val response = link.await(BridgeType.ACTION_RESULT, sequence, limits.responseTimeoutMillis)
            ?: error("Screenshot response timed out")
        val result = parseResult(sequence, response.payload)
        if(result.resultCode == UNSUPPORTED) {
            throw UnsupportedOperationException("Screenshot requires the owner-started Windows CDC helper; see docs/SCREENSHOT_PIPELINE.md")
        }
        require(result.resultCode == SUCCESS) { "Screenshot request failed (${result.resultCode})" }
        return link.awaitScreenshot(sequence, SCREENSHOT_TOTAL_TIMEOUT_MILLIS)
            ?: error("Screenshot transfer timed out")
    }

    private fun parseResult(sequence: Long, payload: ByteArray): ActionResult {
        require(payload.size == 4) { "Malformed Flipper result" }
        val detail = (payload[2].toInt() and 0xFF) or ((payload[3].toInt() and 0xFF) shl 8)
        return ActionResult(sequence, payload[0].toInt() and 0xFF, payload[1].toInt() and 0xFF, detail)
    }

    companion object {
        private const val SCREENSHOT_TOTAL_TIMEOUT_MILLIS = 300_000L
        private const val SUCCESS = 0
        private const val UNSUPPORTED = 4
    }
}
