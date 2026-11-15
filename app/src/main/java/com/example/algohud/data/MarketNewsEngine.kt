package com.example.algohud.data

import android.util.Log
import android.util.Xml
import com.example.algohud.core.Bar
import com.example.algohud.core.MacroEvent
import com.example.algohud.core.NewsItem
import com.example.algohud.core.Tick
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.net.URI
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.math.tanh

// ───────────────────────────── Sentyment ─────────────────────────────

interface SentimentScorer {
    /** Zwraca wynik w zakresie [-1.0, +1.0]. */
    suspend fun score(text: String): Double
}

/** Szybki silnik regułowy: leksykon z wagami + negacje + wzmacniacze. Działa offline, < 1 ms. */
class KeywordSentimentScorer : SentimentScorer {
    private val lexicon: Map<String, Double> = mapOf(
        // EN – pozytywne
        "beat" to 0.6, "beats" to 0.6, "surge" to 0.7, "surges" to 0.7, "soar" to 0.7, "soars" to 0.7,
        "rally" to 0.6, "rallies" to 0.6, "upgrade" to 0.5, "upgraded" to 0.5, "approval" to 0.5,
        "approved" to 0.5, "stimulus" to 0.4, "growth" to 0.3, "bullish" to 0.6, "inflows" to 0.4,
        "record high" to 0.7, "rate cut" to 0.5, "rate cuts" to 0.5,
        // EN – negatywne
        "miss" to -0.6, "misses" to -0.6, "plunge" to -0.8, "plunges" to -0.8, "crash" to -0.9,
        "selloff" to -0.6, "downgrade" to -0.5, "downgraded" to -0.5, "recession" to -0.7,
        "default" to -0.8, "bankruptcy" to -0.9, "hack" to -0.8, "hacked" to -0.8, "exploit" to -0.7,
        "sanctions" to -0.5, "inflation" to -0.3, "layoffs" to -0.4, "lawsuit" to -0.4, "ban" to -0.6,
        "bearish" to -0.6, "outflows" to -0.4, "rate hike" to -0.4, "rate hikes" to -0.4,
        // PL
        "wzrost" to 0.4, "wzrosty" to 0.4, "rekord" to 0.5, "hossa" to 0.6, "spadek" to -0.4,
        "spadki" to -0.4, "krach" to -0.9, "recesja" to -0.7, "upadłość" to -0.9, "bessa" to -0.6,
        "obniżka stóp" to 0.5, "podwyżka stóp" to -0.4
    )
    private val negations = setOf("not", "no", "fails", "without", "nie", "brak", "bez")
    private val intensifiers = mapOf(
        "sharply" to 1.5, "massive" to 1.5, "huge" to 1.4, "slightly" to 0.5, "modest" to 0.6,
        "mocno" to 1.5, "gwałtowny" to 1.5, "lekko" to 0.5, "nieznacznie" to 0.5
    )
    private val tokenizer = Regex("[^\\p{L}\\p{N}]+")

    override suspend fun score(text: String): Double {
        val lower = text.lowercase()
        val tokens = lower.split(tokenizer).filter { it.isNotBlank() }
        var sum = 0.0
        var hits = 0
        for ((phrase, w) in lexicon) {
            if (' ' in phrase && lower.contains(phrase)) { sum += w; hits++ }
        }
        tokens.forEachIndexed { i, tok ->
            val base = lexicon[tok] ?: return@forEachIndexed
            val window = tokens.subList(maxOf(0, i - 3), i)
            var v = base
            if (window.any { it in negations }) v = -v * 0.7
            window.firstNotNullOfOrNull { intensifiers[it] }?.let { v *= it }
            sum += v
            hits++
        }
        if (hits == 0) return 0.0
        return tanh(sum / sqrt(hits.toDouble()))
    }
}

/**
 * Scoring przez TWÓJ backend (FinBERT / LLM). Nie umieszczaj kluczy do LLM w APK –
 * aplikacja woła własny serwer, który zwraca {"score": -1..1}. Fallback: silnik regułowy.
 */
