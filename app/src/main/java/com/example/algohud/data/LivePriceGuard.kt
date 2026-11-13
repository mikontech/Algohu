package com.example.algohud.data

import com.example.algohud.core.Tick
import org.json.JSONArray
import org.json.JSONObject

/** Tylko nowe transakcje z czasem giełdy; ponowny odczyt REST nie odmładza ceny. */
class LivePriceGuard {
    data class Trade(val tick: Tick, val id: Long)
    private val accepted = HashMap<String, Trade>()
    @Synchronized fun lastAt(symbol: String): Long? = accepted[symbol]?.tick?.timestamp
    @Synchronized fun accept(t: Trade, now: Long): Boolean {
        val tick = t.tick
        if (!tick.price.isFinite() || tick.price <= 0 || tick.timestamp <= 0 ||
            tick.timestamp > now + 2000 || now - tick.timestamp > 20_000) return false
        val prev = accepted[tick.symbol]
        if (prev != null && (tick.timestamp < prev.tick.timestamp || t.id <= prev.id)) return false
        accepted[tick.symbol] = t
        return true
    }
    companion object {
        fun websocket(raw: String): Trade? = runCatching {
            val root = JSONObject(raw)
            val o = root.optJSONObject("data") ?: root
            Trade(Tick(o.getString("s"), o.getString("p").toDouble(), o.getLong("T")), o.getLong("t"))
        }.getOrNull()
        fun rest(symbol: String, raw: String): List<Trade> {
            val a = JSONArray(raw)
            return (0 until a.length()).mapNotNull { i -> runCatching {
                val o = a.getJSONObject(i)
                Trade(Tick(symbol, o.getString("price").toDouble(), o.getLong("time")), o.getLong("id"))
            }.getOrNull() }.sortedWith(compareBy<Trade> { it.tick.timestamp }.thenBy { it.id })
        }
    }
}
