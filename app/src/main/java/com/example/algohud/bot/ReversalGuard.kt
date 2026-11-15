package com.example.algohud.bot

import com.example.algohud.core.Bar
import com.example.algohud.core.Side

/**
 * Wyjście z pozycji przy POTWIERDZONEJ tendencji przeciwnej (dla LONG – spadkowej, dla SHORT – wzrostowej).
 *
 * Ocena raz na zamkniętą świecę 1m. Warunki przeciwne:
 *  a) STRUKTURA: świeca zamknęła się pod dołkiem (LONG) / nad szczytem (SHORT) poprzednich 10 świec 1m (BOS/CHoCH),
 *  b) MOMENTUM: 2 ostatnie świece zamknięte po złej stronie EMA20(1m), EMA20 nachylona przeciw i MACD przeciw,
 *  c) KOMPAS: [TrendScore] przeskoczył na stronę przeciwną (≥ [SCORE_AGAINST]).
 *
 * Zamknięcie, gdy jednocześnie:
 *  • spełnione są ≥ 2 z 3 warunków na [CONFIRM_BARS] kolejnych zamkniętych świecach,
 *  • od wejścia minęły ≥ [MIN_BARS_IN_TRADE] pełne świece (nie reagujemy na szum zaraz po wejściu),
 *  • cena cofnęła się od najlepszego punktu o ≥ [MIN_RETRACE_ATR]×ATR (pojedyncze szarpnięcie to nie odwrócenie).
 * Pojedyncza świeca przeciw nigdy nie zamyka pozycji – stratę w szumie dalej zamyka SL w XTB.
 *
 * Świeca impulsowa przeciw pozycji (≥ [IMPULSE_ATR]×ATR): nie zamykamy na jej zamknięciu (po wystrzale
 * często przychodzi cofnięcie – test 12.11, 19:13). Czekamy na kolejną świecę; jeśli cofnie impuls o ≥ 50%,
 * licznik potwierdzeń się zeruje i pozycja zostaje.
 */
class ReversalGuard {

    data class Verdict(
        /** true = zamknij pozycję. */
        val exit: Boolean,
        /** Ile z 3 warunków jest spełnionych na ostatniej świecy. */
        val met: Int,
        /** Ile kolejnych świec z ≥ 2 warunkami. */
        val streak: Int,
        val reason: String
    )

    private var key: String? = null
    private var lastBar = 0L
    private var streak = 0
    private var last: Verdict? = null
    private var deferredBar = -1L

    /**
     * @param positionKey stały identyfikator pozycji (np. czas otwarcia) – zmiana zeruje licznik potwierdzeń.
     * @param m1 zamknięte świece 1m (bez bieżącej).
     * @param best najlepsza cena od wejścia (dla LONG – najwyższa).
     */
    fun evaluate(
        positionKey: String,
        side: Side,
        openedAt: Long,
        best: Double,
        m1: List<Bar>,
        score: TrendScore.Score?
    ): Verdict? {
        if (key != positionKey) { key = positionKey; lastBar = 0L; streak = 0; last = null; deferredBar = -1L }
        val bar = m1.lastOrNull() ?: return null
        if (bar.start == lastBar) return last
        if (bar.start < lastBar) {
            streak = 0; deferredBar = -1L
            last = Verdict(false, 0, 0, "nieaktualna świeca – brak potwierdzenia zwrotu")
            return last
        }
        // Potwierdzenia muszą pochodzić z kolejnych minut, także po przerwie w odczycie.
        if (lastBar != 0L && bar.start - lastBar != M1) {
            streak = 0; deferredBar = -1L
        }
        lastBar = bar.start
        val invalid = m1.takeLast(60).any {
            !it.open.isFinite() || !it.high.isFinite() || !it.low.isFinite() || !it.close.isFinite() ||
                it.low > minOf(it.open, it.close) || it.high < maxOf(it.open, it.close)
        } || m1.takeLast(60).zipWithNext().any { (a, b) -> b.start - a.start != M1 }
        val v = if (invalid || !best.isFinite()) {
            streak = 0; deferredBar = -1L
            Verdict(false, 0, 0, "niepełne lub błędne świece M1 – brak potwierdzenia zwrotu")
        } else judge(side, openedAt, best, m1, score)
        last = v
        return v
    }

