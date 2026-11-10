package com.example.algohud.bot

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max

/** Constant costs, single position, no financing or margin-call model.
 * Input OHLC must be MID prices in the account-compatible quote currency.
 * Exit timestamps are bar ends, not precise intrabar execution times.
 */
object TradeSimulator {
    enum class Side(val sign: Int) { BUY(1), SELL(-1) }
    enum class ExitReason { STOP, TARGET, BOTH_TOUCHED_STOP_ASSUMED, TIMEOUT, END_OF_DATA }
    data class Signal(val atMs: Long, val trainedThroughMs: Long, val atr: Double,
                      val up: Double, val down: Double, val neutral: Double)
    data class Config(
        val timeframeMs: Long,
        val initialEquity: Double,
        val spread: Double,
        val slippage: Double,
        val pointValuePerLot: Double,
        val commissionPerLotPerSide: Double,
        val minLot: Double,
        val lotStep: Double,
        val maxLot: Double,
        val marginRate: Double,
        val riskFraction: Double,
        val maxExposureMultiple: Double,
        val maxMarginFraction: Double,
        val stopAtr: Double,
        val rewardRatio: Double,
        val maxHoldingBars: Int,
        val minProbability: Double,
        val minEdge: Double,
        val confirmedMidPrices: Boolean
    ) {
        init {
            require(timeframeMs > 0 && maxHoldingBars > 0)
            require(confirmedMidPrices) { "Wymagane potwierdzenie danych MID" }
            require(listOf(initialEquity, pointValuePerLot, minLot, lotStep,
                maxLot, marginRate, riskFraction, maxExposureMultiple,
                maxMarginFraction, stopAtr, rewardRatio).all { it.isFinite() && it > 0.0 })
            require(listOf(spread, slippage, commissionPerLotPerSide, minEdge)
                .all { it.isFinite() && it >= 0.0 })
            require(minLot <= maxLot && marginRate <= 1.0 && riskFraction <= 1.0 &&
                maxMarginFraction <= 1.0 && minEdge <= 1.0)
            require(minProbability.isFinite() && minProbability in 0.5..1.0)
        }
    }
    data class Trade(
        val side: Side, val signalAtMs: Long, val entryAtMs: Long,
        val exitBarEndMs: Long, val lots: Double, val entryPrice: Double,
        val exitPrice: Double, val stopPrice: Double, val takeProfit: Double,
        val initialRiskMoney: Double, val commission: Double,
        val netPnl: Double, val rMultiple: Double, val reason: ExitReason
    )
    data class Report(
        val startingEquity: Double, val endingEquity: Double,
        val trades: List<Trade>, val closedEquityMaxDrawdownPct: Double,
        val ignoredSignals: Int
    ) {
        val winRate: Double get() = if (trades.isEmpty()) 0.0 else
            trades.count { it.netPnl > 0.0 }.toDouble() / trades.size
        val averageR: Double get() = if (trades.isEmpty()) 0.0 else
            trades.sumOf { it.rMultiple } / trades.size
        val profitFactor: Double? get() {
            val losses = -trades.filter { it.netPnl < 0.0 }.sumOf { it.netPnl }
            return if (losses == 0.0) null else
                trades.filter { it.netPnl > 0.0 }.sumOf { it.netPnl } / losses
        }
    }

