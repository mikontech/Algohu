package com.example.algohud.data

import android.util.Log
import com.example.algohud.core.Bar
import com.example.algohud.core.Tick
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.pow

/**
 * Darmowe ceny z Pyth Network (Hermes, bez klucza): towary i metale.
 * Feed ID wyszukiwany dynamicznie po nazwie; historia świec z Pyth Benchmarks (shim TradingView).
 * Jeśli feed nie istnieje w darmowej wersji (np. kawa/kakao bywają tylko w Pyth Pro) – status "brak".
 */
class PythFeed(
    private val scope: CoroutineScope,
    private val http: OkHttpClient,
    private val pollMs: Long = 2_000
) {
    /** Instrument XTB → szukana fraza w symbolu Pyth (np. "USOILSPOT"). */
    data class Spec(val instrument: String, val query: String)

    data class Resolved(val instrument: String, val id: String, val symbol: String)

    private val _ticks = MutableSharedFlow<Tick>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    /** Tick.symbol = nazwa instrumentu XTB (np. "OIL"). */
    val ticks: SharedFlow<Tick> = _ticks.asSharedFlow()

    private val _status = MutableStateFlow<Map<String, String>>(emptyMap())
    /** instrument → "ok" / opis problemu. */
    val status: StateFlow<Map<String, String>> = _status.asStateFlow()

    private val resolved = HashMap<String, Resolved>()

    fun resolvedFor(instrument: String): Resolved? = synchronized(resolved) { resolved[instrument] }

    fun start(specs: List<Spec>, onResolved: suspend (Resolved) -> Unit) {
        scope.launch {
            for (s in specs) {
                val r = runCatching { resolve(s) }.getOrElse { e ->
                    setStatus(s.instrument, "błąd wyszukiwania (${e.message})"); null
                }
                if (r == null) {
                    if (_status.value[s.instrument] == null) setStatus(s.instrument, "brak darmowego źródła w Pyth")
                    continue
                }
                synchronized(resolved) { resolved[s.instrument] = r }
                setStatus(s.instrument, "ok")
                runCatching { onResolved(r) }.onFailure { Log.w(TAG, "onResolved ${s.instrument}: ${it.message}") }
            }
            pollLoop()
        }
    }

    private suspend fun pollLoop() {
        val lastPublish = HashMap<String, Long>()
        while (scope.isActive) {
            val items = synchronized(resolved) { resolved.values.toList() }
            if (items.isNotEmpty()) {
                try {
                    val url = "https://hermes.pyth.network/v2/updates/price/latest".toHttpUrl().newBuilder().apply {
                        items.forEach { addQueryParameter("ids[]", it.id) }
                        addQueryParameter("parsed", "true")
                        addQueryParameter("encoding", "hex")
                    }.build()
                    val json = withContext(Dispatchers.IO) {
                        http.newCall(Request.Builder().url(url).build()).execute().use { r ->
                            if (!r.isSuccessful) error("HTTP ${r.code}")
                            JSONObject(r.body!!.string())
                        }
                    }
                    val parsed = json.optJSONArray("parsed") ?: JSONArray()
                    val now = System.currentTimeMillis()
                    for (i in 0 until parsed.length()) {
                        val p = parsed.getJSONObject(i)
                        val id = p.getString("id").removePrefix("0x")
                        val item = items.firstOrNull { it.id.removePrefix("0x").equals(id, ignoreCase = true) } ?: continue
                        val price = p.getJSONObject("price")
                        val publishMs = price.getLong("publish_time") * 1000
                        // Rynek zamknięty / brak nowych cen – nie produkujemy sztucznych, płaskich świec.
                        if (now - publishMs > 120_000 || lastPublish[item.instrument] == publishMs) continue
                        lastPublish[item.instrument] = publishMs
                        val value = price.getString("price").toDouble() * 10.0.pow(price.getInt("expo"))
                        _ticks.emit(Tick(item.instrument, value, publishMs))
                    }
                } catch (ce: CancellationException) {
                    throw ce
                } catch (t: Throwable) {
                    Log.w(TAG, "poll: ${t.message}")
                }
            }
            delay(pollMs)
        }
    }

    private suspend fun resolve(spec: Spec): Resolved? = withContext(Dispatchers.IO) {
        val url = "https://hermes.pyth.network/v2/price_feeds".toHttpUrl().newBuilder()
            .addQueryParameter("query", spec.query).build()
        http.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) error("HTTP ${r.code}")
            val arr = JSONArray(r.body!!.string())
            val candidates = (0 until arr.length()).map { arr.getJSONObject(it) }.mapNotNull { o ->
                val attrs = o.optJSONObject("attributes") ?: return@mapNotNull null
                val symbol = attrs.optString("symbol")
                if (!symbol.contains(spec.query, ignoreCase = true)) return@mapNotNull null
                Resolved(spec.instrument, o.getString("id"), symbol)
            }
            // Preferuj notowania w USD i najkrótszy symbol (bez kontraktów z datą).
            candidates.filter { it.symbol.endsWith("/USD", ignoreCase = true) || !it.symbol.contains('/') }
                .minByOrNull { it.symbol.length } ?: candidates.minByOrNull { it.symbol.length }
        }
    }

    /** Historia świec z Pyth Benchmarks. @param resolutionMin 1 albo 5. */
    suspend fun history(r: Resolved, resolutionMin: Int, bars: Int): List<Bar> = withContext(Dispatchers.IO) {
        // Partiami wstecz (po ~2000 świec), bo rynki towarowe mają przerwy i API może ograniczać zakres.
        val out = ArrayList<Bar>()
        var to = System.currentTimeMillis() / 1000
        val chunk = resolutionMin * 60L * 2000
        var emptyInRow = 0
        repeat(25) {
            if (out.size >= bars || emptyInRow >= 3) return@repeat
            val part = runCatching { historyChunk(r, resolutionMin, to - chunk, to) }.getOrElse { e ->
                if (out.isEmpty()) throw e else emptyList()
            }
            if (part.isEmpty()) emptyInRow++ else { emptyInRow = 0; out.addAll(0, part) }
            to -= chunk
        }
        out.distinctBy { it.start }.sortedBy { it.start }.dropLast(1).takeLast(bars)
    }

    private fun historyChunk(r: Resolved, resolutionMin: Int, from: Long, to: Long): List<Bar> {
        val url = "https://benchmarks.pyth.network/v1/shims/tradingview/history".toHttpUrl().newBuilder()
            .addQueryParameter("symbol", r.symbol)
            .addQueryParameter("resolution", resolutionMin.toString())
            .addQueryParameter("from", from.toString())
            .addQueryParameter("to", to.toString())
            .build()
        return http.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code}")
            val o = JSONObject(resp.body!!.string())
            if (o.optString("s") == "no_data") return emptyList()
            if (o.optString("s") != "ok") error("brak danych (${o.optString("s")})")
            val t = o.getJSONArray("t"); val op = o.getJSONArray("o"); val h = o.getJSONArray("h")
            val l = o.getJSONArray("l"); val c = o.getJSONArray("c")
            (0 until t.length()).map { i ->
                Bar(t.getLong(i) * 1000, op.getDouble(i), h.getDouble(i), l.getDouble(i), c.getDouble(i))
            }
        }
    }

    private fun setStatus(instrument: String, s: String) {
        _status.value = _status.value + (instrument to s)
    }

    private companion object {
        const val TAG = "PythFeed"
    }
}
