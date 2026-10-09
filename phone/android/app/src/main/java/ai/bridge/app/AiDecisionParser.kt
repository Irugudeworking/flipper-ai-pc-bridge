package ai.bridge.app

import ai.bridge.AgentAction
import ai.bridge.ActionValidator
import org.json.JSONArray
import org.json.JSONObject

/** Both AI backends must pass through the same action limits and validation. */
internal object AiDecisionParser {
    fun parse(content: String): AgentDecision = try {
        val data = JSONObject(content)
        val status = DecisionStatus.valueOf(data.getString("status").uppercase())
        val reason = data.optString("reason", "").take(500)
        val sourceActions = data.optJSONArray("actions") ?: JSONArray()
        if(sourceActions.length() > ActionValidator.MAX_BATCH) throw IllegalArgumentException("AI returned too many actions")
        val actions = (0 until sourceActions.length()).map { parseAction(sourceActions.getJSONObject(it)) }
        if(status == DecisionStatus.CONTINUE) {
            ActionValidator.validate(actions, ActionValidator.MAX_BATCH)
        } else {
            require(actions.isEmpty()) { "Non-continuing decisions must have no actions" }
        }
        AgentDecision(status, reason, actions, data.optBoolean("requestScreenshotAfter", false))
    } catch(error: Exception) {
        throw IllegalArgumentException("AI returned invalid structured actions: ${error.message ?: "invalid response"}", error)
    }

    private fun parseAction(item: JSONObject): AgentAction = when(item.getString("type").uppercase()) {
        "KEY_TAP" -> AgentAction.Key(keyUsage(item.getString("key")))
        "KEY_DOWN" -> AgentAction.KeyDown(keyUsage(item.getString("key")))
        "KEY_UP" -> AgentAction.KeyUp(keyUsage(item.getString("key")))
        "HOTKEY" -> {
            val modifiers = item.optJSONArray("modifiers") ?: JSONArray()
            var mask = 0
            for(index in 0 until modifiers.length()) mask = mask or when(modifiers.getString(index).uppercase()) {
                "CTRL", "CONTROL" -> 1
                "SHIFT" -> 2
                "ALT" -> 4
                "WIN", "GUI" -> 8
                else -> throw IllegalArgumentException("Unknown hotkey modifier")
            }
            AgentAction.Hotkey(mask, keyUsage(item.getString("key")))
        }
        "TEXT_INPUT" -> AgentAction.Text(item.getString("text"))
        "MOUSE_MOVE" -> AgentAction.MouseMove(item.getInt("dx"), item.getInt("dy"))
        "MOUSE_CLICK" -> AgentAction.MouseClick(when(item.getString("button").uppercase()) {
            "LEFT" -> 1; "RIGHT" -> 2; "MIDDLE" -> 4
            else -> throw IllegalArgumentException("Unknown mouse button")
        })
        "MOUSE_SCROLL" -> AgentAction.MouseScroll(item.getInt("delta"))
        "WAIT" -> AgentAction.Wait(item.getLong("milliseconds"))
        "REQUEST_SCREENSHOT" -> AgentAction.RequestScreenshot
        "FINISH" -> AgentAction.Finish
        else -> throw IllegalArgumentException("Unknown action type")
    }

    private fun keyUsage(value: String): Int {
        val name = value.uppercase()
        return namedKeys[name] ?: when {
            name.length == 1 && name[0] in 'A'..'Z' -> name[0].code - 'A'.code + 4
            name.length == 1 && name[0] in '1'..'9' -> name[0].code - '1'.code + 30
            name == "0" -> 39
            else -> throw IllegalArgumentException("Unsupported key name")
        }
    }

    private val namedKeys = mapOf(
        "ENTER" to 40, "ESC" to 41, "ESCAPE" to 41, "BACKSPACE" to 42, "TAB" to 43, "SPACE" to 44,
        "MINUS" to 45, "EQUAL" to 46, "LEFT_BRACKET" to 47, "RIGHT_BRACKET" to 48, "BACKSLASH" to 49,
        "SEMICOLON" to 51, "APOSTROPHE" to 52, "GRAVE" to 53, "COMMA" to 54, "PERIOD" to 55, "SLASH" to 56,
        "CAPS_LOCK" to 57, "F1" to 58, "F2" to 59, "F3" to 60, "F4" to 61, "F5" to 62, "F6" to 63,
        "F7" to 64, "F8" to 65, "F9" to 66, "F10" to 67, "F11" to 68, "F12" to 69,
        "PRINT_SCREEN" to 70, "SCROLL_LOCK" to 71, "PAUSE" to 72, "INSERT" to 73, "HOME" to 74,
        "PAGE_UP" to 75, "DELETE" to 76, "END" to 77, "PAGE_DOWN" to 78, "RIGHT" to 79,
        "LEFT" to 80, "DOWN" to 81, "UP" to 82
    )
}
