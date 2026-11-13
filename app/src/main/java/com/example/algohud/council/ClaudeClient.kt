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

/**
 * Minimalny klient Anthropic Messages API (bez SDK – tylko OkHttp + org.json, które już są w projekcie).
 * Klucz API podaje użytkownik w aplikacji (SharedPreferences) – NIGDY w kodzie ani w sekretach CI,
 * bo APK z artefaktów publicznego repo może pobrać każdy zalogowany na GitHubie.
 */
class ClaudeClient(
    private val apiKey: () -> String?,
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .build()
) {
    class ApiException(message: String) : IOException(message)

    /** Zwraca obiekt JSON z odpowiedzi modelu. Rzuca wyjątek, gdy odpowiedź nie jest poprawnym JSON-em. */
    suspend fun askJson(
        model: String,
        system: String,
        userText: String,
        imageJpegBase64: String? = null,
        maxTokens: Int = 700
    ): JSONObject {
        val key = apiKey()?.takeIf { it.isNotBlank() } ?: throw ApiException("brak klucza API Claude (ustaw w aplikacji)")
        val content = JSONArray()
        if (imageJpegBase64 != null) {
            content.put(JSONObject()
                .put("type", "image")
                .put("source", JSONObject().put("type", "base64").put("media_type", "image/jpeg").put("data", imageJpegBase64)))
        }
        content.put(JSONObject().put("type", "text").put("text", userText))
        val body = JSONObject()
            .put("model", model)
            .put("max_tokens", maxTokens)
            .put("temperature", 0.2)
            .put("system", system)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", content)))
        val req = Request.Builder()
            .url("https://api.anthropic.com/v1/messages")
            .header("x-api-key", key)
            .header("anthropic-version", "2023-06-01")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        val text = withContext(Dispatchers.IO) { http.newCall(req).await() }
        return extractJson(text)
    }

    private suspend fun Call.await(): String = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { runCatching { cancel() } }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { if (cont.isActive) cont.resumeWithException(e) }
            override fun onResponse(call: Call, response: Response) {
                response.use { r ->
                    val s = r.body?.string().orEmpty()
                    if (!r.isSuccessful) {
                        val msg = runCatching { JSONObject(s).getJSONObject("error").getString("message") }.getOrDefault(s.take(160))
                        cont.resumeWithException(ApiException("HTTP ${r.code}: $msg"))
                        return
                    }
                    val out = runCatching {
                        val arr = JSONObject(s).getJSONArray("content")
                        buildString { for (i in 0 until arr.length()) arr.getJSONObject(i).optString("text").let { append(it) } }
                    }
                    out.fold({ cont.resume(it) }, { cont.resumeWithException(ApiException("zła odpowiedź API: ${it.message}")) })
                }
            }
        })
    }

    companion object {
        /** Wyciąga pierwszy obiekt {...} – model czasem doda ```json albo zdanie wstępu. */
        fun extractJson(text: String): JSONObject {
            val a = text.indexOf('{'); val b = text.lastIndexOf('}')
            if (a < 0 || b <= a) throw ApiException("model nie zwrócił JSON: ${text.take(120)}")
            return JSONObject(text.substring(a, b + 1))
        }
    }
}
