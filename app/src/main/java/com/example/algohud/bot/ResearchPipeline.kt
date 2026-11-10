package com.example.algohud.bot

import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/** Offline research only. Never activates a model or sends orders. */
object ResearchPipeline {
    data class Request(
        val recordingName: String,
        val labeling: CandleLabeler.Config,
        val validation: WalkForwardValidator.Config,
        val simulation: TradeSimulator.Config
    ) {
        init {
            require(recordingName.matches(Regex("[A-Za-z0-9._-]+")))
            require(labeling.timeframeMs == 60_000L || labeling.timeframeMs == 300_000L)
            require(labeling.timeframeMs == simulation.timeframeMs)
        }
    }
    data class Result(
        val outputDirectory: File,
        val inputCandles: Int,
        val usedCandles: Int,
        val labelCount: Int,
        val inputSha256: String,
        val validation: WalkForwardValidator.Report,
        val simulation: TradeSimulator.Report,
        val summary: String
    )

    suspend fun run(filesDir: File, request: Request): Result =
        withContext(Dispatchers.Default) {
            val context = coroutineContext
            val checkCancelled: () -> Unit = { context.ensureActive() }
            val minutes = request.labeling.timeframeMs / 60_000L
            val source = File(filesDir, "rec_${request.recordingName}_${minutes}m.csv")
            val root = File(filesDir, "research")
            val runId = UUID.randomUUID().toString()
            val pending = File(root, ".pending_$runId")
            val output = File(root, "run_$runId")
            withContext(Dispatchers.IO) {
                check(root.isDirectory || root.mkdirs())
                check(pending.mkdir())
            }
            try {
                val snapshot = File(pending, "input.csv")
                val loaded = withContext(Dispatchers.IO) {
                    synchronized(CandleRecorder) {
                        require(source.isFile) { "Brak zapisu ${source.name}" }
                        source.copyTo(snapshot, overwrite = false)
                    }
                    CandleLabeler.load(snapshot)
                }
                checkCancelled()
                require(loaded.invalidRows == 0) { "Niepoprawne wiersze CSV: ${loaded.invalidRows}" }
                require(loaded.duplicateRows == 0) { "Duplikaty CSV: ${loaded.duplicateRows}" }
                val hash = withContext(Dispatchers.IO) {
                    val digest = MessageDigest.getInstance("SHA-256")
                    snapshot.inputStream().use { input ->
                        val buffer = ByteArray(8192)
                        while (true) {
                            checkCancelled()
                            val n = input.read(buffer)
                            if (n < 0) break
                            digest.update(buffer, 0, n)
                        }
                    }
                    digest.digest().joinToString("") {
                        (it.toInt() and 255).toString(16).padStart(2, '0')
                    }
                }
                val all = loaded.candles
                var start = all.lastIndex
                while (start > 0 && all[start].startMs - all[start - 1].startMs ==
                    request.labeling.timeframeMs) {
                    checkCancelled()
                    start--
                }
                // The first recorded bar may have started before collection.
                val candles = if (all.isEmpty()) emptyList() else all.drop(start + 1)
                require(candles.isNotEmpty()) { "Za mało świec w najnowszym ciągłym fragmencie" }
                val labeled = CandleLabeler.label(candles, request.labeling, checkCancelled)
                require(labeled.samples.isNotEmpty()) { "Za mało danych do etykietowania" }
                val datasetId = "${request.recordingName}|$hash|${request.labeling}"
                val validation = WalkForwardValidator.validate(labeled.samples,
                    datasetId, request.validation, checkCancelled)
                require(validation.forecasts.isNotEmpty()) {
                    "Brak okien testowych z prognozami. " + WalkForwardValidator.summary(validation)
                }
                val samplesByTime = labeled.samples.associateBy { it.signalAtMs }
                val signals = validation.forecasts.map { f ->
                    checkCancelled()
                    val sample = requireNotNull(samplesByTime[f.signalAtMs])
                    TradeSimulator.Signal(f.signalAtMs, f.trainedThroughMs, sample.atr,
                        f.up, f.down, f.neutral)
                }
                val simulation = TradeSimulator.run(candles, signals,
                    request.simulation, checkCancelled)
                val summary = buildString {
                    appendLine("LABORATORIUM OFFLINE — NIE HANDEL AUTO")
                    appendLine("Źródło: ${source.name}; SHA-256: $hash")
                    appendLine("Świece w pliku: ${all.size}; wykorzystane: ${candles.size}")
                    appendLine("Wybrano najnowszy ciągły fragment bez jego pierwszej świecy.")
                    appendLine("Etykiety: ${labeled.samples.size}")
                    appendLine(WalkForwardValidator.summary(validation))
                    appendLine("Transakcje: ${simulation.trades.size}; pominięte sygnały: ${simulation.ignoredSignals}")
                    appendLine("Kapitał: ${simulation.startingEquity} -> ${simulation.endingEquity}")
                    appendLine("Netto: ${simulation.endingEquity - simulation.startingEquity}")
                    appendLine("Win rate: ${simulation.winRate}; średnie R: ${simulation.averageR}")
                    appendLine("Profit factor: ${simulation.profitFactor ?: "nieokreślony — brak strat"}")
                    appendLine("Obsunięcie zamkniętego kapitału (%): ${simulation.closedEquityMaxDrawdownPct}")
                    appendLine("Założenia: ceny MID potwierdzone przez wywołującego; stałe koszty.")
                    appendLine("Brak finansowania, margin call i precyzyjnej kolejności ticków.")
                    appendLine("Rejestrator nie gwarantuje pełnego pokrycia tickowego świecy.")
                    appendLine("Etykiety klasyfikacyjne i reguły wykonania to różne definicje wyniku.")
                    appendLine("Nie zapisano ani nie aktywowano modelu do handlu rzeczywistego.")
                }
                withContext(Dispatchers.IO) {
                    checkCancelled()
                    CandleLabeler.writeCsv(File(pending, "labels.csv"), labeled.samples)
                    WalkForwardValidator.writeForecastsCsv(File(pending, "forecasts.csv"), validation)
                    writeTrades(File(pending, "trades.csv"), simulation.trades, checkCancelled)
                    File(pending, "report.txt").writeText(summary)
                    File(pending, "settings.txt").writeText(
                        "schema=1\ndataset_id=$datasetId\nrequest=$request\n")
                    checkCancelled()
                    check(pending.renameTo(output)) { "Nie można opublikować raportu" }
                }
                Result(output, all.size, candles.size, labeled.samples.size,
                    hash, validation, simulation, summary)
            } catch (error: Throwable) {
                withContext(NonCancellable + Dispatchers.IO) { pending.deleteRecursively() }
                throw error
            }
        }

    private fun writeTrades(file: File, trades: List<TradeSimulator.Trade>,
                            checkCancelled: () -> Unit) {
        file.bufferedWriter().use { out ->
            out.write("side,signal_ms,entry_ms,exit_bar_end_ms,lots,entry,exit,sl,tp,risk,commission,net_pnl,r,reason\n")
            trades.forEach { t ->
                checkCancelled()
                out.write(listOf(t.side.name, t.signalAtMs, t.entryAtMs, t.exitBarEndMs,
                    t.lots, t.entryPrice, t.exitPrice, t.stopPrice, t.takeProfit,
                    t.initialRiskMoney, t.commission, t.netPnl, t.rMultiple, t.reason.name)
                    .joinToString(",") + "\n")
            }
        }
    }
}
