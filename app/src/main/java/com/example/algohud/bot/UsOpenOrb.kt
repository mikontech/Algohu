package com.example.algohud.bot

import com.example.algohud.core.Bar
import com.example.algohud.core.Side
import kotlin.math.abs

/**
 * Handel przy otwarciu sesji kasowej w USA (9:30 ET) – Opening Range Breakout.
 *
 *  • Zakres otwarcia = High/Low świec 1m z pierwszych [RANGE_MIN] min po 9:30 ET (odtwarzany ze świec – przetrwa restart).
 *  • Pierwsze [SPIKE_MIN] min – bez wejść (największy spread i poślizg).
 *  • Wejście po ZAMKNIĘCIU świecy 1m poza zakresem (+ bufor 0,1×ATR) między [RANGE_MIN] a [ACTIVE_MIN] min od otwarcia.
 *  • Kierunek musi zgadzać się z [TrendScore] (nie gramy wybicia pod prąd silnego kompasu).
 *  • SL w środku zakresu; gdy to dalej niż limit 15–20 PLN – setup pomijany („zakres za szeroki”),
 *    zamiast przysuwać SL tam, gdzie rynek go łatwo zbierze.
 *  • Jedno wejście na otwarcie.
 */
class UsOpenOrb {

    data class Range(val openMs: Long, val high: Double, val low: Double) {
        val mid get() = (high + low) / 2
        val width get() = high - low
    }

    data class Setup(val side: Side, val stop: Double, val reason: String, val key: String)

    data class Result(val status: UsSession.Status, val range: Range?, val setup: Setup?, val note: String)

    private var firedForOpen = 0L

    fun markTaken(openMs: Long) { firedForOpen = openMs }

    /**
     * @param m1 zamknięte świece 1m.
     * @param maxStopDist maks. odległość SL w cenie (z limitu 20 PLN przy stałym locie); null = nieznana wartość kontraktu.
     */
    fun evaluate(
        m1: List<Bar>,
        price: Double,
        nowMs: Long,
        maxStopDist: Double?,
        score: TrendScore.Score?
    ): Result {
        val st = UsSession.status(nowMs)
        val open = st.openMs
        if (open == null || st.phase == UsSession.Phase.OFF || st.phase == UsSession.Phase.HOLIDAY) {
            return Result(st, null, null, st.phase.label)
        }
        if (st.phase == UsSession.Phase.PRE) return Result(st, null, null, st.phase.label)
        val rangeEnd = open + RANGE_MIN * 60_000L
        val inRange = m1.filter { it.start >= open && it.start < rangeEnd }
        val partial = if (inRange.isEmpty()) null else Range(open, inRange.maxOf { it.high }, inRange.minOf { it.low })
        if (st.phase == UsSession.Phase.SPIKE || st.phase == UsSession.Phase.RANGE) {
            return Result(st, partial, null, "${st.phase.label} (${st.minutesFromOpen ?: 0}/$RANGE_MIN min)")
        }
        // ACTIVE
        if (inRange.size < RANGE_MIN - 1) return Result(st, partial, null, "brak pełnego zakresu otwarcia (${inRange.size}/$RANGE_MIN świec 1m)")
        val range = partial ?: return Result(st, null, null, "brak świec zakresu otwarcia")
        if (firedForOpen == open) return Result(st, range, null, "wejście z otwarcia USA już wykorzystane dziś")
        val atr = Indicators.atr(m1.takeLast(60))?.takeIf { it > 0 } ?: return Result(st, range, null, "za mało świec do ATR")
        val last = m1.lastOrNull()?.takeIf { it.start >= rangeEnd }
            ?: return Result(st, range, null, "czekam na pierwszą świecę po zakresie")
        val buf = BREAK_BUFFER_ATR * atr
        val dir = when {
            last.close > range.high + buf -> 1
            last.close < range.low - buf -> -1
            else -> return Result(st, range, null, "czekam na zamknięcie świecy 1m > %.2f albo < %.2f".format(range.high + buf, range.low - buf))
        }
        val side = if (dir > 0) Side.BUY else Side.SELL
        TrendScore.entryBlock(score, side)?.let { return Result(st, range, null, "wybicie ${if (dir > 0) "↑" else "↓"} odpuszczone: $it") }
        // Za daleko od zakresu = gonienie ruchu.
        val edge = if (dir > 0) range.high else range.low
        if ((price - edge) * dir > MAX_CHASE_ATR * atr) {
            return Result(st, range, null, "cena już %.1f×ATR od zakresu – nie gonię".format((price - edge) * dir / atr))
        }
        val stop = range.mid
        if (maxStopDist != null && abs(price - stop) > maxStopDist) {
            return Result(st, range, null, "zakres za szeroki: SL w środku zakresu %.2f dalej niż limit %.2f – pomijam".format(abs(price - stop), maxStopDist))
        }
        val reason = "US OPEN wybicie zakresu %.2f–%.2f %s (otwarcie %s PL)".format(
            range.low, range.high, if (dir > 0) "↑" else "↓", UsSession.openInPoland(nowMs) ?: "?"
        )
        return Result(st, range, Setup(side, stop, reason, "usopen:$open"), "WYBICIE – wchodzę")
    }

    companion object {
        const val PRE_MIN = 5L
        const val SPIKE_MIN = 2L
        const val RANGE_MIN = 15L
        const val ACTIVE_MIN = 75L
        const val BREAK_BUFFER_ATR = 0.1
        const val MAX_CHASE_ATR = 1.5
    }
}
