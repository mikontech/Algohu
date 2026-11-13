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

    /** Priorytety ntfy: 5 = pilne (dźwięk mimo trybu cichego), 4 = wysoki, 3 = zwykły, 2 = cichy, 1 = bez powiadomienia (tylko lista). */
    const val URGENT = 5
    const val HIGH = 4
    const val NORMAL = 3
    const val QUIET = 2
    const val MIN = 1

    @Volatile var topic: String? = null
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Stan ostatniej wysyłki – do HUD (żeby było widać, czy powiadomienia wychodzą). */
    @Volatile var lastOkAt: Long = 0L
        private set
    @Volatile var lastError: String? = null
        private set
    private val lastSent = ConcurrentHashMap<String, Long>()

    fun init(ctx: Context) {
        if (topic != null) return
        val p = ctx.applicationContext.getSharedPreferences("ntfy", Context.MODE_PRIVATE)
        // Kanał wyliczany z identyfikatora urządzenia – ten sam także po odinstalowaniu i ponownej instalacji
        // (przy stałym kluczu podpisu), więc nie trzeba co chwilę subskrybować nowego kanału w ntfy.
        val androidId = runCatching {
            android.provider.Settings.Secure.getString(ctx.contentResolver, android.provider.Settings.Secure.ANDROID_ID)
        }.getOrNull()
        topic = p.getString("topic", null)
            ?: (androidId?.takeIf { it.isNotBlank() }?.let { topicFor(it) } ?: newTopic())
                .also { p.edit().putString("topic", it).apply() }
    }

    /** Stała, trudna do zgadnięcia nazwa kanału dla danego urządzenia. */
    fun topicFor(deviceId: String): String {
        val abc = "abcdefghijkmnpqrstuvwxyz23456789"
        val h = java.security.MessageDigest.getInstance("SHA-256").digest("algohud:$deviceId".toByteArray())
        return "algohud-" + (0 until 10).map { abc[(h[it].toInt() and 0xFF) % abc.length] }.joinToString("")
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
    /**
     * Darmowy ntfy.sh przyjmuje ok. 250 wiadomości na dobę z jednego adresu IP (potem HTTP 429).
     * Budżet: ciche wiadomości (raporty, zapowiedzi, diagnoza) najwyżej [LOW_DAILY_BUDGET] na dobę,
     * reszta zostaje dla wejść, wyjść i alarmów. Po HTTP 429 ciche wiadomości pauzują na [BACKOFF_MS].
     */
    const val LOW_DAILY_BUDGET = 120
    const val BACKOFF_MS = 30 * 60_000L
    @Volatile private var day = 0L
    @Volatile private var lowSentToday = 0
    @Volatile private var backoffUntil = 0L

    fun send(title: String, text: String, priority: Int = NORMAL, tags: String = "", key: String? = null, minGapMs: Long = 0L) {
        val t = topic ?: return
        val nowMs = System.currentTimeMillis()
        if (priority <= QUIET) {
            val d = nowMs / 86_400_000L
            if (d != day) { day = d; lowSentToday = 0 }
            if (nowMs < backoffUntil || lowSentToday >= LOW_DAILY_BUDGET) return
        }
        if (key != null) {
            val prev = lastSent[key]
            if (prev != null && nowMs - prev < minGapMs) return
            lastSent[key] = nowMs
        }
        if (priority <= QUIET) lowSentToday++
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
                    if (code in 200..299) { lastOkAt = System.currentTimeMillis(); lastError = null }
                    else {
                        lastError = if (code == 429) "HTTP 429 – dzienny limit ntfy.sh, ciche raporty wstrzymane na 30 min" else "HTTP $code"
                        if (code == 429) backoffUntil = System.currentTimeMillis() + BACKOFF_MS
                        Log.w(TAG, "ntfy HTTP $code")
                    }
                } finally { c.disconnect() }
            }.onFailure { lastError = it.javaClass.simpleName + ": " + (it.message ?: ""); Log.w(TAG, "ntfy: wysyłka nieudana", it) }
        }
    }
}