    fun run(candles: List<CandleLabeler.Candle>, signals: List<Signal>,
            config: Config, checkCancelled: () -> Unit = {}): Report {
        candles.forEach { c ->
            checkCancelled()
            require(c.startMs > 0 && c.startMs % config.timeframeMs == 0L)
            require(listOf(c.open, c.high, c.low, c.close).all { it.isFinite() && it > 0.0 })
            require(c.low <= minOf(c.open, c.close) && c.high >= maxOf(c.open, c.close))
            require(c.low - config.spread / 2.0 - config.slippage > 0.0)
        }
        require(candles.zipWithNext().all { (a, b) ->
            b.startMs - a.startMs == config.timeframeMs
        }) { "Symulator wymaga ciągłych, posortowanych świec" }
        require(signals.zipWithNext().all { (a, b) -> a.atMs < b.atMs })
        val barTimes = candles.map { it.startMs }.toSet()
        signals.forEach { s ->
            checkCancelled()
            require(s.atMs in barTimes) { "Brak świecy otwarcia dla sygnału" }
            require(s.trainedThroughMs >= 0 && s.trainedThroughMs < s.atMs)
            require(s.atr.isFinite() && s.atr > 0.0)
            val p = listOf(s.up, s.down, s.neutral)
            require(p.all { it.isFinite() && it in 0.0..1.0 } && abs(p.sum() - 1.0) < 1e-6)
        }
        val byTime = signals.associateBy { it.atMs }
        val trades = ArrayList<Trade>()
        var equity = config.initialEquity
        var peak = equity
        var drawdown = 0.0
        var i = 0
        while (i < candles.size) {
            checkCancelled()
            val s = byTime[candles[i].startMs]
            if (s == null || equity <= 0.0) { i++; continue }
            val side = when {
                s.up > max(s.down, s.neutral) && s.up >= config.minProbability &&
                    s.up - s.down >= config.minEdge -> Side.BUY
                s.down > max(s.up, s.neutral) && s.down >= config.minProbability &&
                    s.down - s.up >= config.minEdge -> Side.SELL
                else -> null
            }
            if (side == null) { i++; continue }
            val sign = side.sign.toDouble()
            val entry = candles[i].open + sign * (config.spread / 2.0 + config.slippage)
            val distance = s.atr * config.stopAtr
            val stop = entry - sign * distance
            val target = entry + sign * distance * config.rewardRatio
            require(listOf(entry, distance, stop, target).all { it.isFinite() && it > 0.0 })
            val commissionPerLot = 2.0 * config.commissionPerLotPerSide
            val riskPerLot = (distance + config.slippage) * config.pointValuePerLot + commissionPerLot
            val notionalPerLot = entry * config.pointValuePerLot
            require(riskPerLot.isFinite() && notionalPerLot.isFinite())
            val cap = minOf(config.maxLot, equity * config.riskFraction / riskPerLot,
                equity * config.maxExposureMultiple / notionalPerLot,
                equity * config.maxMarginFraction / (notionalPerLot * config.marginRate))
            require(cap.isFinite() && cap >= 0.0)
            val lots = floor(cap / config.lotStep) * config.lotStep
            if (lots < config.minLot || lots <= 0.0) { i++; continue }
            val risk = riskPerLot * lots
            val last = i + minOf(config.maxHoldingBars - 1, candles.lastIndex - i)
            var exitIndex = last
            var exitPrice = Double.NaN
            var reason = if (last - i + 1 < config.maxHoldingBars)
                ExitReason.END_OF_DATA else ExitReason.TIMEOUT
            for (j in i..last) {
                checkCancelled()
                val c = candles[j]
                val offset = -sign * config.spread / 2.0
                val open = c.open + offset
                val high = c.high + offset
                val low = c.low + offset
                val stopAtOpen = if (side == Side.BUY) open <= stop else open >= stop
                val targetAtOpen = if (side == Side.BUY) open >= target else open <= target
                val stopHit = if (side == Side.BUY) low <= stop else high >= stop
                val targetHit = if (side == Side.BUY) high >= target else low <= target
                val rawExit: Double? = when {
                    stopAtOpen -> { reason = ExitReason.STOP; open }
                    targetAtOpen -> { reason = ExitReason.TARGET; target }
                    stopHit && targetHit -> { reason = ExitReason.BOTH_TOUCHED_STOP_ASSUMED; stop }
                    stopHit -> { reason = ExitReason.STOP; stop }
                    targetHit -> { reason = ExitReason.TARGET; target }
                    j == last -> c.close + offset
                    else -> null
                }
                if (rawExit != null) {
                    exitIndex = j
                    exitPrice = rawExit - sign * config.slippage
                    break
                }
            }
            val commission = commissionPerLot * lots
            val pnl = sign * (exitPrice - entry) * config.pointValuePerLot * lots - commission
            require(listOf(exitPrice, risk, commission, pnl).all { it.isFinite() })
            require(risk > 0.0 && exitPrice > 0.0)
            equity += pnl
            require(equity.isFinite())
            peak = max(peak, equity)
            drawdown = max(drawdown, (peak - equity) / peak * 100.0)
            trades += Trade(side, s.atMs, candles[i].startMs,
                Math.addExact(candles[exitIndex].startMs, config.timeframeMs), lots,
                entry, exitPrice, stop, target, risk, commission, pnl, pnl / risk, reason)
            // Signals during the position are discarded, never executed late.
            i = exitIndex + 1
        }
        checkCancelled()
        return Report(config.initialEquity, equity, trades, drawdown, signals.size - trades.size)
    }
}
