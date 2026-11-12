package com.example.algohud.bot

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Dowód przewagi strategii. Każdy sygnał wejścia (RT, ORB, ZAKRES, SMC) jest prowadzony „na sucho”
 * z tymi samymi poziomami SL/TP i tym samym prowadzeniem stopu co prawdziwa pozycja, po kosztach spreadu.
 * Prawdziwe zlecenie wolno złożyć dopiero wtedy, gdy wyniki strategii „na sucho” pokazują dodatnią,
 * statystycznie istotną przewagę. Bez tego każde wejście kosztuje średnio spread i nic nie zarabia.
 *
 * Klasa nie zależy od Androida – trwałość przez [load]/[save] (zwykły tekst).
 */
class StrategyProof(
    private val load: () -> String? = { null },
    private val save: (String) -> Unit = {},
    private val minTrades: Int = MIN_TRADES,
    private val window: Int = WINDOW,
    private val minAvgR: Double = MIN_AVG_R,
    private val minTStat: Double = MIN_T_STAT,
    private val liveWindow: Int = LIVE_WINDOW,
    private val liveMin: Int = LIVE_MIN
) {
    /**
     * Kiedy strategia może składać prawdziwe zlecenia:
     *  [LIVE] – od razu; ocena na bieżąco: wstrzymana, gdy średnia z ostatnich prób jest ujemna, wraca po poprawie,
     *  [STRICT] – dopiero po udowodnionej przewadze (min. liczba prób, dodatni i istotny wynik),
     *  [OFF] – zawsze (tylko pomiar).
     */
    enum class Policy { LIVE, STRICT, OFF }

    /** Ocena bieżąca: wynik z ostatnich [LIVE_WINDOW] prób. */
    data class Live(val trades: Int, val avgR: Double, val paused: Boolean)

    data class Closed(val strategy: String, val symbol: String, val r: Double, val reason: String, val closedAt: Long)

    data class Verdict(val trades: Int, val avgR: Double, val tStat: Double, val proven: Boolean, val needed: Int) {
        fun line(): String = when {
            trades == 0 -> "0/$needed prób – brak wyników"
            trades < needed -> "%d/%d prób • E=%+.2fR – zbieram wyniki".format(trades, needed, avgR)
            proven -> "%d prób • E=%+.2fR • t=%.1f – przewaga potwierdzona".format(trades, avgR, tStat)
            else -> "%d prób • E=%+.2fR • t=%.1f – brak przewagi po kosztach".format(trades, avgR, tStat)
        }
    }

    private class Shadow(
        val strategy: String, val symbol: String, val dir: Int, val entry: Double,
        val initialStop: Double, var stop: Double, val tp: Double, var best: Double
    )

    private val trials = LinkedHashMap<String, Shadow>()
    private val results = LinkedHashMap<String, ArrayDeque<Closed>>()

    init {
        runCatching {
            load()?.lineSequence()?.forEach { line ->
                val p = line.split(';')
                if (p.size >= 5) add(Closed(p[0], p[1], p[2].toDouble(), p[3], p[4].toLong()))
            }
        }
    }

    /**
     * Otwiera pozycję „na sucho”. [entry] – cena wykonania (ask dla kupna, bid dla sprzedaży).
     * Jedna otwarta próba na strategię i instrument. Zwraca false, gdy próba już trwa albo poziomy są błędne.
     */
    @Synchronized
    fun open(strategy: String, symbol: String, dir: Int, entry: Double, stop: Double, tp: Double): Boolean {
        val key = "$symbol|$strategy"
        if (key in trials) return false
        if ((entry - stop) * dir <= 0 || (tp - entry) * dir <= 0) return false
        trials[key] = Shadow(strategy, symbol, dir, entry, stop, stop, tp, entry)
        return true
    }

    /**
     * Nowa cena środkowa [mid] instrumentu. Zamknięcie następuje po stronie, po której zamyka broker
     * (bid dla pozycji długiej, ask dla krótkiej). Po zysku 1R stop idzie na wejście + spread,
     * dalej 1R za najlepszą ceną – tak samo jak w prawdziwej pozycji.
     */
    @Synchronized
    fun onPrice(symbol: String, mid: Double, spread: Double, ts: Long): List<Closed> {
        if (trials.isEmpty()) return emptyList()
        val out = ArrayList<Closed>()
        val iter = trials.entries.iterator()
        while (iter.hasNext()) {
            val s = iter.next().value
            if (s.symbol != symbol) continue
            val exitPx = mid - s.dir * spread / 2
            val r = abs(s.entry - s.initialStop)
            val level = when {
                (exitPx - s.stop) * s.dir <= 0 -> s.stop
                (exitPx - s.tp) * s.dir >= 0 -> s.tp
                else -> null
            }
            if (level != null) {
                val reason = when {
                    level == s.tp -> "TP"
                    s.stop != s.initialStop -> "SL za zyskiem"
                    else -> "SL"
                }
                val c = Closed(s.strategy, symbol, (level - s.entry) * s.dir / r, reason, ts)
                add(c); out.add(c); iter.remove()
                continue
            }
            if ((mid - s.best) * s.dir > 0) s.best = mid
            if ((s.best - s.entry) * s.dir >= r) {
                val be = s.entry + s.dir * spread
                val trail = s.best - s.dir * r
                val target = if ((trail - be) * s.dir > 0) trail else be
                if ((target - s.stop) * s.dir > 0) s.stop = target
            }
        }
        if (out.isNotEmpty()) persist()
        return out
    }

    @Synchronized
    fun verdict(strategy: String): Verdict {
        val rs = results[strategy]?.map { it.r }.orEmpty()
        val n = rs.size
        if (n == 0) return Verdict(0, 0.0, 0.0, false, minTrades)
        val avg = rs.average()
        val sd = if (n > 1) sqrt(rs.sumOf { (it - avg) * (it - avg) } / (n - 1)) else 0.0
        val t = when {
            sd > 0 -> avg / (sd / sqrt(n.toDouble()))
            avg > 0 -> Double.POSITIVE_INFINITY // same identyczne zyski
            avg < 0 -> Double.NEGATIVE_INFINITY
            else -> 0.0
        }
        return Verdict(n, avg, t, n >= minTrades && avg >= minAvgR && t >= minTStat, minTrades)
    }

    fun proven(strategy: String): Boolean = verdict(strategy).proven

    @Synchronized
    fun live(strategy: String): Live {
        val rs = results[strategy]?.toList()?.takeLast(liveWindow)?.map { it.r }.orEmpty()
        val avg = if (rs.isEmpty()) 0.0 else rs.average()
        return Live(rs.size, avg, rs.size >= liveMin && avg < 0.0)
    }

    /** Czy strategia może teraz złożyć prawdziwe zlecenie. */
    fun allows(strategy: String, policy: Policy): Boolean = when (policy) {
        Policy.OFF -> true
        Policy.STRICT -> verdict(strategy).proven
        Policy.LIVE -> !live(strategy).paused
    }

    /** Opis stanu jednej strategii (do komunikatów). */
    fun status(strategy: String, policy: Policy): String {
        if (policy == Policy.STRICT) return verdict(strategy).line()
        val l = live(strategy)
        return when {
            l.trades == 0 -> "brak wyników"
            policy == Policy.LIVE && l.paused -> "ostatnie %d prób E=%+.2fR – wstrzymana do poprawy wyniku".format(l.trades, l.avgR)
            else -> "ostatnie %d prób E=%+.2fR".format(l.trades, l.avgR)
        }
    }

    /** Krótki stan wszystkich strategii do HUD-a: ✓ = może składać zlecenia, ✗ = tylko pomiar. */
    fun summary(policy: Policy = Policy.STRICT): String = STRATEGIES.joinToString(" • ") { k ->
        val mark = if (allows(k, policy)) "✓" else "✗"
        if (policy == Policy.STRICT) {
            val v = verdict(k)
            when {
                v.trades == 0 -> "$k 0/${v.needed}"
                v.trades < v.needed -> "%s %d/%d E=%+.2fR".format(k, v.trades, v.needed, v.avgR)
                else -> "%s %d E=%+.2fR %s".format(k, v.trades, v.avgR, mark)
            }
        } else {
            val l = live(k)
            if (l.trades == 0) "$k – $mark" else "%s %d E=%+.2fR %s".format(k, l.trades, l.avgR, mark)
        }
    }

    private fun add(c: Closed) {
        val q = results.getOrPut(c.strategy) { ArrayDeque() }
        q.addLast(c)
        while (q.size > window) q.removeFirst()
    }

    private fun persist() {
        runCatching {
            save(results.values.flatten().joinToString("\n") { "${it.strategy};${it.symbol};${it.r};${it.reason};${it.closedAt}" })
        }
    }

    companion object {
        /** Minimum zamkniętych prób „na sucho”, zanim strategia może handlować prawdziwymi pieniędzmi. */
        const val MIN_TRADES = 30
        /** Wynik liczony z ostatnich [WINDOW] prób. */
        const val WINDOW = 100
        /** Średni wynik po kosztach ≥ 0,10R… */
        const val MIN_AVG_R = 0.10
        /** …i istotny statystycznie (t ≥ 2). */
        const val MIN_T_STAT = 2.0
        /** Ocena bieżąca ([Policy.LIVE]): średnia z ostatnich [LIVE_WINDOW] prób… */
        const val LIVE_WINDOW = 20
        /** …liczona od [LIVE_MIN] prób; ujemna = strategia wstrzymana. */
        const val LIVE_MIN = 10

        val STRATEGIES = listOf("SMC", "RT", "ORB", "ZAKRES")

        /** Klucz strategii z opisu wejścia; null = wejście nie podlega bramce (ręczne, turniej – ma własną). */
        fun keyOf(reason: String): String? = STRATEGIES.firstOrNull { reason.startsWith(it) }
    }
}
