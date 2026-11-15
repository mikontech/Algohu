package com.example.algohud.council

import android.content.SharedPreferences
import java.lang.reflect.Proxy
import org.json.JSONObject
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** Pamięć preferencji bez android.jar implementacji runtime. */
internal fun memoryPrefs(data: MutableMap<String, Any> = mutableMapOf()): SharedPreferences {
    val editor = Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader,
        arrayOf(SharedPreferences.Editor::class.java)) { proxy, method, args ->
        when (method.name) {
            "putLong", "putString", "putBoolean" -> { data[args!![0] as String] = args[1]; proxy }
            "commit" -> true
            "apply" -> null
            else -> error(method.name)
        }
    } as SharedPreferences.Editor
    return Proxy.newProxyInstance(SharedPreferences::class.java.classLoader,
        arrayOf(SharedPreferences::class.java)) { _, method, args ->
        when (method.name) {
            "getLong", "getString", "getBoolean" -> data[args!![0]] ?: args[1]
            "edit" -> editor
            else -> error(method.name)
        }
    } as SharedPreferences
}

class AiProvidersTest {
    private fun body(r: okhttp3.Request): JSONObject {
        val b = okio.Buffer(); r.body!!.writeTo(b); return JSONObject(b.readUtf8())
    }
    @Test fun geminiHasDedicatedKeyHeaderAndImage() {
        val r = ClaudeClient.request("gemini:gemini-3.8-flash", "gemini-secret", "system", "user", "JPEG", 500)
        assertEquals("generativelanguage.googleapis.com", r.url.host)
        assertEquals("gemini-secret", r.header("x-goog-api-key"))
        assertNull(r.header("Authorization")); assertFalse(r.url.toString().contains("secret"))
        val b = body(r)
        assertEquals("system", b.getJSONObject("systemInstruction").getJSONArray("parts").getJSONObject(0).getString("text"))
        assertEquals("JPEG", b.getJSONArray("contents").getJSONObject(0).getJSONArray("parts").getJSONObject(1).getJSONObject("inlineData").getString("data"))
        assertEquals("application/json", b.getJSONObject("generationConfig").getString("responseMimeType"))
    }
    @Test fun groqUsesChatCompletionAndDataImage() {
        val r = ClaudeClient.request("groq:qwen/qwen3.8-27b", "groq-secret", "system", "user", "JPEG", 500)
        assertEquals("api.groq.com", r.url.host)
        assertEquals("Bearer groq-secret", r.header("Authorization"))
        assertNull(r.header("x-goog-api-key"))
        val b = body(r)
        assertEquals("qwen/qwen3.8-27b", b.getString("model"))
        assertTrue(b.getJSONArray("messages").getJSONObject(1).getJSONArray("content").getJSONObject(1).getJSONObject("image_url").getString("url").startsWith("data:image/jpeg;base64,"))
    }
    @Test fun claudeCompatibility() {
        val r = ClaudeClient.request("claude-haiku-5-5", "legacy", "s", "u", null, 500)
        assertEquals("api.anthropic.com", r.url.host)
        assertEquals("legacy", r.header("x-api-key"))
        assertEquals(500, body(r).getInt("max_tokens"))
    }
    @Test fun parsesAllResponseEnvelopes() {
        val answer = "{\"screen_ok\":true}"
        val gem = JSONObject().put("candidates", org.json.JSONArray().put(JSONObject().put("finishReason", "STOP")
            .put("content", JSONObject().put("parts", org.json.JSONArray().put(JSONObject().put("thought", true).put("text", "ignore"))
                .put(JSONObject().put("text", answer))))))
        assertTrue(ClaudeClient.parse(AiProvider.GEMINI, gem.toString()).getBoolean("screen_ok"))
        assertTrue(ClaudeClient.parse(AiProvider.GROQ, """{"choices":[{"finish_reason":"stop","message":{"content":"{\"ok\":true}"}}]}""").getBoolean("ok"))
        assertTrue(ClaudeClient.parse(AiProvider.CLAUDE, """{"stop_reason":"end_turn","content":[{"type":"text","text":"{\"ok\":true}"}]}""").getBoolean("ok"))
    }
    @Test fun rejectsTruncationEvenIfJsonLooksValid() {
        assertThrows(ClaudeClient.ApiException::class.java) {
            ClaudeClient.parse(AiProvider.GROQ, """{"choices":[{"finish_reason":"length","message":{"content":"{\"execute\":true}"}}]}""")
        }
        assertThrows(ClaudeClient.ApiException::class.java) {
            ClaudeClient.parse(AiProvider.GEMINI, """{"candidates":[{"finishReason":"MAX_TOKENS","content":{"parts":[{"text":"{}"}]}}]}""")
        }
    }
    @Test fun hybridRequiresBothKeysAndKeepsProvidersSeparate() {
        val data = mutableMapOf<String, Any>("ai_route" to "GEMINI_GROQ", "gemini_api_key" to "gemini")
        val p = memoryPrefs(data)
        assertFalse(AiSettings.hasKeys(p))
        data["groq_api_key"] = "groq"
        assertTrue(AiSettings.hasKeys(p))
        assertEquals(AiProvider.GEMINI, AiSettings.provider(AiSettings.plan(p).fast))
        assertEquals(AiProvider.GROQ, AiSettings.provider(AiSettings.plan(p).deep))
        assertEquals("groq", AiSettings.key(p, AiProvider.GROQ))
    }
    @Test fun legacyKeyKeepsClaudeUnlessExplicitlyChanged() {
        val d = mutableMapOf<String, Any>("council_api_key" to "legacy")
        val p = memoryPrefs(d)
        assertEquals(AiRoute.CLAUDE, AiSettings.route(p))
        d["ai_route"] = "GEMINI"
        assertFalse(AiSettings.hasKeys(p))
    }
    @Test fun rateLimitBacksOffWithoutRetryOrErrorBodyLeak() = runTest {
        var calls = 0
        val p = memoryPrefs()
        val limits = AiLimits(p) { 1_000_000 }
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            calls++
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(429).message("limit")
                .header("Retry-After", "1200").body("SECRET provider body".toResponseBody()).build()
        }.build()
        val client = ClaudeClient({ "unused" }, http, { "key" }, limits)
        repeat(2) {
            val e = runCatching { client.askJson("groq:qwen/qwen3.8-27b", "s", "u") }.exceptionOrNull()
            assertNotNull(e); assertFalse(e!!.message.orEmpty().contains("SECRET"))
        }
        assertEquals(1, calls)
        assertEquals(2_200_000L, p.getLong("ai_backoff_GROQ", 0))
    }
    @Test fun networkDiagnosticsNeverLeakExceptionMessage() {
        val errors = listOf(java.net.UnknownHostException("SECRET"),
            java.net.SocketTimeoutException("SECRET"), javax.net.ssl.SSLHandshakeException("SECRET"))
        val messages = errors.map { ClaudeClient.networkFailure(AiProvider.GEMINI, it) }
        assertTrue(messages[0].message!!.contains("DNS"))
        assertTrue(messages[1].message!!.contains("timeout"))
        assertTrue(messages[2].message!!.contains("TLS"))
        assertFalse(messages[2].retryable)
        messages.forEach { assertFalse(it.message!!.contains("SECRET")) }
    }

    @Test fun transientConnectionFailureIsRetriedOnce() = runTest {
        var calls = 0
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            calls++
            if (calls == 1) throw java.net.SocketTimeoutException("SECRET")
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"candidates":[{"finishReason":"STOP","content":{"parts":[{"text":"{\"ok\":true}"}]}}]}""".toResponseBody()).build()
        }.build()
        val client = ClaudeClient({ "key" }, http)
        assertTrue(client.askJson("gemini:test", "s", "u").getBoolean("ok"))
        assertEquals(2, calls)
    }

    @Test fun tlsFailureIsNotRetried() = runTest {
        var calls = 0
        val http = OkHttpClient.Builder().addInterceptor {
            calls++; throw javax.net.ssl.SSLHandshakeException("SECRET")
        }.build()
        val e = runCatching { ClaudeClient({ "key" }, http).askJson("gemini:test", "s", "u") }.exceptionOrNull()
        assertTrue(e!!.message!!.contains("TLS"))
        assertFalse(e.message!!.contains("SECRET"))
        assertEquals(1, calls)
    }

}
