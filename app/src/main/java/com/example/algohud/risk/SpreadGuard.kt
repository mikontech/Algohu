package com.example.algohud.risk

import com.example.algohud.core.OrderRequest
import com.example.algohud.core.ScreenQuote
import kotlin.math.abs

data class SpreadCost(val amount: Double?, val percent: Double?, val currency: String, val block: String?)
object SpreadGuard {
    fun assess(order: OrderRequest, quote: ScreenQuote?, maxPercent: Double, now: Long): SpreadCost {
        if (order.reduceOnly) return SpreadCost(null, null, "", null)
        val q = quote?.takeIf { it.instrument == order.symbol && now - it.timestamp in 0..3000 }
        val bid = q?.bid; val ask = q?.ask
        val currency = q?.accountCurrency ?: "wal. konta"
        if (bid == null || ask == null || !bid.isFinite() || !ask.isFinite() || bid <= 0 || ask < bid)
            return SpreadCost(null, null, currency, "Brak świeżego spreadu XTB")
        val distance = abs(order.referencePrice - order.stopPrice)
        if (!distance.isFinite() || distance <= 0 || !order.pointValue.isFinite() || order.pointValue <= 0)
            return SpreadCost(null, null, currency, "Brak danych ryzyka")
        val amount = (ask - bid) * order.quantity * order.pointValue
        val percent = (ask - bid) / distance * 100
        val limit = maxPercent.takeIf { it.isFinite() && it > 0 } ?: 25.0
        return SpreadCost(amount, percent, currency, if (percent > limit) "Spread %.1f%% > limit %.1f%% ryzyka".format(percent, limit) else null)
    }
}
