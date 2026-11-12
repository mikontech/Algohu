package com.example.algohud.bot

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * Powiadomienia na główny telefon przez ntfy (https://ntfy.sh) – działa bez konta i bez połączenia z telefonem bota.
 * Na głównym telefonie: aplikacja „ntfy” → „+” → nazwa kanału z HUD-a ([topic]).
 *
 * Kanał jest losowy i generowany raz na instalację (repo jest publiczne – stała nazwa w kodzie byłaby widoczna dla każdego).
 */
object Ntfy {
    private const val SERVER = "https://ntfy.sh/"
    private const val TAG = "Ntfy"

    /** Priorytety ntfy: 5 = pilne (dźwięk mimo trybu cichego), 4 = wysoki, 3 = zwykły, 2 = cichy. */
    const val URGENT = 5
    const val HIGH = 4
    const val NORMAL = 3
    const val QUIET = 2

    @Volatile var topic: String? = null
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lastSent = ConcurrentHashMap<String, Long>()

    fun init(ctx: Context) {
        if (topic != null) return
        val p = ctx.applicationContext.getSharedPreferences("ntfy", Context.MODE_PRIVATE)
        topic = p.getString("topic", null) ?: newTopic().also { p.edit().putString("topic", it).apply() }
    }

    /** Losowa, trudna do zgadnięcia nazwa kanału, np. "algohud-k3x9q2m7ta". */
    fun newTopic(random: SecureRandom = SecureRandom()): String {
        val abc = "abcdefghijkmnpqrstuvwxyz23456789"
        return "algohud-" + (1..10).map { abc[random.nextInt(abc.length)] }.joinToString("")
    }

    /** Adres wysyłki z tytułem/priorytetem/tagami w parametrach (nagłówki HTTP nie przyjmują polskich znaków). */
    fun url(topic: String, title: String, priority: Int, tags: String): String {
        fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
        return buildString {
            append(SERVER).append(topic)
            append("?title=").append(enc(title))
            append("&priority=").append(priority.coerceIn(1, 5))
            if (tags.isNotEmpty()) append("&tags=").append(enc(tags))
        }
    }

    /**
     * Wysyła powiadomienie w tle. [key] + [minGapMs] – nie częściej niż raz na [minGapMs] dla tego samego klucza.
     * Błędy sieci są tylko logowane (bot działa dalej).
     */
    fun send(title: String, text: String, priority: Int = NORMAL, tags: String = "", key: String? = null, minGapMs: Long = 0L) {
        val t = topic ?: return
        if (key != null) {
            val now = System.currentTimeMillis()
            val prev = lastSent[key]
            if (prev != null && now - prev < minGapMs) return
            lastSent[key] = now
        }
        scope.launch {
            runCatching {
                val c = URL(url(t, title, priority, tags)).openConnection() as HttpURLConnection
                try {
                    c.requestMethod = "POST"
                    c.doOutput = true
                    c.connectTimeout = 8_000
                    c.readTimeout = 8_000
                    c.setRequestProperty("Content-Type", "text/plain; charset=utf-8")
                    c.outputStream.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                    val code = c.responseCode
                    if (code !in 200..299) Log.w(TAG, "ntfy HTTP $code")
                } finally { c.disconnect() }
            }.onFailure { Log.w(TAG, "ntfy: wysyłka nieudana", it) }
        }
    }
}
