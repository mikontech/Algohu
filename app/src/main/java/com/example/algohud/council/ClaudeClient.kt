package com.example.algohud.council

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
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

/** Klient Claude, Gemini i Groq. Endpoint wybierany wyłącznie z zamkniętej listy. */
class ClaudeClient(
    private val apiKey: () -> String?,
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS).callTimeout(25, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false).followRedirects(false).build(),
    private val providerKey: (AiProvider) -> String? = { apiKey() },
    private val limits: AiLimits? = null
) {
    class ApiException(message: String) : IOException(message)

    suspend fun askJson(model: String, system: String, userText: String,
                        imageJpegBase64: String? = null, maxTokens: Int = 700): JSONObject {
        val provider = AiSettings.provider(model)
        val key = providerKey(provider)?.takeIf { it.isNotBlank() }
            ?: throw ApiException("brak klucza API ${provider.name}")
        val req = request(model, key, system, userText, imageJpegBase64, maxTokens)
        limits?.reserveRequest(provider)?.let { throw ApiException(it) }
        return withContext(Dispatchers.IO) { http.newCall(req).await(provider) }
    }

    private suspend fun Call.await(provider: AiProvider): JSONObject = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(ApiException("${provider.name}: błąd połączenia"))
            }
            override fun onResponse(call: Call, response: Response) {
                response.use { r ->
                    if (!cont.isActive) return
                    val result = runCatching {
                        if (!r.isSuccessful) {
                            if (r.code == 429) limits?.backoff(provider, r.header("Retry-After")?.toLongOrNull())
                            // Treść błędu dostawcy może zawierać fragmenty danych lub klucz. Nie logujemy jej.
                            throw ApiException("${provider.name}: HTTP ${r.code}" + if (r.code == 429) " – przerwa minimum 15 min" else "")
                        }
                        parse(provider, r.body?.string().orEmpty())
                    }
                    if (cont.isActive) result.fold({ cont.resume(it) }, {
                        cont.resumeWithException(if (it is ApiException) it else ApiException("${provider.name}: nieprawidłowa odpowiedź JSON"))
                    })
                }
            }
        })
    }

    companion object {
        fun configured(prefs: android.content.SharedPreferences) = ClaudeClient(
            { AiSettings.key(prefs, AiProvider.CLAUDE) },
            providerKey = { AiSettings.key(prefs, it) }, limits = AiLimits(prefs))

        internal fun request(route: String, key: String, system: String, text: String, image: String?, tokens: Int): Request {
            val provider = AiSettings.provider(route)
            val model = route.substringAfter(':')
            require(model.matches(Regex("[A-Za-z0-9._/-]+"))) { "Nieprawidłowy identyfikator modelu" }
            val request = Request.Builder()
            val body = when (provider) {
                AiProvider.CLAUDE -> {
                    request.url("https://api.anthropic.com/v1/messages").header("x-api-key", key).header("anthropic-version", "2023-06-01")
                    val parts = JSONArray()
                    if (image != null) parts.put(JSONObject().put("type", "image").put("source", JSONObject()
                        .put("type", "base64").put("media_type", "image/jpeg").put("data", image)))
                    parts.put(JSONObject().put("type", "text").put("text", text))
                    JSONObject().put("model", model).put("max_tokens", tokens).put("temperature", 0.2).put("system", system)
                        .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", parts)))
                }
                AiProvider.GEMINI -> {
                    require(!model.contains('/')) { "Wpisz nazwę modelu Gemini bez ścieżki" }
                    request.url("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent").header("x-goog-api-key", key)
                    val parts = JSONArray().put(JSONObject().put("text", text))
                    if (image != null) parts.put(JSONObject().put("inlineData", JSONObject().put("mimeType", "image/jpeg").put("data", image)))
                    JSONObject().put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
                        .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", parts)))
                        .put("generationConfig", JSONObject().put("responseMimeType", "application/json")
                            .put("maxOutputTokens", maxOf(tokens, 2048)).put("temperature", 0.2))
                }
                AiProvider.GROQ -> {
                    request.url("https://api.groq.com/openai/v1/chat/completions").header("Authorization", "Bearer $key")
                    val parts = JSONArray().put(JSONObject().put("type", "text").put("text", text))
                    if (image != null) parts.put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$image")))
                    JSONObject().put("model", model).put("max_completion_tokens", maxOf(tokens, 2048)).put("temperature", 0.2)
                        .put("response_format", JSONObject().put("type", "json_object"))
                        .put("messages", JSONArray().put(JSONObject().put("role", "system").put("content", system))
                            .put(JSONObject().put("role", "user").put("content", parts)))
                }
            }
            return request.post(body.toString().toRequestBody("application/json".toMediaType())).build()
        }

        internal fun parse(provider: AiProvider, raw: String): JSONObject {
            val root = JSONObject(raw)
            val text = when (provider) {
                AiProvider.CLAUDE -> {
                    if (root.optString("stop_reason") != "end_turn") throw ApiException("Claude: niepełna odpowiedź")
                    val parts = root.getJSONArray("content")
                    buildString { for (i in 0 until parts.length()) {
                        val p = parts.getJSONObject(i); if (p.optString("type") == "text") append(p.getString("text"))
                    } }
                }
                AiProvider.GEMINI -> {
                    val candidate = root.getJSONArray("candidates").getJSONObject(0)
                    if (candidate.optString("finishReason") != "STOP") throw ApiException("Gemini: zablokowana lub niepełna odpowiedź")
                    val parts = candidate.getJSONObject("content").getJSONArray("parts")
                    buildString { for (i in 0 until parts.length()) {
                        val p = parts.getJSONObject(i); if (!p.optBoolean("thought", false)) append(p.optString("text", ""))
                    } }
                }
                AiProvider.GROQ -> {
                    val choice = root.getJSONArray("choices").getJSONObject(0)
                    if (choice.optString("finish_reason") != "stop") throw ApiException("Groq: zablokowana lub niepełna odpowiedź")
                    choice.getJSONObject("message").getString("content")
                }
            }
            return extractJson(text)
        }

        fun extractJson(text: String): JSONObject {
            val a = text.indexOf('{'); val b = text.lastIndexOf('}')
            if (a < 0 || b <= a) throw ApiException("model nie zwrócił JSON")
            return JSONObject(text.substring(a, b + 1))
        }
    }
}