    private fun judge(side: Side, openedAt: Long, best: Double, m1: List<Bar>, score: TrendScore.Score?): Verdict {
        val dir = side.sign
        val entryBar = openedAt - openedAt % M1
        val after = m1.count { it.start > entryBar }
        if (after < MIN_BARS_IN_TRADE) return Verdict(false, 0, 0, "za wcześnie po wejściu ($after/$MIN_BARS_IN_TRADE świec)").also { streak = 0 }
        val atr = Indicators.atr(m1.takeLast(60))?.takeIf { it > 0 }
            ?: return Verdict(false, 0, 0, "za mało świec do ATR").also { streak = 0 }
        val closedBar = m1.last()

        val structure = TrendScore.structure(m1) == -dir
        val momentum = momentumAgainst(m1, dir)
        val compass = score != null && score.towards(side) <= -SCORE_AGAINST
        val met = listOf(structure, momentum, compass).count { it }

        val retrace = (best - closedBar.close) * dir / atr
        val names = listOfNotNull(
            if (structure) "struktura ${if (dir > 0) "↓" else "↑"}" else null,
            if (momentum) "momentum przeciw" else null,
            if (compass) "kompas %+.0f".format((score?.value ?: 0.0) * 100) else null
        )
        val prev = if (m1.size >= 2) m1[m1.size - 2] else null
        if (prev != null && EntryFilters.isImpulse(prev, atr, -dir, IMPULSE_ATR)) {
            val retraced = (prev.close - closedBar.close) * (-dir) >= 0.5 * (prev.high - prev.low)
            if (retraced) {
                streak = 0
                return Verdict(false, met, 0, "impuls przeciw pozycji cofnięty o ≥ 50% – trzymam pozycję")
            }
        }
        if (met < 2 || retrace < MIN_RETRACE_ATR) {
            streak = 0
            val why = when {
                met < 2 -> "brak potwierdzonego zwrotu ($met/3 sygnałów odwrócenia${if (names.isNotEmpty()) ": ${names.joinToString()}" else ""})"
                else -> "sygnały przeciw, ale cofnięcie tylko %.1f×ATR < %.1f×ATR – szum".format(retrace, MIN_RETRACE_ATR)
            }
            return Verdict(false, met, 0, why)
        }
        streak++
        val text = "${names.joinToString(" + ")}, cofnięcie %.1f×ATR".format(retrace)
        // Odroczenie tylko raz: seria impulsów jedna po drugiej to już prawdziwe odwrócenie, nie pojedynczy wystrzał.
        if (EntryFilters.isImpulse(closedBar, atr, -dir, IMPULSE_ATR) && deferredBar != prev?.start) {
            deferredBar = closedBar.start
            streak = minOf(streak, CONFIRM_BARS - 1)
            return Verdict(false, met, streak, "świeca impulsowa przeciw pozycji – nie zamykam na jej szczycie, czekam na następną ($text)")
        }
        return if (streak >= CONFIRM_BARS) Verdict(true, met, streak, "odwrócenie trendu potwierdzone: $text")
        else Verdict(false, met, streak, "możliwe odwrócenie ($streak/$CONFIRM_BARS świec): $text")
    }

    /** 2 ostatnie świece zamknięte po złej stronie EMA20, EMA20 nachylona przeciw pozycji, histogram MACD przeciw. */
    private fun momentumAgainst(m1: List<Bar>, dir: Int): Boolean {
        val closes = m1.map { it.close }
        val ema = Indicators.emaSeries(closes, 20)
        if (ema.size < 4) return false
        val lastTwo = m1.takeLast(2)
        val emaTwo = ema.takeLast(2)
        val wrongSide = lastTwo.indices.all { (lastTwo[it].close - emaTwo[it]) * dir < 0 }
        val slopeAgainst = (ema.last() - ema[ema.size - 4]) * dir < 0
        val macdAgainst = Indicators.macdHistogram(closes).lastOrNull()?.let { it * dir < 0 } ?: false
        return wrongSide && slopeAgainst && macdAgainst
    }

    companion object {
        const val M1 = 60_000L
        const val MIN_BARS_IN_TRADE = 2
        const val CONFIRM_BARS = 2
        const val MIN_RETRACE_ATR = 0.5
        const val SCORE_AGAINST = 0.35
        const val IMPULSE_ATR = 1.5
    }
}
