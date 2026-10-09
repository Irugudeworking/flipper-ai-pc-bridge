package ai.bridge.app

import ai.bridge.AgentAction
import android.util.Base64
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal data class DeviceCapabilities(
    val protocolVersion: Int = 0,
    val flags: Int = 0,
    val maxPacket: Int = 0,
    val maxBatch: Int = 0,
    val maxText: Int = 0,
    val maxFileSize: Long = 0,
) {
    val keyboard get() = flags and 1 != 0
    val mouse get() = flags and 2 != 0
    val screenshotHelper get() = flags and 64 != 0
    val connected get() = protocolVersion > 0
    fun describe() = "Protocol v$protocolVersion · keyboard ${if(keyboard) "yes" else "no"} · mouse ${if(mouse) "yes" else "no"} · Flipper screenshot ${if(screenshotHelper) "helper" else "unavailable"}"
}

internal enum class DecisionStatus { CONTINUE, NEED_SCREENSHOT, FINISHED, FAILED, NEED_USER }
internal data class AgentDecision(
    val status: DecisionStatus,
    val reason: String,
    val actions: List<AgentAction>,
    val requestScreenshotAfter: Boolean,
)
internal data class AgentRequest(
    val task: String,
    val screenshot: ByteArray,
    val screenshotMimeType: String,
    val screenshotQuality: String,
    val history: List<String>,
    val capabilities: DeviceCapabilities,
    val currentState: String,
)

internal interface AiProvider {
    suspend fun getNextAction(request: AgentRequest): AgentDecision
}

internal fun AgentRequest.userPrompt(): String = buildString {
    append("Goal: ").append(task)
    append("\nCurrent state: ").append(currentState)
    append("\nDevice: ").append(capabilities.describe())
    append("\nRecent action history:\n")
    history.takeLast(12).forEach { append("- ").append(it.take(180)).append('\n') }
    append("\nAnalyze only this current frame and return the next safe step.")
}

internal class OpenAiProvider(private val apiKey: String, private val model: String) : AiProvider {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(75, TimeUnit.SECONDS)
        .build()

    override suspend fun getNextAction(request: AgentRequest): AgentDecision {
        require(apiKey.startsWith("sk-") && apiKey.length >= 20) { "Enter a valid OpenAI API key in Settings" }
        val userText = request.userPrompt()
        val userParts = JSONArray()
            .put(JSONObject().put("type", "text").put("text", userText))
            .put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put(
                "url", "data:${request.screenshotMimeType};base64," + Base64.encodeToString(request.screenshot, Base64.NO_WRAP)
            ).put("detail", request.screenshotQuality.lowercase())))
        val body = JSONObject()
            .put("model", model.trim())
            .put("temperature", 0)
            .put("max_tokens", 700)
            .put("response_format", JSONObject().put("type", "json_object"))
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", AgentSystemPrompt.VALUE))
                .put(JSONObject().put("role", "user").put("content", userParts)))
        val httpRequest = Request.Builder()
            .url("https://api.openai.com/v1/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody(JSON_MEDIA))
            .build()
        val responseText = execute(httpRequest)
        val content = JSONObject(responseText).getJSONArray("choices").getJSONObject(0)
            .getJSONObject("message").getString("content")
        return AiDecisionParser.parse(content)
    }

    private suspend fun execute(request: Request): String = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if(continuation.isActive) continuation.resumeWithException(e)
            }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val text = it.body?.string().orEmpty()
                    if(!it.isSuccessful) {
                        val detail = runCatching { JSONObject(text).optJSONObject("error")?.optString("message") }.getOrNull()
                        val message = when(it.code) {
                            401 -> "API key rejected (401). Check Settings."
                            403 -> "API access denied (403). Check account access and model."
                            429 -> "AI rate limit reached (429). Wait and try again."
                            in 500..599 -> "AI service temporarily unavailable (${it.code})."
                            else -> "AI request failed (${it.code})${detail?.takeIf(String::isNotBlank)?.let { d -> ": $d" }.orEmpty()}"
                        }
                        if(continuation.isActive) continuation.resumeWithException(IOException(message))
                    } else if(continuation.isActive) continuation.resume(text)
                }
            }
        })
    }

    companion object {
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}
