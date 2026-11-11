package com.example.algohud.bot

import com.example.algohud.core.Bar
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Lekki model uczący się online (po każdej zamkniętej świecy) – predykcja NASTĘPNEJ świecy:
 *  • kierunek: regresja logistyczna (AdaGrad + L2),
 *  • rozmiar korpusu (w ATR): regresja liniowa (AdaGrad).
 * Cechy: price action (korpus, knoty, położenie zamknięcia), momentum (ostatnie zwroty, suma korpusów,
 * EMA9−EMA21, RSI), położenie w zakresie 10 świec i „wolumen” = liczba ticków w świecy względem średniej
 * (feedy złota nie podają wolumenu – aktywność ticków jest jego przybliżeniem).
 *
 * Prawdopodobieństwo jest celowo ściągane do 50%, dopóki model ma mało próbek – na 1m rynek jest blisko
 * rzutu monetą i uczciwy model rzadko pokaże > 65%. Trafność z ostatnich 20 świec mierzymy na żywo.
 */
class NextCandlePredictor {

    data class Prediction(
        /** Start świecy, której dotyczy prognoza. */
        val forBarStart: Long,
        val probUp: Double,
        /** Szacowany korpus następnej świecy w ATR. */
        val expBodyAtr: Double,
        val atr: Double,
        val madeAt: Long,
        /** Wczesna prognoza (z niepełnej świecy, ~30–50 s) – oceniana osobno, żeby nauczyć się, czy wolno wchodzić wcześniej. */
        val early: Boolean = false
    ) {
        val up get() = probUp >= 0.5
        val prob get() = if (up) probUp else 1 - probUp
        val expBody get() = expBodyAtr * atr
        val strength get() = when {
            expBodyAtr >= STRONG_BODY_ATR -> "WYSOKA"
            expBodyAtr >= MEDIUM_BODY_ATR -> "ŚREDNIA"
            else -> "NISKA"
        }
    }

    private val wDir = DoubleArray(N)
    private val gDir = DoubleArray(N)
    private val wSize = DoubleArray(N).also { it[0] = 0.5 } // start: przeciętny korpus ≈ 0,5 ATR
    private val gSize = DoubleArray(N)
    private val hits = ArrayDeque<Boolean>()
    private val earlyHits = ArrayDeque<Boolean>()
    /** Ile świec model już przerobił (nauka). */
    var samples = 0L
        private set
    private var lastTrainedStart = Long.MIN_VALUE
    /** Prognozy czekające na swoją świecę. Prognoza dla B+1 powstaje w trakcie B, a w trakcie B+1 już kolejna – nie nadpisujemy. */
    private val pending = ArrayDeque<Prediction>()
    var last: Prediction? = null
        private set

    /** Trafione / ocenione z ostatnich [WINDOW] prognoz. */
    fun hitStats(early: Boolean = false): Pair<Int, Int> = (if (early) earlyHits else hits).let { h -> h.count { it } to h.size }
    fun accuracy(): Double? = if (hits.isEmpty()) null else hits.count { it }.toDouble() / hits.size

    /** Nauka na historii (np. po starcie) – tylko gdy model jest pusty. */
    fun warmUp(history: List<Bar>) {
        for (i in MIN_BARS until history.size) train(history.subList(0, i + 1))
    }

    /**
     * Po zamknięciu świecy: ocena prognozy (jeśli dotyczyła tej świecy) i aktualizacja wag.
     * [closed] = zamknięte świece, ostatnia właśnie się zamknęła.
     */
    fun onBarClosed(closed: List<Bar>) {
        val bar = closed.lastOrNull() ?: return
        if (bar.close != bar.open) {
            for (p in pending.filter { it.forBarStart == bar.start }) push((bar.close > bar.open) == p.up, p.early)
        }
        pending.removeAll { it.forBarStart <= bar.start }
        train(closed)
    }

    /**
     * Prognoza dla świecy po [current] (trwającej, prawie zamkniętej). [tickRatio] = aktywność ticków
     * bieżącej świecy względem średniej (1.0 = normalnie).
     */
    fun predict(closed: List<Bar>, current: Bar, tickRatio: Double, periodMs: Long, now: Long, early: Boolean = false): Prediction? {
        val bars = closed + current
        val x = features(bars, tickRatio) ?: return null
        val atr = Indicators.atr(bars.takeLast(ATR_WINDOW))?.takeIf { it > 0 } ?: return null
        val raw = sigmoid(dot(wDir, x))
        val trust = min(1.0, samples / TRUST_SAMPLES)
        val p = Prediction(
            forBarStart = current.start + periodMs,
            probUp = 0.5 + (raw - 0.5) * trust,
            expBodyAtr = dot(wSize, x).coerceIn(0.0, 3.0),
            atr = atr,
            madeAt = now,
            early = early
        )
        pending.removeAll { it.forBarStart == p.forBarStart && it.early == early }
        pending.addLast(p)
        while (pending.size > 5) pending.removeFirst()
        last = p
        return p
    }

    // ───────────── nauka ─────────────

