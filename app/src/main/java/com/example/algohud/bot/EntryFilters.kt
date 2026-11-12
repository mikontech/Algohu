package com.example.algohud.bot

import com.example.algohud.core.Bar
import kotlin.math.abs

/**
 * Filtry wejść wynikające z testów na żywo (12.11.2026):
 *  • [reversalConfirmed] – wejście mimo przeciwnego trendu 5m, gdy 1m potwierdza odwrócenie, a trend 5m słabnie
 *    (bot przepuszczał wyraźne odbicia, bo EMA 5m reagują z opóźnieniem),
 *  • [rangeFadeBlock] – ZAKRES nie gra przeciw silnemu impulsowi 1m (short na szczycie wystrzału),
 *  • [isConsolidation] – rynek w konsolidacji (do wyłączenia wyjścia przy odwróceniu).
 * Czysta logika – bez Androida, testowalna.
 */
object EntryFilters {

    const val REV_EMA_BARS = 3          // tyle ostatnich świec 1m musi zamknąć się za EMA20
    const val REV_BOS_WITHIN = 5        // przebicie struktury w ciągu ostatnich N świec
    const val FADE_RSI = 65.0           // RSI ≥ 65 (≤ 35) = silny impuls w górę (w dół)
    const val REJECTION_WICK = 0.5      // świeca odrzucenia: knot ≥ 50% zasięgu

    /**
     * Potwierdzone odwrócenie na 1m w kierunku [dir] (+1 long, −1 short) przy słabnącym trendzie 5m.
     * Null = brak potwierdzenia; inaczej opis do HUD.
     */
    fun reversalConfirmed(m1: List<Bar>, m5: List<Bar>, dir: Int): String? {
        if (m1.size < 40 || m5.size < 25) return null
        val closes = m1.map { it.close }
        val ema = Indicators.emaSeries(closes, 20)
        if (ema.size < REV_EMA_BARS + 3) return null
        val lastBars = m1.takeLast(REV_EMA_BARS)
        val lastEma = ema.takeLast(REV_EMA_BARS)
        val beyondEma = lastBars.indices.all { (lastBars[it].close - lastEma[it]) * dir > 0 }
        val emaTurns = (ema.last() - ema[ema.size - 4]) * dir > 0
        val macdOk = Indicators.macdHistogram(closes).lastOrNull()?.let { it * dir > 0 } ?: false
        val bos = (0 until REV_BOS_WITHIN).any { k -> TrendScore.structure(m1.subList(0, m1.size - k)) == dir }
        val e5 = Indicators.emaSeries(m5.map { it.close }, 20)
        val weakening = e5.size >= 3 && (e5.last() - e5[e5.size - 3]) * dir >= 0
        return if (beyondEma && emaTurns && macdOk && bos && weakening)
            "🔁 odwrócenie ${if (dir > 0) "↑" else "↓"} potwierdzone na 1m (BOS, $REV_EMA_BARS świece za EMA20, MACD), trend 5m słabnie"
        else null
    }

    /**
     * ZAKRES: wejście w kierunku [dir] przeciw silnemu impulsowi 1m jest blokowane,
     * chyba że ostatnia świeca to świeca odrzucenia (długi knot w stronę impulsu, zamknięcie w stronę wejścia).
     */
    fun rangeFadeBlock(m1: List<Bar>, dir: Int): String? {
        if (m1.size < 40) return null
        val closes = m1.map { it.close }
        val rsi = Indicators.rsi(closes) ?: return null
        val hist = Indicators.macdHistogram(closes).lastOrNull() ?: return null
        val against = -dir
        val strong = (if (against > 0) rsi >= FADE_RSI else rsi <= 100 - FADE_RSI) && hist * against > 0
        if (!strong) return null
        val l = m1.last()
        val rng = l.high - l.low
        val wick = if (against > 0) l.high - maxOf(l.open, l.close) else minOf(l.open, l.close) - l.low
        val rejection = rng > 0 && wick >= REJECTION_WICK * rng && (l.close - l.open) * dir >= 0
        if (rejection) return null
        return "silny impuls %s na 1m (RSI %.0f, MACD %s) – nie gram przeciw, czekam na świecę odrzucenia".format(
            if (against > 0) "↑" else "↓", rsi, if (hist > 0) "+" else "−"
        )
    }

    /** Konsolidacja M5: zakres ostatniej godziny (12 świec 5m) < 3×ATR5 – ta sama miara co w kontekście SMC. */
    fun isConsolidation(m5: List<Bar>): Boolean {
        val w = m5.takeLast(12).takeIf { it.size == 12 } ?: return false
        val atr5 = Indicators.atr(m5.takeLast(60))?.takeIf { it > 0 } ?: return false
        return w.maxOf { it.high } - w.minOf { it.low } < 3 * atr5
    }

    /** Świeca impulsowa: zasięg ≥ [mult]×ATR i korpus w kierunku [dir]. */
    fun isImpulse(bar: Bar, atr: Double, dir: Int, mult: Double = 1.5): Boolean =
        bar.high - bar.low >= mult * atr && (bar.close - bar.open) * dir > 0 && abs(bar.close - bar.open) > 0
}
