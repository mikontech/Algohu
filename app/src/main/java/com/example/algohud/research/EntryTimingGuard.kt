package com.example.algohud.research

import com.example.algohud.core.*
import kotlin.math.abs

object EntryTimingGuard {
    fun check(o: OrderRequest, q: ScreenQuote?, now: Long): String? {
        if (o.reduceOnly) return null
        if (now - o.signalAt !in 0..90_000) return "Sygnał wygasł (90 s)"
        if (q?.instrument != o.symbol || !q.isFresh(now = now, maxAgeMs = 3000)) return "Brak świeżej ceny XTB"
        val bid = q.bid ?: return "Brak bid XTB"
        val ask = q.ask ?: return "Brak ask XTB"
        if (!bid.isFinite() || !ask.isFinite() || bid <= 0 || ask < bid) return "Błędna cena XTB"
        val anchor = o.brokerReferencePrice ?: return "Brak ceny XTB w chwili sygnału"
        val risk = abs(o.referencePrice - o.stopPrice)
        if (!anchor.isFinite() || !risk.isFinite() || risk <= 0) return "Błędne ryzyko sygnału"
        val current = if (o.side == Side.BUY) ask else bid
        if ((current - anchor) * o.side.sign > risk * 0.25) return "Spóźnione wejście: ruch > 0,25R"
        val stop = anchor + o.stopPrice - o.referencePrice
        val riskNow = (current - stop) * o.side.sign
        if (riskNow <= 0) return "Cena przekroczyła planowany SL"
        o.takeProfit?.let {
            val reward = (anchor + it - o.referencePrice - current) * o.side.sign
            // Bid/ask już zawierają spread. Dodatkowy bufor na poślizg: 0,1 spreadu.
            val buffer = (ask - bid) * 0.1
            if ((reward - buffer) / (riskNow + buffer) < 1.2) return "R:R po kosztach < 1,2"
        }
        return null
    }
}
