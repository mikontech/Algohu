package com.example.algohud.research

import com.example.algohud.core.*
import kotlin.math.abs

/** Wyłącznie symulacja na obserwowanych bid/ask XTB, nigdy nie wysyła zleceń. */
class ShadowBook(private val emit: (String, Map<String, Any?>) -> Unit) {
    private data class Trade(val key: String, val o: OrderRequest, val side: Side, val entry: Double,
        val sl: Double, val tp: Double, val at: Long, val currency: String)
    private val open = mutableListOf<Trade>()
    private val seen = LinkedHashSet<String>()
    private val stats = mutableMapOf<String, Pair<Int, Double>>()
    private var lastQuote = 0L
    @Synchronized fun candidate(o: OrderRequest, q: ScreenQuote?, now: Long) {
        if (o.reduceOnly || o.clientOrderId in seen || q?.instrument != o.symbol || !q.isFresh(now, 3000)) return
        val bid = q.bid ?: return; val ask = q.ask ?: return
        val distance = abs(o.referencePrice - o.stopPrice)
        if (!bid.isFinite() || !ask.isFinite() || bid <= 0 || ask < bid || !distance.isFinite() || distance <= 0 ||
            o.quantity <= 0 || !o.quantity.isFinite() || o.pointValue <= 0 || !o.pointValue.isFinite()) return
        seen.add(o.clientOrderId); if (seen.size > 2048) seen.remove(seen.first())
        for (variant in listOf("ZGODNY", "ODWROTNY", "ZGODNY_2R")) {
            val group = o.reason.substringBefore("[").take(32).replace(Regex("[0-9.,]+"), "#")
            val key = "${o.symbol}|$group|$variant|${q.accountCurrency ?: "?"}"
            if (open.any { it.key == key } || open.size >= 30) continue
            val side = if (variant == "ODWROTNY") o.side.opposite() else o.side
            val entry = if (side == Side.BUY) ask else bid
            val target = if (variant == "ZGODNY_2R") 2 * distance else o.takeProfit?.let { abs(it-o.referencePrice) } ?: 2 * distance
            val t = Trade(key, o, side, entry, entry-side.sign*distance, entry+side.sign*target, now, q.accountCurrency ?: "?")
            open.add(t)
            emit("shadow_open", mapOf("id" to o.clientOrderId, "variant" to key, "entry" to entry, "sl" to t.sl, "tp" to t.tp))
        }
    }
    @Synchronized fun quote(q: ScreenQuote, now: Long) {
        val bid = q.bid ?: return; val ask = q.ask ?: return
        if (!q.isFresh(now, 3000) || q.timestamp <= lastQuote || !bid.isFinite() || !ask.isFinite() || bid <= 0 || ask < bid) return
        lastQuote = q.timestamp
        val iterator = open.iterator()
        while (iterator.hasNext()) {
            val t = iterator.next(); if (q.instrument != t.o.symbol) continue
            val exit = if (t.side == Side.BUY) bid else ask
            val reason = when {
                (exit-t.sl)*t.side.sign <= 0 -> "SL"
                (exit-t.tp)*t.side.sign >= 0 -> "TP"
                now-t.at >= 300_000 -> "TIME_5M"
                else -> continue
            }
            val pnl = (exit-t.entry)*t.side.sign*t.o.quantity*t.o.pointValue
            val old = stats[t.key] ?: (0 to 0.0)
            stats[t.key] = old.first+1 to old.second+pnl
            emit("shadow_close", mapOf("id" to t.o.clientOrderId, "variant" to t.key, "exit" to exit, "pnl_estimate" to pnl, "reason" to reason, "currency" to t.currency))
            iterator.remove()
        }
    }
    @Synchronized fun summary(): String = "PAPER XTB — bieżąca sesja, ${open.size} otwartych.\n" +
        "Spread uwzględniony; bez prowizji, finansowania i rzeczywistego poślizgu. Luki odczytu mogą pomijać SL/TP.\n" +
        stats.entries.joinToString("\n") { (k,v) -> "$k: ${v.first} trans., %.2f".format(v.second) }
}
