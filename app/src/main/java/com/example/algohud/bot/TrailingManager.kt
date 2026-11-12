package com.example.algohud.bot

import com.example.algohud.core.Bar
import kotlin.math.abs
import kotlin.math.max

/**
 * Prowadzenie SL i TP w otwartej pozycji (czysta logika – co ustawić; klikanie w XTB robi warstwa Accessibility).
 *  • zysk ≥ 1R → SL na wejście + spread (break-even),
 *  • zysk ≥ 1,5R → SL za ostatni swing low/high albo k×ATR za najlepszą ceną,
 *  • silny impuls w stronę pozycji → TP dalej (kolejna strefa płynności albo +x×ATR),
 *  • SL nigdy nie cofa się w stronę straty, TP nigdy nie jest przybliżany.
 * Progi: [BotConfig.Trailing].
 */
object TrailingManager {
    data class Input(
        /** +1 long, −1 short. */
        val dir: Int,
        val entry: Double,
        val initialStop: Double,
        val stop: Double,
        val takeProfit: Double?,
        val price: Double,
        /** Najlepsza cena od wejścia. */
        val best: Double,
        val spread: Double,
        val atrM1: Double,
        val atrM5: Double,
        /** Zamknięte świece M1. */
        val m1: List<Bar>,
        /** Trwająca świeca M1 (może być null). */
        val current: Bar?,
        val openedAt: Long,
        /** Aktywność ticków bieżącej świecy względem średniej (1.0 = normalnie). */
        val tickRatio: Double = 1.0,
        /** Strefy płynności (PDH/PDL, Asia H/L, swingi 1h) – kandydaci na nowy TP. */
        val liquidity: List<Double> = emptyList()
    )

    data class Plan(
        val stop: Double? = null,
        val stopWhy: String = "",
        val takeProfit: Double? = null,
        val tpWhy: String = "",
        val impulse: Boolean = false
    ) {
        val isEmpty: Boolean get() = stop == null && takeProfit == null
    }

    fun plan(i: Input): Plan {
        val c = BotConfig.Trailing
        val r = abs(i.entry - i.initialStop)
        if (r <= 0 || i.atrM1 <= 0) return Plan()
        val dir = i.dir
        val bestR = (i.best - i.entry) * dir / r

        // ── SL ──
        var stop: Double? = null
        var stopWhy = ""
        if (bestR >= c.BE_AT_R) {
            var cand = i.entry + dir * max(i.spread, 0.05)
            var why = "break-even po +%.1fR".format(bestR)
            if (bestR >= c.TRAIL_FROM_R) {
                val swing = lastSwing(i)?.let { it - dir * c.SWING_BUFFER_ATR * i.atrM1 }
                val atr = i.best - dir * c.ATR_K * i.atrM1
                val trail: Pair<Double, String>? = when (c.MODE) {
                    BotConfig.Trailing.Mode.SWING -> swing?.let { it to "za ostatni swing" } ?: (atr to "%.1f×ATR za ceną".format(c.ATR_K))
                    BotConfig.Trailing.Mode.ATR -> atr to "%.1f×ATR za ceną".format(c.ATR_K)
                    BotConfig.Trailing.Mode.TIGHTER ->
                        if (swing != null && (swing - atr) * dir > 0) swing to "za ostatni swing" else atr to "%.1f×ATR za ceną".format(c.ATR_K)
                }
                if (trail != null && (trail.first - cand) * dir > 0) { cand = trail.first; why = "trailing ${trail.second}" }
            }
            // Nie bliżej ceny niż minimalny odstęp i nigdy w stronę straty.
            val gap = max(2 * i.spread, c.MIN_GAP_ATR * i.atrM1)
            val limit = i.price - dir * gap
            if ((cand - limit) * dir > 0) cand = limit
            if ((cand - i.stop) * dir > 1e-9) { stop = cand; stopWhy = why }
        }

        // ── TP ──
        val imp = impulse(i)
        var tp: Double? = null
        var tpWhy = ""
        val cur = i.takeProfit
        if (imp && cur != null && bestR >= c.TP_EXTEND_FROM_R && i.atrM5 > 0) {
            val level = i.liquidity.filter { (it - cur) * dir > 0.1 * i.atrM5 && abs(it - cur) <= c.TP_LEVEL_MAX_ATR * i.atrM5 }
                .minByOrNull { abs(it - cur) }
            val cand = level?.let { it - dir * 0.1 * i.atrM5 } ?: (cur + dir * c.TP_EXTEND_ATR * i.atrM5)
            if ((cand - cur) * dir > 1e-9) {
                tp = cand
                tpWhy = if (level != null) "impuls – TP do kolejnej strefy płynności" else "impuls – TP dalej o %.1f×ATR".format(c.TP_EXTEND_ATR)
            }
        }
        return Plan(stop, stopWhy, tp, tpWhy, imp)
    }

    /** Ostatni potwierdzony swing low (long) / high (short) po wejściu, leżący po stronie zysku. */
    private fun lastSwing(i: Input): Double? {
        val w = i.m1.takeLast(120)
        return Structure.swings(w).lastOrNull { s ->
            s.high == (i.dir < 0) && w[s.index].start >= i.openedAt && (s.price - i.entry) * i.dir > 0 && (i.price - s.price) * i.dir > 0
        }?.price
    }

