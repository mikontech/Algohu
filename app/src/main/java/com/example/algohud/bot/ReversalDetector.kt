package com.example.algohud.bot

import com.example.algohud.core.Bar
import kotlin.math.abs

/**
 * Wykrywa POTWIERDZONE odwrócenie tendencji przeciw otwartej pozycji (dla longa: spadkowe, dla shorta: wzrostowe).
 * Potwierdzenie = co najmniej [BotConfig.Reversal.MIN_CONDITIONS] z 3 warunków naraz:
 *   a) BOS/CHoCH przeciw pozycji na interwale wejścia (wybicie po wejściu, nieodwrócone),
 *   b) zamknięcie po drugiej stronie EMA 20 i EMA 50 z rosnącym przeciwnym momentum (histogram MACD),
 *   c) wynik SignalEngine strony przeciwnej powyżej progu i z przewagą,
 * utrzymane przez [BotConfig.Reversal.CONFIRM_BARS] kolejnych ZAMKNIĘTYCH świec, a do tego cena musi być
 * co najmniej [BotConfig.Reversal.MIN_MOVE_ATR] × ATR od najlepszej ceny pozycji. Pojedyncza świeca cofnięcia
 * ani szum poniżej progu nigdy nie zamykają pozycji.
 * Jedna instancja na pozycję; [reset] po zamknięciu. Czysta logika – bez Androida.
 */
class ReversalDetector {
    data class Verdict(
        /** true = zamknij pozycję teraz. */
        val close: Boolean,
        /** Spełnione warunki (opisy). */
        val met: List<String>,
        /** Ile kolejnych zamkniętych świec warunki się utrzymują. */
        val streak: Int,
        val note: String
    ) {
        /** Powód zamknięcia do HUD / dziennika. */
        val reason: String get() = "ODWRÓCENIE TENDENCJI: " + met.joinToString(" + ") + " przez $streak świece"
    }

    private var streak = 0
    /** Zamknięcie pierwszej świecy bieżącej serii – do wykrycia odbicia. */
    private var streakStart = Double.NaN
    private var lastBar = Long.MIN_VALUE
    private var last = Verdict(false, emptyList(), 0, "")

    fun reset() { streak = 0; streakStart = Double.NaN; lastBar = Long.MIN_VALUE; last = Verdict(false, emptyList(), 0, "") }

    /**
     * Wywołuj po zamknięciu świecy interwału wejścia (ponowne wywołanie dla tej samej świecy zwraca poprzedni wynik).
     * @param dir +1 long, −1 short
     * @param best najlepsza cena od wejścia (dla longa najwyższa)
     * @param bars zamknięte świece interwału wejścia (M1 albo M5), [periodMs] – ich długość
     * @param signal bieżący wynik SignalEngine (warunek c); null = warunek niespełniony
     * @param noiseAtr ATR do progu szumu
     */
    fun onBarClosed(
        dir: Int, openedAt: Long, best: Double, price: Double,
        bars: List<Bar>, periodMs: Long, signal: SignalEngine.Result?, noiseAtr: Double
    ): Verdict {
        val c = BotConfig.Reversal
        val bar = bars.lastOrNull() ?: return last
        if (bar.start == lastBar) return last
        lastBar = bar.start
        val inTrade = bars.count { it.start >= openedAt }
        if (inTrade < c.MIN_BARS_IN_TRADE) {
            streak = 0
            last = Verdict(false, emptyList(), 0, "za wcześnie na ocenę odwrócenia ($inTrade/${c.MIN_BARS_IN_TRADE} świec od wejścia)")
            return last
        }
        val met = ArrayList<String>()
        val against = if (dir > 0) "w dół" else "w górę"

        // a) wybicie struktury przeciw pozycji, które nastąpiło po wejściu i nie zostało odwrócone nowszym wybiciem
        val w = bars.takeLast(150)
        Structure.analyze(w).lastEvent?.let { ev ->
            if (ev.dir == -dir && w[ev.index].start + periodMs > openedAt) met += (if (ev.choch) "CHoCH" else "BOS") + " $against"
        }

        // b) zamknięcie po drugiej stronie EMA 20 i 50 + rosnące przeciwne momentum
        val closes = bars.takeLast(250).map { it.close }
        val fast = Indicators.ema(closes, c.EMA_FAST)
        val slow = Indicators.ema(closes, c.EMA_SLOW)
        val hist = Indicators.macdHist(closes)
        if (fast != null && slow != null && hist.size >= 2) {
            val beyond = (bar.close - fast) * dir < 0 && (bar.close - slow) * dir < 0
            val h = hist.last()
            val growing = h * dir < 0 && abs(h) > abs(hist[hist.size - 2])
            if (beyond && growing) met += "zamknięcie ${if (dir > 0) "pod" else "nad"} EMA${c.EMA_FAST}/${c.EMA_SLOW} z rosnącym momentum $against"
        }

        // c) score SignalEngine po stronie przeciwnej
        if (signal != null && signal.blocked == null) {
            val opp = signal.score(-dir)
            val mine = signal.score(dir)
            if (opp >= c.SCORE && opp - mine >= c.SCORE_LEAD) met += "score ${if (dir > 0) "SHORT" else "LONG"} %.1f vs %.1f".format(opp, mine)
        }

        if (met.size >= c.MIN_CONDITIONS) {
            if (streak == 0) streakStart = bar.close
            streak++
        } else streak = 0
        val move = (best - price) * dir
        val need = c.MIN_MOVE_ATR * noiseAtr
        // Odbicie: świeca potwierdzająca zamyka się wyraźnie lepiej dla pozycji niż pierwsza świeca serii –
        // to wygląda na cofnięcie, które już wraca, a nie na trwały ruch przeciwny. Czekamy dalej.
        val bouncing = streak >= 2 && (bar.close - streakStart) * dir > c.MAX_BOUNCE_ATR * noiseAtr
        val close = streak >= c.CONFIRM_BARS && move >= need && !bouncing
        val note = when {
            close -> "odwrócenie potwierdzone"
            bouncing && streak >= c.CONFIRM_BARS && move >= need -> "odwrócenie: warunki są, ale cena już odbija – nie zamykam na cofnięciu"
            met.size < c.MIN_CONDITIONS -> "odwrócenie: ${met.size}/3 warunków" + if (met.isEmpty()) "" else " (${met.joinToString("; ")})"
            streak < c.CONFIRM_BARS -> "odwrócenie: ${met.size}/3 warunków, świeca $streak/${c.CONFIRM_BARS} – czekam na potwierdzenie"
            else -> "odwrócenie: warunki są, ale ruch %.2f < %.2f (%.1f×ATR) – to jeszcze szum".format(move, need, c.MIN_MOVE_ATR)
        }
        last = Verdict(close, met, streak, note)
        return last
    }
}
