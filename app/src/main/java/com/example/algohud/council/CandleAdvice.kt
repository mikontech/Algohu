package com.example.algohud.council

import com.example.algohud.core.Bar
import org.json.JSONObject

/** Opinia bez OrderRequest i bez dostępu do wykonawcy zleceń. */
data class CandleAdvice(val side: String, val entry: Double?, val invalidation: Double?, val condition: String, val reason: String) {
    fun display(): String = if (side == "WAIT") "CZEKAM: $reason • warunek: $condition"
        else "$side przy %.2f • warunek: %s • unieważnienie %.2f • %s".format(entry, condition, invalidation, reason)
    companion object {
        fun parse(json: JSONObject): CandleAdvice {
            val side = json.getString("side")
            require(side in listOf("LONG", "SHORT", "WAIT")) { "nieprawidłowy kierunek opinii" }
            val condition = json.getString("condition").trim().take(200)
            val reason = json.getString("reason").trim().take(200)
            require(condition.isNotEmpty() && reason.isNotEmpty()) { "brak uzasadnienia opinii" }
            if (side == "WAIT") return CandleAdvice(side, null, null, condition, reason)
            val entry = json.getDouble("entry")
            val invalidation = json.getDouble("invalidation")
            require(entry.isFinite() && entry > 0 && invalidation.isFinite() && invalidation > 0)
            require(if (side == "LONG") invalidation < entry else invalidation > entry) { "błędny poziom unieważnienia" }
            return CandleAdvice(side, entry, invalidation, condition, reason)
        }
        fun validBars(bars: List<Bar>, interval: Long, now: Long): Boolean = bars.isNotEmpty() &&
            bars.all { b -> b.start > 0 && b.start + interval <= now &&
                listOf(b.open, b.high, b.low, b.close).all { it.isFinite() && it > 0 } &&
                b.low <= minOf(b.open, b.close) && b.high >= maxOf(b.open, b.close) } &&
            bars.zipWithNext().all { (a, b) -> b.start - a.start == interval } &&
            now - (bars.last().start + interval) in 0..interval
    }
}