    private fun train(closed: List<Bar>) {
        if (closed.size < MIN_BARS + 1) return
        val target = closed.last()
        if (target.start <= lastTrainedStart) return
        lastTrainedStart = target.start
        val hist = closed.subList(0, closed.size - 1)
        val x = features(hist, 1.0) ?: return
        val atr = Indicators.atr(hist.takeLast(ATR_WINDOW))?.takeIf { it > 0 } ?: return
        val body = target.close - target.open
        if (body != 0.0) {
            val y = if (body > 0) 1.0 else 0.0
            val err = y - sigmoid(dot(wDir, x))
            step(wDir, gDir, x, err, LR_DIR)
        }
        val sizeErr = (abs(body) / atr).coerceAtMost(3.0) - dot(wSize, x)
        step(wSize, gSize, x, sizeErr, LR_SIZE)
        samples++
    }

    private fun step(w: DoubleArray, g: DoubleArray, x: DoubleArray, err: Double, lr: Double) {
        for (i in 0 until N) {
            val grad = err * x[i] - (if (i == 0) 0.0 else L2 * w[i])
            g[i] += grad * grad
            w[i] += lr * grad / (sqrt(g[i]) + 1e-8)
        }
    }

    private fun push(hit: Boolean, early: Boolean = false) {
        val h = if (early) earlyHits else hits
        h.addLast(hit)
        while (h.size > WINDOW) h.removeFirst()
    }

    // ───────────── cechy ─────────────

    /** Cechy z ostatniej świecy listy (i jej poprzedniczek); wszystko w jednostkach ATR, przycięte do ±3. */
    internal fun features(bars: List<Bar>, tickRatio: Double): DoubleArray? {
        if (bars.size < MIN_BARS) return null
        val win = bars.takeLast(ATR_WINDOW)
        val atr = Indicators.atr(win)?.takeIf { it > 0 } ?: return null
        val b = bars.last()
        val range = b.high - b.low
        val closes = win.map { it.close }
        val ema9 = Indicators.ema(closes, 9) ?: return null
        val ema21 = Indicators.ema(closes, 21) ?: return null
        val rsi = Indicators.rsi(closes.takeLast(40)) ?: 50.0
        val c = bars.map { it.close }
        val n = c.size
        val last10 = bars.takeLast(10)
        val hh = last10.maxOf { it.high }
        val ll = last10.minOf { it.low }
        val half = (hh - ll) / 2
        val x = DoubleArray(N)
        x[0] = 1.0
        x[1] = (b.close - b.open) / atr
        x[2] = (b.high - max(b.open, b.close)) / atr
        x[3] = (min(b.open, b.close) - b.low) / atr
        x[4] = if (range > 0) (b.close - b.low) / range - 0.5 else 0.0
        x[5] = (c[n - 2] - c[n - 3]) / atr
        x[6] = (c[n - 3] - c[n - 4]) / atr
        x[7] = (ema9 - ema21) / atr
        x[8] = (rsi - 50) / 50
        x[9] = range / atr - 1
        x[10] = (tickRatio - 1).coerceIn(-1.0, 2.0)
        x[11] = bars.takeLast(5).sumOf { it.close - it.open } / atr / 2
        x[12] = if (half > 0) (b.close - (hh + ll) / 2) / half else 0.0
        for (i in 1 until N) x[i] = x[i].coerceIn(-3.0, 3.0)
        return x
    }

    // ───────────── zapis / odczyt (SharedPreferences, bez JSON) ─────────────

    fun export(): String = listOf(
        "v1", samples.toString(), lastTrainedStart.toString(),
        wDir.joinToString(","), gDir.joinToString(","), wSize.joinToString(","), gSize.joinToString(","),
        hits.joinToString("") { if (it) "1" else "0" },
        earlyHits.joinToString("") { if (it) "1" else "0" }
    ).joinToString(";")

    fun import(s: String): Boolean = runCatching {
        val p = s.split(";")
        if (p.size < 8 || p[0] != "v1") return false
        fun arr(t: String) = t.split(",").map { it.toDouble() }.also { require(it.size == N) }
        val a = arr(p[3]); val b = arr(p[4]); val c = arr(p[5]); val d = arr(p[6])
        for (i in 0 until N) { wDir[i] = a[i]; gDir[i] = b[i]; wSize[i] = c[i]; gSize[i] = d[i] }
        samples = p[1].toLong()
        lastTrainedStart = p[2].toLong()
        hits.clear(); p[7].forEach { push(it == '1') }
        earlyHits.clear(); p.getOrNull(8)?.forEach { push(it == '1', early = true) }
        true
    }.getOrDefault(false)

    companion object {
        const val N = 13
        const val MIN_BARS = 30
        const val ATR_WINDOW = 60
        const val WINDOW = 20
        const val LR_DIR = 0.05
        const val LR_SIZE = 0.03
        const val L2 = 1e-3
        const val TRUST_SAMPLES = 300.0
        const val STRONG_BODY_ATR = 0.70
        const val MEDIUM_BODY_ATR = 0.35

        private fun sigmoid(z: Double) = 1.0 / (1.0 + exp(-z.coerceIn(-30.0, 30.0)))
        private fun dot(w: DoubleArray, x: DoubleArray): Double { var s = 0.0; for (i in w.indices) s += w[i] * x[i]; return s }
    }
}
