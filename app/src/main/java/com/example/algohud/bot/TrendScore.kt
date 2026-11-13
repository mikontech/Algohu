package com.example.algohud.bot

import com.example.algohud.core.Bar
import com.example.algohud.core.Side

/**
 * Jeden wspólny „kompas kierunku” dla wszystkich strategii: czy rynek teraz sprzyja LONG, czy SHORT.
 *
 * Wynik [value] w zakresie −1…+1 (−1 = silnie spadkowo, +1 = silnie wzrostowo) to średnia ważona warstw:
 *  • trend 5m  (waga 2): (EMA20 − EMA50) / ATR5, przycięte do ±1,
 *  • nachylenie 1m (waga 1): zmiana EMA20(1m) w 5 świecach / ATR1,
 *  • MACD 1m (waga 1): histogram MACD(12,26,9) / ATR1,
 *  • RSI 1m (waga 1): (RSI − 50) / 20,
 *  • struktura 1m (waga 1): zamknięcie nad szczytem / pod dołkiem poprzednich 10 świec (BOS).
 *
 * Używany do: filtra wejść (nie otwieramy pod prąd silnego wyniku), wyjścia przy odwróceniu ([ReversalGuard])
 * i HUD-a. Czysta logika – bez Androida, testowalna.
 */
object TrendScore {

    data class Score(
        val value: Double,
        val trend5: Double?,
        val slope1: Double,
        val macd1: Double,
        val rsi1: Double,
        val structure1: Int
    ) {
        val side: Side? get() = when {
            value >= ENTRY_THRESHOLD -> Side.BUY
            value <= -ENTRY_THRESHOLD -> Side.SELL
            else -> null
        }

        /** Wynik w stronę pozycji (dodatni = sprzyja, ujemny = przeciw). */
        fun towards(side: Side): Double = value * side.sign

        fun describe(): String {
            val dir = when {
                value >= ENTRY_THRESHOLD -> "LONG"
                value <= -ENTRY_THRESHOLD -> "SHORT"
                else -> "neutralnie"
            }
            val t5 = trend5?.let { "%+.1f".format(it) } ?: "—"
            val st = when (structure1) { 1 -> "BOS↑"; -1 -> "BOS↓"; else -> "w zakresie" }
            return "%+.0f %s (5m %s • nachyl. %+.1f • MACD %+.1f • RSI %.0f • %s)".format(
                value * 100, dir, t5, slope1, macd1, rsi1, st
            )
        }
    }

    const val ENTRY_THRESHOLD = 0.35
    /** Wejście pod prąd wyniku silniejszego niż ten próg jest blokowane. */
    const val AGAINST_BLOCK = 0.45

    private const val W_TREND5 = 2.0
    private const val W_SLOPE = 1.0
    private const val W_MACD = 1.0
    private const val W_RSI = 1.0
    private const val W_STRUCT = 1.0
    private const val STRUCT_LOOKBACK = 10

    fun compute(m1: List<Bar>, m5: List<Bar>): Score? {
        if (m1.size < 40) return null
        val c1 = m1.map { it.close }
        val atr1 = Indicators.atr(m1.takeLast(60))?.takeIf { it > 0 } ?: return null

        val trend5 = if (m5.size >= 55) {
            val c5 = m5.map { it.close }
            val e20 = Indicators.ema(c5, 20)
            val e50 = Indicators.ema(c5, 50)
            val atr5 = Indicators.atr(m5.takeLast(60))
            if (e20 != null && e50 != null && atr5 != null && atr5 > 0) ((e20 - e50) / atr5).coerceIn(-1.0, 1.0) else null
        } else null

        val ema = Indicators.emaSeries(c1, 20)
        val slope = if (ema.size >= 6) ((ema.last() - ema[ema.size - 6]) / atr1).coerceIn(-1.0, 1.0) else 0.0
        val hist = Indicators.macdHistogram(c1).lastOrNull() ?: 0.0
        val macd = (hist / (0.3 * atr1)).coerceIn(-1.0, 1.0)
        val rsi = Indicators.rsi(c1) ?: 50.0
        val rsiN = ((rsi - 50) / 20).coerceIn(-1.0, 1.0)
        val structure = structure(m1)

        var sum = W_SLOPE * slope + W_MACD * macd + W_RSI * rsiN + W_STRUCT * structure
        var w = W_SLOPE + W_MACD + W_RSI + W_STRUCT
        if (trend5 != null) { sum += W_TREND5 * trend5; w += W_TREND5 }
        return Score(sum / w, trend5, slope, macd, rsi, structure)
    }

    /** +1 = ostatnia zamknięta świeca zamknęła się nad szczytem poprzednich 10, −1 = pod dołkiem, 0 = w zakresie. */
    fun structure(m1: List<Bar>, lookback: Int = STRUCT_LOOKBACK): Int {
        if (m1.size < lookback + 1) return 0
        val last = m1.last()
        val prev = m1.subList(m1.size - 1 - lookback, m1.size - 1)
        return when {
            last.close > prev.maxOf { it.high } -> 1
            last.close < prev.minOf { it.low } -> -1
            else -> 0
        }
    }

    /** Null = wejście zgodne z kompasem (albo kompas neutralny); inaczej powód blokady. */
    fun entryBlock(score: Score?, side: Side): String? {
        score ?: return null
        val t = score.towards(side)
        return if (t <= -AGAINST_BLOCK) {
            "kompas kierunku %+.0f przeciw %s – nie wchodzę pod prąd".format(score.value * 100, if (side == Side.BUY) "LONG" else "SHORT")
        } else null
    }
}
