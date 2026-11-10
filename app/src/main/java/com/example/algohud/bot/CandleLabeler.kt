package com.example.algohud.bot

import java.io.File
import kotlin.math.abs
import kotlin.math.max

/** Labels use only completed horizons. Features stop at the signal candle. */
object CandleLabeler {
    enum class Outcome { UP, DOWN, NEUTRAL, AMBIGUOUS }

    data class Candle(
        val startMs: Long,
        val open: Double,
        val high: Double,
        val low: Double,
        val close: Double,
        val ticks: Int
    )

    data class Config(
        val timeframeMs: Long,
        val atrPeriod: Int = 14,
        val horizonBars: Int = 5,
        val barrierAtr: Double = 1.0,
        val roundTripCost: Double
    ) {
        init {
            require(timeframeMs > 0)
            require(atrPeriod > 0)
            require(horizonBars > 0)
            require(barrierAtr.isFinite() && barrierAtr > 0.0)
            require(roundTripCost.isFinite() && roundTripCost >= 0.0)
        }
    }

    data class LoadResult(
        val candles: List<Candle>,
        val invalidRows: Int,
        val duplicateRows: Int
    )

    data class Sample(
        val signalAtMs: Long,
        val labelEndMs: Long,
        val atr: Double,
        val bodyAtr: Double,
        val rangeAtr: Double,
        val return1: Double,
        val outcome: Outcome
    )

    data class LabelResult(val samples: List<Sample>)

    fun load(file: File): LoadResult {
        require(file.isFile) { "Brak pliku świec: ${file.name}" }
        var invalid = 0
        var duplicates = 0
        val unique = sortedMapOf<Long, Candle>()
        file.useLines { lines ->
            lines.forEach { line ->
                val parts = line.split(',')
                val candle = if (parts.size == 6) {
                    val time = parts[0].trim().toLongOrNull()
                    val o = parts[1].trim().toDoubleOrNull()
                    val h = parts[2].trim().toDoubleOrNull()
                    val l = parts[3].trim().toDoubleOrNull()
                    val c = parts[4].trim().toDoubleOrNull()
                    val n = parts[5].trim().toIntOrNull()
                    if (time != null && o != null && h != null &&
                        l != null && c != null && n != null) {
                        Candle(time, o, h, l, c, n)
                    } else null
                } else null
                if (candle == null || !valid(candle)) {
                    invalid++
                } else if (unique.containsKey(candle.startMs)) {
                    // First valid row wins; later duplicates are counted.
                    duplicates++
                } else {
                    unique[candle.startMs] = candle
                }
            }
        }
        return LoadResult(unique.values.toList(), invalid, duplicates)
    }

    private fun valid(c: Candle): Boolean =
        c.startMs > 0 && c.ticks > 0 &&
            listOf(c.open, c.high, c.low, c.close)
                .all { it.isFinite() && it > 0.0 } &&
            c.low <= minOf(c.open, c.close) &&
            c.high >= maxOf(c.open, c.close) && c.low <= c.high

    fun label(
        candles: List<Candle>,
        config: Config,
        checkCancelled: () -> Unit = {}
    ): LabelResult {
        require(candles.all(::valid)) { "Niepoprawne OHLC" }
        require(candles.all { it.startMs % config.timeframeMs == 0L }) {
            "Czas świecy nie jest wyrównany do interwału"
        }
        require(candles.zipWithNext().all { (a, b) ->
            b.startMs > a.startMs
        }) { "Świece muszą być posortowane i unikalne" }

        val result = ArrayList<Sample>()
        var segmentStart = 0
        var rollingTr = 0.0
        val trueRanges = DoubleArray(candles.size)

        for (i in candles.indices) {
            checkCancelled()
            val current = candles[i]
            val continuous = i > 0 &&
                current.startMs - candles[i - 1].startMs == config.timeframeMs
            if (!continuous) {
                segmentStart = i
                rollingTr = 0.0
            }
            if (i == segmentStart) continue

            val previous = candles[i - 1]
            val tr = max(
                current.high - current.low,
                max(abs(current.high - previous.close),
                    abs(current.low - previous.close))
            )
            trueRanges[i] = tr
            rollingTr += tr
            if (i - segmentStart > config.atrPeriod) {
                rollingTr -= trueRanges[i - config.atrPeriod]
            }
            if (i - segmentStart < config.atrPeriod) continue
            if (config.horizonBars > candles.lastIndex - i) continue

            val atr = rollingTr / config.atrPeriod
            if (!atr.isFinite() || atr <= 0.0) continue
            val last = i + config.horizonBars
            var complete = true
            for (j in i + 1..last) {
                checkCancelled()
                if (candles[j].startMs - candles[j - 1].startMs !=
                    config.timeframeMs) {
                    complete = false
                    break
                }
            }
            if (!complete) continue

            val distance = config.barrierAtr * atr + config.roundTripCost
            val upper = current.close + distance
            val lower = current.close - distance
            if (!distance.isFinite() || !upper.isFinite() ||
                !lower.isFinite() || lower <= 0.0) continue

            var outcome = Outcome.NEUTRAL
            for (j in i + 1..last) {
                checkCancelled()
                val future = candles[j]
                // A gap beyond one barrier resolves before intrabar extrema.
                outcome = when {
                    future.open >= upper -> Outcome.UP
                    future.open <= lower -> Outcome.DOWN
                    future.high >= upper && future.low <= lower -> Outcome.AMBIGUOUS
                    future.high >= upper -> Outcome.UP
                    future.low <= lower -> Outcome.DOWN
                    else -> Outcome.NEUTRAL
                }
                if (outcome != Outcome.NEUTRAL) break
            }
            val signalAt = Math.addExact(current.startMs, config.timeframeMs)
            val labelEnd = Math.addExact(candles[last].startMs, config.timeframeMs)
            val body = (current.close - current.open) / atr
            val range = (current.high - current.low) / atr
            val ret = current.close / previous.close - 1.0
            if (!listOf(body, range, ret).all { it.isFinite() }) continue
            result += Sample(signalAt, labelEnd, atr, body, range, ret, outcome)
        }
        return LabelResult(result)
    }

    fun writeCsv(file: File, samples: List<Sample>) {
        file.bufferedWriter().use { out ->
            out.write("signal_ms,label_end_ms,atr,body_atr,range_atr,return1,outcome\n")
            samples.forEach { s ->
                out.write(listOf(s.signalAtMs, s.labelEndMs, s.atr,
                    s.bodyAtr, s.rangeAtr, s.return1, s.outcome.name)
                    .joinToString(",") + "\n")
            }
        }
    }
}
