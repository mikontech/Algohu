package com.example.algohud.bot

import com.example.algohud.core.Bar
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Składa świece z ticków (domyślnie 5 min). */
class BarAggregator(private val periodMs: Long = 5 * 60_000L, private val maxBars: Int = 300) {
    private val bars = ArrayDeque<Bar>()
    private var current: Bar? = null
    private var lastTickAt = Long.MIN_VALUE

    init {
        require(periodMs > 0) { "periodMs must be positive" }
        require(maxBars > 0) { "maxBars must be positive" }
    }

    /** Zwraca świecę, która właśnie się zamknęła (gdy tick otworzył nową), w przeciwnym razie null. */
    @Synchronized
    fun onTick(price: Double, ts: Long): Bar? {
        if (!price.isFinite() || price <= 0 || ts < 0 || ts < lastTickAt) return null
        val start = ts - ts % periodMs
        lastTickAt = ts
        val c = current
        if (c == null) {
            if (bars.isNotEmpty() && start <= bars.last().start) return null // spóźniony tick po domknięciu przez zegar
            current = Bar(start, price, price, price, price)
            return null
        }
        if (start == c.start) {
            current = c.copy(high = max(c.high, price), low = min(c.low, price), close = price)
            return null
        }
        if (start < c.start) {
            // Nigdy nie cofamy czasu bieżącej świecy.
            return null
        }
        bars.addLast(c)
        while (bars.size > maxBars) bars.removeFirst()
        current = Bar(start, price, price, price, price)
        return c
    }

    /** Historia z REST (bez trwającej świecy) – strategia nie musi czekać godzinami na rozgrzewkę. */
    @Synchronized
    fun seed(history: List<Bar>) {
        val merged = (history + bars).filter { b ->
            b.start >= 0 && (current == null || b.start < current!!.start) &&
                listOf(b.open, b.high, b.low, b.close).all { it.isFinite() && it > 0 } &&
                b.low <= minOf(b.open, b.close) && b.high >= maxOf(b.open, b.close)
        }.associateBy { it.start }.values.sortedBy { it.start }.takeLast(maxBars)
        bars.clear()
        merged.forEach { bars.addLast(it) }
    }

    @Synchronized
    fun closed(): List<Bar> = bars.toList()

    /**
     * Zegar ścienny: zamyka bieżącą świecę, gdy jej czas minął, a nowy tick jeszcze nie przyszedł
     * (rzadkie notowania). Następny tick otworzy nową świecę. Zwraca zamkniętą świecę albo null.
     */
    @Synchronized
    fun roll(now: Long, graceMs: Long = 3_000L): Bar? {
        val c = current ?: return null
        if (now < c.start + periodMs + graceMs) return null
        bars.addLast(c)
        while (bars.size > maxBars) bars.removeFirst()
        current = null
        return c
    }

    /** Bieżąca, jeszcze trwająca świeca (do detekcji impulsu w czasie rzeczywistym). */
    @Synchronized
    fun current(): Bar? = current
}

object Indicators {
    fun ema(values: List<Double>, period: Int): Double? {
        if (values.size < period) return null
        val k = 2.0 / (period + 1)
        var e = values.take(period).average()
        for (v in values.drop(period)) e = v * k + e * (1 - k)
        return e
    }

    /** Average True Range (Wilder). */
    fun atr(bars: List<Bar>, period: Int = 14): Double? {
        if (bars.size < period + 1) return null
        val trs = (1 until bars.size).map { i ->
            val b = bars[i]
            val pc = bars[i - 1].close
            maxOf(b.high - b.low, abs(b.high - pc), abs(b.low - pc))
        }
        var a = trs.take(period).average()
        for (tr in trs.drop(period)) a = (a * (period - 1) + tr) / period
        return a
    }

    /** RSI (Wilder). */
    fun rsi(closes: List<Double>, period: Int = 14): Double? {
        if (closes.size < period + 1) return null
        var gain = 0.0
        var loss = 0.0
        for (i in 1..period) {
            val d = closes[i] - closes[i - 1]
            if (d > 0) gain += d else loss -= d
        }
        gain /= period
        loss /= period
        for (i in period + 1 until closes.size) {
            val d = closes[i] - closes[i - 1]
            gain = (gain * (period - 1) + max(d, 0.0)) / period
            loss = (loss * (period - 1) + max(-d, 0.0)) / period
        }
        if (loss == 0.0) return 100.0
        return 100 - 100 / (1 + gain / loss)
    }
}
