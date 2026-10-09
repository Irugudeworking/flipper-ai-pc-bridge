package ai.bridge

/** Local policy for the deliberately small GUI/HID-only action surface. */
object SafetyPolicy {
    private val humanRequired = Regex("\\b(login|log in|sign in|password|passcode|authentication|authenticate|uac|permission prompt|verification code|security key)\\b", RegexOption.IGNORE_CASE)

    fun requiresHumanInteraction(screenDescription: String): Boolean = humanRequired.containsMatchIn(screenDescription)

    fun validate(actions: List<AgentAction>, maxActions: Int = 10) {
        ActionValidator.validate(actions, maxActions)
        require(actions.none { it is AgentAction.Text && it.value.contains('\u0000') }) { "Text contains a control character" }
        val heldKeys = HashSet<Int>()
        actions.forEach { action ->
            when(action) {
                is AgentAction.KeyDown -> require(heldKeys.add(action.usage)) { "Key is already held" }
                is AgentAction.KeyUp -> require(heldKeys.remove(action.usage)) { "Cannot release a key that is not held" }
                else -> Unit
            }
        }
        require(heldKeys.isEmpty()) { "Every KEY_DOWN must have a matching KEY_UP in the same step" }
    }
}
