package com.example.algohud.risk

import android.content.SharedPreferences
import com.example.algohud.core.Side
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

data class ClosedTrade(
    val symbol: String,
    val side: Side,
    val quantity: Double,
    val entry: Double,
    val exit: Double,
    val pnl: Double,
    /** Wynik w wielokrotnościach ryzyka początkowego (1R = strata przy pierwotnym SL). */
    val r: Double,
    val reason: String,
    val paper: Boolean,
    val closedAt: Long
)

data class JournalStats(val trades: Int = 0, val winRate: Double = 0.0, val avgR: Double = 0.0, val pnl: Double = 0.0) {
    fun summary(): String =
        if (trades == 0) "Papier: brak zamkniętych transakcji"
        else "Papier: %d trans. • %.0f%% trafień • E=%+.2fR • %+.2f".format(trades, winRate * 100, avgR, pnl)
}

/** Dziennik zamkniętych transakcji (trwały). Statystyki liczone z transakcji papierowych. */
class TradeJournal(private val prefs: SharedPreferences, private val maxTrades: Int = 300) {
    private val trades = ArrayDeque<ClosedTrade>()
    private val _stats = MutableStateFlow(JournalStats())
    val stats: StateFlow<JournalStats> = _stats.asStateFlow()

    init {
        load()
        recompute()
    }

    @Synchronized
    fun record(t: ClosedTrade) {
        trades.addLast(t)
        while (trades.size > maxTrades) trades.removeFirst()
        save()
        recompute()
    }

    @Synchronized
    fun all(): List<ClosedTrade> = trades.toList()

    private fun recompute() {
        val p = trades.filter { it.paper }
        _stats.value = if (p.isEmpty()) JournalStats() else JournalStats(
            trades = p.size,
            winRate = p.count { it.pnl > 0 }.toDouble() / p.size,
            avgR = p.map { it.r }.average(),
            pnl = p.sumOf { it.pnl }
        )
    }

    private fun save() {
        val arr = JSONArray()
        for (t in trades) {
            arr.put(
                JSONObject()
                    .put("s", t.symbol).put("side", t.side.name).put("q", t.quantity)
                    .put("e", t.entry).put("x", t.exit).put("pnl", t.pnl).put("r", t.r)
                    .put("why", t.reason).put("paper", t.paper).put("at", t.closedAt)
            )
        }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    private fun load() {
        val raw = prefs.getString(KEY, null) ?: return
        runCatching {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                trades.addLast(
                    ClosedTrade(
                        o.getString("s"), Side.valueOf(o.getString("side")), o.getDouble("q"),
                        o.getDouble("e"), o.getDouble("x"), o.getDouble("pnl"), o.getDouble("r"),
                        o.getString("why"), o.getBoolean("paper"), o.getLong("at")
                    )
                )
            }
        }
    }

    private companion object {
        const val KEY = "journal_trades"
    }
}
