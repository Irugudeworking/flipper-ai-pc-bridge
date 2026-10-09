package ai.bridge

/** Implement this interface with an on-device or network AI provider. */
fun interface AiAgent {
    fun nextAction(task: String, screenshot: ByteArray?, history: List<ActionResult>): AgentResponse
}