    /**
     * Silny impuls w stronę pozycji: świeca M1 (trwająca albo ostatnia zamknięta) o zasięgu ≥ IMPULSE_ATR × ATR
     * i dużym korpusie, potwierdzona rosnącą aktywnością ticków, rosnącym histogramem MACD albo wybiciem struktury.
     */
    fun impulse(i: Input): Boolean {
        val c = BotConfig.Trailing
        val closed = i.m1
        val cands = listOfNotNull(i.current, closed.lastOrNull())
        val bar = cands.firstOrNull { b ->
            val range = b.high - b.low
            range >= c.IMPULSE_ATR * i.atrM1 && (b.close - b.open) * i.dir >= c.IMPULSE_BODY * range
        } ?: return false
        val before = closed.filter { it.start < bar.start }.takeLast(c.IMPULSE_BREAK_BARS)
        val breaks = before.size >= 3 && (if (i.dir > 0) bar.close > before.maxOf { it.high } else bar.close < before.minOf { it.low })
        val hist = Indicators.macdHist(closed.takeLast(250).map { it.close })
        val momentum = hist.size >= 2 && hist.last() * i.dir > 0 && abs(hist.last()) > abs(hist[hist.size - 2])
        return breaks || momentum || i.tickRatio >= c.IMPULSE_TICK_RATIO
    }
}

/**
 * Synchronizacja SL/TP z XTB: co i kiedy wysłać do otwartej pozycji, z limitem częstotliwości, ponawianiem
 * po błędzie i historią przesunięć. Czysta logika – samą edycję pozycji wykonuje AccessibilityService.
 * Ceny w skali księgi bota (feed); przeliczenie na ceny XTB robi wywołujący.
 */
class ProtectionSync(
    private val dir: Int,
    brokerStop: Double,
    brokerTp: Double?,
    private val minIntervalMs: Long = BotConfig.Trailing.MODIFY_MIN_INTERVAL_MS,
    private val retryMs: Long = 15_000L,
    private val maxFailures: Int = BotConfig.Trailing.MAX_FAILURES_BEFORE_ALERT
) {
    data class Move(val at: Long, val what: String, val from: Double?, val to: Double, val why: String)

    /** [setStop]/[setTp] – co wpisać (null = bez zmiany); [expectStop]/[expectTp] – co ma być przy pozycji po zmianie. */
    data class Request(val setStop: Double?, val setTp: Double?, val expectStop: Double, val expectTp: Double?, val stopWhy: String, val tpWhy: String)

    /** SL/TP ostatnio POTWIERDZONE w XTB. */
    var brokerStop: Double = brokerStop; private set
    var brokerTp: Double? = brokerTp; private set
    var failures = 0; private set
    var lastError: String? = null; private set
    val history = ArrayList<Move>()
    private var lastOkAt = Long.MIN_VALUE / 2
    private var lastFailAt = Long.MIN_VALUE / 2
    var busy = false

    /** true = po kolejnych błędach trzeba pokazać alert w HUD. */
    val alert: Boolean get() = failures >= maxFailures

    /**
     * Co wysłać teraz do XTB; null = nic (brak zmiany ≥ [minStep], trwa inna modyfikacja albo limit częstotliwości).
     * SL wysyłany tylko w stronę zysku, TP tylko dalej od ceny.
     */
    fun next(now: Long, wantStop: Double, wantTp: Double?, minStep: Double, stopWhy: String = "", tpWhy: String = ""): Request? {
        if (busy) return null
        val slMove = (wantStop - brokerStop) * dir >= minStep
        val bt = brokerTp
        val tpMove = wantTp != null && (bt == null || (wantTp - bt) * dir >= minStep)
        if (!slMove && !tpMove) return null
        if (now - lastOkAt < minIntervalMs) return null
        // Ponowienie po błędzie: 15 s, potem coraz rzadziej (30 s, 60 s… maks. 5 min), żeby nie spamować XTB.
        if (failures > 0 && now - lastFailAt < minOf(retryMs shl minOf(failures - 1, 5), 300_000L)) return null
        return Request(
            setStop = if (slMove) wantStop else null, setTp = if (tpMove) wantTp else null,
            expectStop = if (slMove) wantStop else brokerStop, expectTp = if (tpMove) wantTp else bt,
            stopWhy = stopWhy, tpWhy = tpWhy
        )
    }

    /** Wynik modyfikacji w XTB (po odczytaniu ekranu). Zwraca nowe wpisy historii (puste przy błędzie). */
    fun onResult(now: Long, req: Request, ok: Boolean, error: String? = null): List<Move> {
        busy = false
        if (!ok) { failures++; lastError = error; lastFailAt = now; return emptyList() }
        failures = 0; lastError = null; lastOkAt = now
        val added = ArrayList<Move>()
        req.setStop?.let { added += Move(now, "SL", brokerStop, it, req.stopWhy); brokerStop = it }
        req.setTp?.let { added += Move(now, "TP", brokerTp, it, req.tpWhy); brokerTp = it }
        history += added
        while (history.size > 30) history.removeAt(0)
        return added
    }

    /** Tryb papierowy: przesunięcie bez XTB – tylko do historii. */
    fun recordPaper(now: Long, stop: Double?, tp: Double?, stopWhy: String, tpWhy: String): List<Move> {
        val added = ArrayList<Move>()
        if (stop != null && (stop - brokerStop) * dir > 1e-9) { added += Move(now, "SL", brokerStop, stop, stopWhy); brokerStop = stop }
        val bt = brokerTp
        if (tp != null && (bt == null || (tp - bt) * dir > 1e-9)) { added += Move(now, "TP", bt, tp, tpWhy); brokerTp = tp }
        history += added
        while (history.size > 30) history.removeAt(0)
        return added
    }
}
