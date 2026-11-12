package com.example.algohud.bot

import com.example.algohud.core.Bar
import com.example.algohud.core.Side
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Silnik decyzji „instytucjonalnego” tradera XAU/USD (Smart Money Concepts / price action):
 *  • czas: handel tylko w killzone'ach (Londyn 09:00–12:00, Nowy Jork 14:30–17:00 czasu PL), blackout wokół danych USA,
 *  • płynność: Asia High/Low (01:00–08:00 PL), PDH/PDL (poprzedni dzień), swingi z ostatnich 60 min,
 *  • setup: sweep płynności (wybicie poziomu knotem i powrót ceny) → CHoCH/BOS na M1 w przeciwną stronę,
 *  • ryzyko: SL za ekstremum sweepu (punkt zanegowania), TP na przeciwnej płynności, minimum R:R 1:2,5,
 *  • kontekst: struktura H1 (z M5) – przeciw trendowi H1 tylko po sweepie PDH/PDL.
 * Makro (DXY, US10Y, kalendarz) – brak feedu w aplikacji; zamiast kalendarza stałe okna publikacji danych USA.
 */
class SmcEngine(
    private val zone: ZoneId = ZoneId.of("Europe/Warsaw"),
    /** true = handel przez całą dobę (killzone'y tylko w opisie); false = tylko Londyn/NY killzone. */
    private val allSessions: Boolean = false
) {

    enum class Session(val label: String, val tradable: Boolean) {
        ASIA("AZJA – buduje płynność", false),
        PRE_LONDON("przed Londynem", false),
        LONDON_KZ("LONDYN killzone", true),
        LONDON("Londyn (po killzone)", false),
        NY_KZ("NOWY JORK killzone", true),
        NY("Nowy Jork (po killzone)", false),
        OFF("poza sesjami", false)
    }

    data class Level(val name: String, val price: Double)

    data class Levels(
        val pdh: Double?, val pdl: Double?,
        val asiaHigh: Double?, val asiaLow: Double?,
        val swingHigh: Double?, val swingLow: Double?
    ) {
        fun above(price: Double) = listOfNotNull(
            pdh?.let { Level("PDH", it) }, asiaHigh?.let { Level("Asia High", it) }, swingHigh?.let { Level("swing H 1h", it) }
        ).filter { it.price > price }.sortedBy { it.price - price }
        fun below(price: Double) = listOfNotNull(
            pdl?.let { Level("PDL", it) }, asiaLow?.let { Level("Asia Low", it) }, swingLow?.let { Level("swing L 1h", it) }
        ).filter { it.price < price }.sortedBy { price - it.price }
    }

    data class Setup(
        val side: Side,
        val stop: Double,
        val target: Double,
        val rr: Double,
        val swept: Level,
        val extreme: Double,
        val chochLevel: Double,
        /** Klucz do jednego wejścia na jeden sweep. */
        val key: String,
        val reason: String
    )

    data class Result(
        val session: Session,
        val bias: Int,
        val levels: Levels?,
        val setup: Setup?,
        /** Dlaczego brak wejścia (gdy setup == null). */
        val note: String,
        /** Scenariusze warunkowe „Jeśli … to …” do HUD-a. */
        val scenarios: List<String>,
        /** Kontekst wielu interwałów: struktura H1, reżim M5 (trend/konsolidacja), zmienność, koszt spreadu. */
        val context: String = ""
    )

    fun session(ts: Long): Session {
        val t = Instant.ofEpochMilli(ts).atZone(zone)
        val m = t.hour * 60 + t.minute
        return when {
            m in 60 until 8 * 60 -> Session.ASIA
            m in 8 * 60 until 9 * 60 -> Session.PRE_LONDON
            m in 9 * 60 until 12 * 60 -> Session.LONDON_KZ
            m in 12 * 60 until 14 * 60 + 30 -> Session.LONDON
            m in 14 * 60 + 30 until 17 * 60 -> Session.NY_KZ
            m in 17 * 60 until 22 * 60 -> Session.NY
            else -> Session.OFF
        }
    }

    /** Okna publikacji danych USA (czas PL): 14:30 (CPI/NFP/PPI/GDP), 16:00 (ISM/JOLTS/UoM), 20:00 (FOMC). */
    /** Blackout wokół danych z USA – liczony w czasie Nowego Jorku (poprawny także w tygodniach przesunięcia czasu). */
    fun newsBlackout(ts: Long): String? = UsSession.dataBlackout(ts)

    /** PDH/PDL z poprzedniego dnia handlowego, Asia High/Low z dzisiejszych 01:00–08:00, swingi z ostatnich 60 min (bez ostatnich 10). */
    fun levels(m5: List<Bar>, m1: List<Bar>, now: Long): Levels {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        fun date(b: Bar): LocalDate = Instant.ofEpochMilli(b.start).atZone(zone).toLocalDate()
        val prevDay = m5.map { date(it) }.filter { it < today }.maxOrNull()
        val prev = if (prevDay == null) emptyList() else m5.filter { date(it) == prevDay }
        val asia = m5.filter {
            val z = Instant.ofEpochMilli(it.start).atZone(zone)
            z.toLocalDate() == today && z.hour in 1 until 8
        }
        val asiaDone = session(now).let { it != Session.ASIA && it != Session.OFF }
        val swing = m1.dropLast(30).takeLast(60) // płynność sprzed okna sweepu (ostatnie 30 min)
        return Levels(
            pdh = prev.takeIf { it.size >= 24 }?.maxOf { it.high },
            pdl = prev.takeIf { it.size >= 24 }?.minOf { it.low },
            asiaHigh = asia.takeIf { asiaDone && it.size >= 12 }?.maxOf { it.high },
            asiaLow = asia.takeIf { asiaDone && it.size >= 12 }?.minOf { it.low },
            swingHigh = swing.takeIf { it.size >= 20 }?.maxOf { it.high },
            swingLow = swing.takeIf { it.size >= 20 }?.minOf { it.low }
        )
    }

    /** Struktura H1 (składana z M5): +1 = HH+HL, −1 = LH+LL, 0 = brak czytelnej struktury. */
    fun biasH1(m5: List<Bar>): Int {
        val h1 = m5.groupBy { it.start / 3_600_000L }.toSortedMap().values.filter { it.size >= 6 }.map { g ->
            Bar(g.first().start, g.first().open, g.maxOf { it.high }, g.minOf { it.low }, g.last().close)
        }
        if (h1.size < 8) return 0
        val highs = swings(h1, high = true)
        val lows = swings(h1, high = false)
        if (highs.size < 2 || lows.size < 2) return 0
        val hh = highs[highs.size - 1] > highs[highs.size - 2]
        val hl = lows[lows.size - 1] > lows[lows.size - 2]
        return when {
            hh && hl -> 1
            !hh && !hl -> -1
            else -> 0
        }
    }

    /** Fraktale (2 świece z każdej strony). */
    private fun swings(bars: List<Bar>, high: Boolean): List<Double> {
        val out = ArrayList<Double>()
        for (i in 2 until bars.size - 2) {
            val v = if (high) bars[i].high else bars[i].low
            val ok = (-2..2).filter { it != 0 }.all { d ->
                val o = if (high) bars[i + d].high else bars[i + d].low
                if (high) v >= o else v <= o
            }
            if (ok) out.add(v)
        }
        return out
    }

    /** Indeks i wartość ostatniego swing low / high (fraktal 2/2) w zakresie [from, to). */
    private fun lastSwing(bars: List<Bar>, from: Int, to: Int, high: Boolean): Pair<Int, Double>? {
        for (i in (to - 3) downTo max(from + 2, 2)) {
            if (i + 2 >= bars.size) continue
            val v = if (high) bars[i].high else bars[i].low
            val ok = (-2..2).filter { it != 0 }.all { d ->
                val o = if (high) bars[i + d].high else bars[i + d].low
                if (high) v >= o else v <= o
            }
            if (ok) return i to v
        }
        return null
    }

    /**
     * Główna decyzja. [m1] – zamknięte świece 1m, [m5] – zamknięte 5m, [price] – bieżąca cena, [spread] – spread.
     * Setup krótki (lustrzanie długi): poziom nad ceną (PDH/Asia High/swing) wybity knotem w ostatnich 30 min,
     * świeca 1m zamknięta z powrotem pod poziomem, potem zamknięcie 1m pod ostatnim swing low sprzed ekstremum (CHoCH).
     */
    fun evaluate(m1Closed: List<Bar>, m5: List<Bar>, price: Double, spread: Double, now: Long, shift: Double = 0.0, current: Bar? = null): Result {
        // Szybka reakcja: trwająca świeca 1m też się liczy (sweep, powrót i CHoCH po cenie na żywo, bez czekania na zamknięcie).
        val m1 = if (current != null && (m1Closed.isEmpty() || current.start > m1Closed.last().start)) m1Closed + current.copy(close = price) else m1Closed
        // [shift] – tylko do opisów: ceny w tekstach w skali XTB (feed w tle może się różnić o kilka USD).
        val session = session(now)
        val bias = biasH1(m5)
        if (m1.size < 60) return Result(session, bias, null, null, "zbieram świece 1m (${m1.size}/60)", emptyList())
        val atr = Indicators.atr(m1Closed.takeLast(60)) ?: return Result(session, bias, null, null, "brak ATR", emptyList())
        val lv = levels(m5, m1Closed, now)
        val scen = scenarios(lv, price, bias, shift)
        val ctx = context(m1Closed, m5, atr, spread, bias)
        newsBlackout(now)?.let { return Result(session, bias, lv, null, it, scen, ctx) }
        if (!allSessions && !session.tradable) return Result(session, bias, lv, null, "${session.label} – poza killzone nie handluję, czekam na Londyn 09:00 / NY 14:30", scen, ctx)

        val tol = 0.05 * atr
        val buf = max(0.2 * atr, 1.5 * spread)
        val window = 30
        val start = m1.size - window
        var best: Setup? = null
        var why = "brak sweepu płynności w ostatnich 30 min – czekam"
        for (dir in listOf(-1, 1)) { // −1: sweep nad poziomem → short, +1: sweep pod poziomem → long
            val candidates = listOfNotNull(
                if (dir < 0) lv.pdh?.let { Level("PDH", it) } else lv.pdl?.let { Level("PDL", it) },
                if (dir < 0) lv.asiaHigh?.let { Level("Asia High", it) } else lv.asiaLow?.let { Level("Asia Low", it) },
                if (dir < 0) lv.swingHigh?.let { Level("swing H 1h", it) } else lv.swingLow?.let { Level("swing L 1h", it) }
            )
            for (level in candidates) {
                // 1) sweep: pierwsza świeca w oknie, która przebiła poziom; przed oknem poziom nienaruszony.
                val before = m1.subList(max(0, start - 30), start)
                val untouched = before.all { if (dir < 0) it.high <= level.price + tol else it.low >= level.price - tol }
                if (!untouched) continue
                val i = (start until m1.size).firstOrNull { k ->
                    if (dir < 0) m1[k].high > level.price + tol else m1[k].low < level.price - tol
                } ?: continue
                // 2) powrót: zamknięcie z powrotem po drugiej stronie poziomu
                val reclaim = (i until m1.size).firstOrNull { k ->
                    if (dir < 0) m1[k].close < level.price else m1[k].close > level.price
                } ?: run { why = "${level.name} %.2f wybity – czekam na powrót pod/nad poziom (inaczej to wybicie, nie sweep)".format(level.price + shift); null } ?: continue
                val ext = (i until m1.size).maxByOrNull { k -> if (dir < 0) m1[k].high else -m1[k].low }!!
                val extreme = if (dir < 0) m1[ext].high else m1[ext].low
                // akceptacja ceny za poziomem (zamknięcia po złej stronie) = to nie sweep
                if (abs(extreme - level.price) > 2.5 * atr) { why = "${level.name}: ruch za poziomem za duży – to wybicie, nie sweep"; continue }
                // 3) CHoCH: zamknięcie M1 za ostatnim swingiem sprzed ekstremum
                val sw = lastSwing(m1, max(0, ext - 15), ext + 1, high = dir > 0) ?: run { why = "${level.name} zebrany – brak swingu do CHoCH"; null } ?: continue
                val choch = (max(ext, reclaim) until m1.size).firstOrNull { k ->
                    if (dir < 0) m1[k].close < sw.second else m1[k].close > sw.second
                }
                if (choch == null) {
                    why = "sweep ${level.name} %.2f ✓ – czekam na CHoCH: zamknięcie M1 %s %.2f".format(level.price + shift, if (dir < 0) "pod" else "nad", sw.second + shift)
                    continue
                }
                if (m1.size - 1 - choch > 1) { why = "CHoCH po sweepie ${level.name} był > 1 min temu – za późno, nie gonię"; continue }
                // 4) bias H1: przeciw trendowi tylko po sweepie PDH/PDL
                if (bias != 0 && bias != dir && level.name != (if (dir < 0) "PDH" else "PDL")) {
                    why = "sweep ${level.name} przeciw strukturze H1 (${if (bias > 0) "↑" else "↓"}) – pod prąd tylko po PDH/PDL"
                    continue
                }
                val side = if (dir > 0) Side.BUY else Side.SELL
                val stop = extreme - dir * buf
                val risk = abs(price - stop)
                if (risk <= 0 || (price - stop) * dir <= 0) continue
                // Koszt liczony względem ryzyka transakcji (SL), nie świecy 1m – na złocie spread to zwykle 50–70% ATR(1m).
                if (spread > 0.2 * risk) { why = "spread %.2f to > 20%% odległości SL %.2f – za drogo".format(spread, risk); continue }
                // Za późno = cena uciekła od poziomu CHoCH o > 35% ryzyka – takie wejście ma gorszy R:R i zwykle łapie szczyt.
                if ((sw.second - price) * dir < 0 && abs(price - sw.second) > 0.35 * risk) {
                    why = "CHoCH ✓, ale cena uciekła %.2f od poziomu – nie gonię".format(abs(price - sw.second)); continue
                }
                // 5) cel: najbliższa przeciwna płynność z R:R ≥ MIN_RR (TP tuż przed poziomem)
                val targets = if (dir < 0) lv.below(price) else lv.above(price)
                // R:R PO KOSZTACH: zysk pomniejszony o spread, ryzyko powiększone o spread (wejście/wyjście po gorszej stronie).
                fun netRr(tp: Double) = (abs(tp - price) - spread) / (risk + spread)
                val tgt = targets.map { it to (it.price + dir * -0.1 * atr) }
                    .firstOrNull { (_, tp) -> netRr(tp) >= MIN_RR }
                if (tgt == null) {
                    why = "sweep ${level.name} + CHoCH ✓, ale brak celu z R:R ≥ 1:%.1f (SL %.2f) – odpuszczam".format(MIN_RR, stop + shift)
                    continue
                }
                val rr = netRr(tgt.second)
                val s = Setup(
                    side, stop, tgt.second, rr, level, extreme, sw.second,
                    key = "${level.name}:${m1[ext].start}",
                    reason = "SMC ${if (dir < 0) "SHORT" else "LONG"}: sweep ${level.name} %.2f → CHoCH %.2f (${if (choch == m1.size - 1 && current != null) "na żywo, świeca w trakcie" else "potwierdzone zamknięciem M1"}), cel ${tgt.first.name} %.2f, R:R po kosztach 1:%.1f".format(
                        level.price + shift, sw.second + shift, tgt.first.price + shift, rr)
                )
                if (best == null || s.rr > best.rr) best = s
            }
        }
        return Result(session, bias, lv, best, if (best != null) best.reason else "NO TRADE – $why", scen, ctx)
    }

    /** Kontekst: H1 struktura, M5 trend vs konsolidacja (zakres 12 świec vs ATR5), zmienność 1m vs mediana, spread jako % ATR. */
    private fun context(m1: List<Bar>, m5: List<Bar>, atr1: Double, spread: Double, bias: Int): String {
        val h1 = when (bias) { 1 -> "H1 ↑"; -1 -> "H1 ↓"; else -> "H1 ↔" }
        val regime = m5.takeLast(12).takeIf { it.size == 12 }?.let { w ->
            val atr5 = Indicators.atr(m5.takeLast(60)) ?: return@let null
            val span = w.maxOf { it.high } - w.minOf { it.low }
            val net = abs(w.last().close - w.first().open)
            when {
                span < 3 * atr5 -> "M5 konsolidacja (zakres 1h %.1f×ATR5)".format(span / atr5)
                net > 0.6 * span -> "M5 trend %s".format(if (w.last().close > w.first().open) "↑" else "↓")
                else -> "M5 szarpany zakres"
            }
        } ?: "M5 brak danych"
        val ranges = m1.takeLast(300).map { it.high - it.low }
        val vol = DataQuality.median(ranges)?.takeIf { it > 0 }?.let { med ->
            val r = atr1 / med
            when { r > 1.6 -> "zmienność wysoka"; r < 0.7 -> "zmienność niska"; else -> "zmienność normalna" }
        } ?: "zmienność ?"
        val cost = if (atr1 > 0) "spread %.0f%% ATR1".format(spread / atr1 * 100) else "spread ?"
        return "$h1 • $regime • $vol • $cost"
    }

    /** Scenariusze warunkowe dla najbliższej płynności nad i pod ceną. */
    private fun scenarios(lv: Levels, price: Double, bias: Int, shift: Double): List<String> {
        val out = ArrayList<String>()
        lv.above(price).firstOrNull()?.let { up ->
            val tgt = lv.below(price).firstOrNull()
            out += "Jeśli zbierzemy płynność nad ${up.name} %.2f i M1 zamknie pod → po CHoCH short, SL nad szczytem, TP %s".format(
                up.price + shift, tgt?.let { "${it.name} %.2f".format(it.price + shift) } ?: "najbliższe wsparcie") + if (bias > 0) " (pod prąd H1 – tylko jeśli to PDH)" else ""
        }
        lv.below(price).firstOrNull()?.let { dn ->
            val tgt = lv.above(price).firstOrNull()
            out += "Jeśli zbierzemy płynność pod ${dn.name} %.2f i M1 zamknie nad → po CHoCH long, SL pod dołkiem, TP %s".format(
                dn.price + shift, tgt?.let { "${it.name} %.2f".format(it.price + shift) } ?: "najbliższy opór") + if (bias < 0) " (pod prąd H1 – tylko jeśli to PDL)" else ""
        }
        return out
    }

    companion object {
        const val MIN_RR = 2.5
    }
}