class RemoteSentimentScorer(
    private val http: OkHttpClient,
    private val endpoint: String,
    private val fallback: SentimentScorer = KeywordSentimentScorer()
) : SentimentScorer {
    override suspend fun score(text: String): Double = withContext(Dispatchers.IO) {
        try {
            val body = JSONObject().put("text", text).toString()
                .toRequestBody("application/json".toMediaType())
            http.newCall(Request.Builder().url(endpoint).post(body).build()).execute().use { r ->
                if (!r.isSuccessful) error("HTTP ${r.code}")
                JSONObject(r.body!!.string()).getDouble("score").coerceIn(-1.0, 1.0)
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            fallback.score(text)
        }
    }
}

// ───────────────────────────── Kalendarz makro ─────────────────────────────

interface EconomicCalendarSource {
    suspend fun upcoming(): List<MacroEvent>

    object None : EconomicCalendarSource {
        override suspend fun upcoming(): List<MacroEvent> = emptyList()
    }
}

// ───────────────────────────── Silnik ─────────────────────────────

class MarketNewsEngine(
    private val scope: CoroutineScope,
    http: OkHttpClient,
    private val config: Config = Config(),
    private val scorer: SentimentScorer = KeywordSentimentScorer(),
    private val calendar: EconomicCalendarSource = EconomicCalendarSource.None
) {
    data class Config(
        /** Publiczne strumienie transakcji Binance (PAXG ≈ cena złota). */
        val feedSymbols: List<String> = listOf("BTCUSDT", "PAXGUSDT", "ETHUSDT"),
        /** Przykładowe kanały – sprawdź regulaminy źródeł przed użyciem. */
        val rssFeeds: List<String> = listOf(
            "https://www.coindesk.com/arc/outboundfeeds/rss/",
            "https://www.cnbc.com/id/100003114/device/rss/rss.html",
            "https://feeds.content.dowjones.io/public/rss/mw_topstories"
        ),
        val rssPollMs: Long = 60_000,
        val calendarPollMs: Long = 15 * 60_000,
        val sentimentHalfLifeMs: Long = 30 * 60_000,
        val macroBlackoutMs: Long = 15 * 60_000
    )

    private val http = http
    // Osobny klient dla WS: brak read-timeoutu (pingi utrzymują połączenie).
    private val wsClient = http.newBuilder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private val _ticks = MutableSharedFlow<Tick>(
        replay = 1, extraBufferCapacity = 256, onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val ticks: SharedFlow<Tick> = _ticks.asSharedFlow()

    private val _news = MutableSharedFlow<NewsItem>(
        replay = 20, extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val news: SharedFlow<NewsItem> = _news.asSharedFlow()

    private val _sentiment = MutableStateFlow(0.0)
    /** Zagregowany sentyment z wygaszaniem wykładniczym, [-1, 1]. */
    val sentiment: StateFlow<Double> = _sentiment.asStateFlow()

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private val _macro = MutableStateFlow<List<MacroEvent>>(emptyList())
    val macroEvents: StateFlow<List<MacroEvent>> = _macro.asStateFlow()

    private val lock = Mutex()
    private val seen = object : LinkedHashMap<String, Boolean>(1024, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?) = size > 3000
    }
    private val recent = ArrayDeque<NewsItem>()
    private val jobs = mutableListOf<Job>()
    private val livePrices = LivePriceGuard()
    private val tickMutex = Mutex()
    private val liveDiag = java.util.concurrent.ConcurrentHashMap<String, String>()
    fun liveDiagnostic(symbol: String): String = liveDiag[symbol] ?: "czekam na transakcje"
    private val priceHttp = http.newBuilder().callTimeout(8, TimeUnit.SECONDS).retryOnConnectionFailure(false).build()

    private suspend fun publishTrade(trade: LivePriceGuard.Trade, source: String) = tickMutex.withLock {
        if (livePrices.accept(trade, System.currentTimeMillis())) {
            liveDiag[trade.tick.symbol] = "$source: odebrano transakcję"
            _ticks.emit(trade.tick)
        }
    }

    /** Zapas gdy WebSocket jest zablokowany lub milczy; ta sama giełda, symbol i czas transakcji. */
    private suspend fun runRestPrices() {
        var retryAt = 0L
        while (currentCoroutineContext().isActive) {
            delay(5_000)
            if (System.currentTimeMillis() < retryAt) continue
            for (symbol in config.feedSymbols) {
                val now = System.currentTimeMillis()
                if (livePrices.lastAt(symbol)?.let { now - it < 10_000 } == true) continue
                try {
                    val raw = withContext(Dispatchers.IO) {
                        val url = "https://data-api.binance.vision/api/v3/trades?symbol=$symbol&limit=100"
                        priceHttp.newCall(Request.Builder().url(url).build()).execute().use { r ->
                            if (r.code == 429 || r.code == 418) {
                                retryAt = System.currentTimeMillis() + maxOf(60L, r.header("Retry-After")?.toLongOrNull() ?: 60L) * 1000
                            }
                            if (!r.isSuccessful) error("HTTP ${r.code}")
                            r.body?.string() ?: error("pusta odpowiedź")
                        }
                    }
                    val trades = LivePriceGuard.rest(symbol, raw)
                    for (trade in trades) publishTrade(trade, "Binance HTTPS")
                    if (livePrices.lastAt(symbol)?.let { System.currentTimeMillis() - it <= 20_000 } != true)
                        liveDiag[symbol] = "HTTPS odpowiada, brak transakcji z ostatnich 20 s"
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { liveDiag[symbol] = "HTTPS: ${e.message?.take(100)}" }
                if (System.currentTimeMillis() < retryAt) break
            }
        }
    }

    fun start() {
        if (jobs.isNotEmpty()) return
        jobs += scope.launch { runTickLoop() }
        jobs += scope.launch { runRestPrices() }
        jobs += scope.launch { runRssLoop() }
        jobs += scope.launch { runCalendarLoop() }
        jobs += scope.launch { while (isActive) { recomputeSentiment(); delay(15_000) } }
    }

    fun stop() {
        jobs.forEach { it.cancel() }
        jobs.clear()
        _connected.value = false
    }

    /** true, jeśli w oknie ±macroBlackoutMs jest publikacja o wysokim wpływie (NFP, CPI, FOMC…). */
    fun isInMacroBlackout(now: Long = System.currentTimeMillis()): Boolean =
        _macro.value.any { it.impact == MacroEvent.Impact.HIGH && abs(it.timeMillis - now) <= config.macroBlackoutMs }

    // ── WebSocket z reconnectem (exponential backoff) ──

    private suspend fun runTickLoop() {
        var attempt = 0
        var endpoint = 0
        val hosts = listOf("wss://data-stream.binance.vision:443", "wss://stream.binance.com:443", "wss://stream.binance.com:9443")
        while (currentCoroutineContext().isActive) {
            try {
                val url = hosts[endpoint++ % hosts.size] + "/stream?streams=" +
                    config.feedSymbols.joinToString("/") { it.lowercase() + "@trade" }
                tickStream(url).collect { trade ->
                    attempt = 0
                    publishTrade(trade, "Binance WebSocket")
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                Log.w(TAG, "WebSocket: ${t.message}")
            }
            _connected.value = false
            delay(min(30_000L, 1_000L shl min(attempt++, 5)))
        }
    }

    private fun tickStream(url: String): Flow<LivePriceGuard.Trade> = callbackFlow {
        val ws = wsClient.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { _connected.value = true }
            override fun onMessage(webSocket: WebSocket, text: String) { LivePriceGuard.websocket(text)?.let { trySend(it) } }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { close(t) }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { close() }
        })
        awaitClose { ws.close(1000, "bye") }
    }.buffer(256, BufferOverflow.DROP_OLDEST)

    /** Długa historia: kolejne partie po 1000 świec wstecz (Binance pozwala na endTime). */
    suspend fun historyPaged(symbol: String, interval: String, totalBars: Int): List<Bar> = withContext(Dispatchers.IO) {
        val out = ArrayList<Bar>()
        var endTime: Long? = null
        repeat(30) {
            if (out.size >= totalBars) return@repeat
            val url = "https://api.binance.com/api/v3/klines?symbol=$symbol&interval=$interval&limit=1000" +
                (endTime?.let { "&endTime=$it" } ?: "")
            val batch = http.newCall(Request.Builder().url(url).build()).execute().use { r ->
                if (!r.isSuccessful) error("HTTP ${r.code}")
                val arr = JSONArray(r.body!!.string())
                (0 until arr.length()).map { i ->
                    val k = arr.getJSONArray(i)
                    Bar(k.getLong(0), k.getString(1).toDouble(), k.getString(2).toDouble(),
                        k.getString(3).toDouble(), k.getString(4).toDouble())
                }
            }
            if (batch.isEmpty()) return@repeat
            out.addAll(0, batch)
            endTime = batch.first().start - 1
        }
        out.distinctBy { it.start }.sortedBy { it.start }.dropLast(1).takeLast(totalBars)
    }

    /** Historia świec z Binance REST (bez trwającej świecy). */
    suspend fun history(symbol: String, interval: String, limit: Int): List<Bar> = withContext(Dispatchers.IO) {
        val url = "https://api.binance.com/api/v3/klines?symbol=$symbol&interval=$interval&limit=$limit"
        http.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) error("HTTP ${r.code}")
            val arr = JSONArray(r.body!!.string())
            (0 until arr.length()).map { i ->
                val k = arr.getJSONArray(i)
                Bar(k.getLong(0), k.getString(1).toDouble(), k.getString(2).toDouble(),
                    k.getString(3).toDouble(), k.getString(4).toDouble())
            }.dropLast(1)
        }
    }

    // ── RSS / Atom ──

    private data class RawItem(val id: String, val title: String, val ts: Long)

    private suspend fun runRssLoop() {
        while (currentCoroutineContext().isActive) {
            for (url in config.rssFeeds) {
                val items = try {
                    fetchFeed(url)
                } catch (ce: CancellationException) {
                    throw ce
                } catch (t: Throwable) {
                    Log.w(TAG, "RSS $url: ${t.message}"); emptyList()
                }
                for (raw in items) {
                    val isNew = lock.withLock { seen.put(raw.id, true) == null }
                    if (!isNew) continue
                    val item = NewsItem(raw.id, raw.title, host(url), raw.ts, scorer.score(raw.title))
                    lock.withLock { recent.addLast(item) }
                    _news.emit(item)
                }
            }
            recomputeSentiment()
            delay(config.rssPollMs)
        }
    }

    private suspend fun fetchFeed(url: String): List<RawItem> = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url).header("User-Agent", "AlgoHUD/1.0").build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code}")
            val parser = Xml.newPullParser().apply {
                setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
                setInput(resp.body!!.charStream())
            }
            val out = mutableListOf<RawItem>()
            var inItem = false
            var title = ""; var link = ""; var guid = ""; var date = ""
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> when (parser.name) {
                        "item", "entry" -> { inItem = true; title = ""; link = ""; guid = ""; date = "" }
                        "title" -> if (inItem) title = parser.nextText().trim()
                        "link" -> if (inItem && link.isEmpty()) {
                            link = parser.getAttributeValue(null, "href") ?: parser.nextText().trim()
                        }
                        "guid", "id" -> if (inItem) guid = parser.nextText().trim()
                        "pubDate", "published", "updated" ->
                            if (inItem && date.isEmpty()) date = parser.nextText().trim()
                    }
                    XmlPullParser.END_TAG -> if (parser.name == "item" || parser.name == "entry") {
                        inItem = false
                        if (title.isNotEmpty()) {
                            out += RawItem(guid.ifEmpty { link.ifEmpty { title } }, title, parseDate(date))
                        }
                    }
                }
                event = parser.next()
            }
            out
        }
    }

    private fun parseDate(s: String): Long {
        if (s.isEmpty()) return System.currentTimeMillis()
        return runCatching {
            ZonedDateTime.parse(s, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
        }.recoverCatching {
            OffsetDateTime.parse(s).toInstant().toEpochMilli()
        }.getOrDefault(System.currentTimeMillis())
    }

    private fun host(url: String) = runCatching { URI(url).host }.getOrNull() ?: url

    // ── Kalendarz ──

    private suspend fun runCalendarLoop() {
        while (currentCoroutineContext().isActive) {
            try {
                _macro.value = calendar.upcoming()
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                Log.w(TAG, "Kalendarz: ${t.message}")
            }
            delay(config.calendarPollMs)
        }
    }

    // ── Agregacja sentymentu ──

    private suspend fun recomputeSentiment() {
        val now = System.currentTimeMillis()
        val value = lock.withLock {
            recent.removeAll { now - it.publishedAt > MAX_NEWS_AGE_MS }
            while (recent.size > 500) recent.removeFirst()
            weighted(recent, now)
        }
        _sentiment.value = value.coerceIn(-1.0, 1.0)
    }

    /** Sentyment tylko z nagłówków zawierających któryś alias (np. "gold", "złoto"). Zwraca (wynik, liczba newsów). */
    suspend fun sentimentFor(aliases: Collection<String>): Pair<Double, Int> {
        if (aliases.isEmpty()) return 0.0 to 0
        val now = System.currentTimeMillis()
        val keys = aliases.map { it.lowercase() }
        return lock.withLock {
            val matching = recent.filter { n ->
                val t = n.title.lowercase()
                keys.any { t.contains(it) }
            }
            weighted(matching, now).coerceIn(-1.0, 1.0) to matching.size
        }
    }

    private fun weighted(items: Iterable<NewsItem>, now: Long): Double {
        var num = 0.0
        var den = 0.0
        for (n in items) {
            val age = (now - n.publishedAt).coerceAtLeast(0L).toDouble()
            val w = 0.5.pow(age / config.sentimentHalfLifeMs)
            num += w * n.sentiment
            den += w
        }
        // +1.0 w mianowniku = neutralny "prior": przy małej liczbie newsów wynik ciąży ku 0.
        return num / (den + 1.0)
    }

    private companion object {
        const val TAG = "MarketNewsEngine"
        const val MAX_NEWS_AGE_MS = 6 * 3_600_000L
    }
}
