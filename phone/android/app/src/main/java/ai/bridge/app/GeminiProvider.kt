package ai.bridge.app

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

/** Gemini GenerateContent over HTTPS; the API key is supplied only at request time. */
internal class GeminiProvider(private val apiKey: String, private val model: String) : AiProvider {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(75, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(90, TimeUnit.SECONDS)
        .build()

    override suspend fun getNextAction(request: AgentRequest): AgentDecision {
        require(apiKey.isNotBlank()) { "Enter a Gemini API key in Settings" }
        val selectedModel = model.trim()
        require(MODEL_NAME.matches(selectedModel)) { "Enter a valid Gemini model name in Settings" }

        val parts = JSONArray()
            .put(JSONObject().put("text", request.userPrompt()))
            .put(JSONObject().put("inlineData", JSONObject()
                .put("mimeType", request.screenshotMimeType)
                .put("data", Base64.encodeToString(request.screenshot, Base64.NO_WRAP))))
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", AgentSystemPrompt.VALUE))))
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", parts)))
            .put("generationConfig", JSONObject().put("responseMimeType", "application/json"))
        val httpRequest = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/$selectedModel:generateContent")
            .header("x-goog-api-key", apiKey)
            .header("x-goog-api-client", "flipper-ai-brain/1.0")
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody(JSON_MEDIA))
            .build()
        return AiDecisionParser.parse(responseContent(execute(httpRequest)))
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
                        val message = when(it.code) {
                            400 -> "Gemini rejected the request (400). Check the selected model."
                            401, 403 -> "Gemini API key or access rejected (${it.code}). Check Settings and Google AI Studio."
                            404 -> "Gemini model not found (404). Check the model in Settings."
                            429 -> "Gemini rate limit reached (429). Wait and try again."
                            in 500..599 -> "Gemini service temporarily unavailable (${it.code})."
                            else -> "Gemini request failed (${it.code})."
                        }
                        if(continuation.isActive) continuation.resumeWithException(IOException(message))
                    } else if(continuation.isActive) continuation.resume(text)
                }
            }
        })
    }

    private fun responseContent(text: String): String {
        val root = JSONObject(text)
        val candidate = root.optJSONArray("candidates")?.optJSONObject(0)
        val parts = candidate?.optJSONObject("content")?.optJSONArray("parts")
        val content = if(parts == null) "" else (0 until parts.length()).mapNotNull { index ->
            parts.optJSONObject(index)?.takeUnless { it.optBoolean("thought", false) }?.optString("text")
        }.joinToString("")
        if(content.isNotBlank()) return content
        val reason = candidate?.optString("finishReason")?.takeIf(String::isNotBlank)
            ?: root.optJSONObject("promptFeedback")?.optString("blockReason")?.takeIf(String::isNotBlank)
            ?: "empty response"
        throw IOException("Gemini returned no action ($reason)")
    }

    companion object {
        private val MODEL_NAME = Regex("[A-Za-z0-9._-]{1,100}")
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}
