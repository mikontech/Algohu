package com.example.algohud.bot

import java.io.File
import java.util.Locale
import kotlin.math.ln

/** Expanding training windows; disjoint, chronological test windows. */
object WalkForwardValidator {
    private val classes = listOf(CandleLabeler.Outcome.UP,
        CandleLabeler.Outcome.DOWN, CandleLabeler.Outcome.NEUTRAL)

    data class Config(
        val initialTrainSamples: Int = 300,
        val testSamples: Int = 60,
        val embargoMs: Long = 0L,
        val training: DirectionModel.Config = DirectionModel.Config()
    ) {
        init {
            require(initialTrainSamples >= 3)
            require(testSamples > 0)
            require(embargoMs >= 0L)
        }
    }

    data class Forecast(
        val fold: Int,
        val signalAtMs: Long,
        val trainedThroughMs: Long,
        val actual: CandleLabeler.Outcome,
        val predicted: CandleLabeler.Outcome,
        val baseline: CandleLabeler.Outcome,
        val up: Double,
        val down: Double,
        val neutral: Double
    )

    data class Metrics(
        val count: Int,
        val accuracy: Double,
        val baselineAccuracy: Double,
        val logLoss: Double,
        val brierScore: Double,
        val confusion: List<List<Int>>
    )

    data class Fold(
        val number: Int,
        val trainCount: Int,
        val purgedCount: Int,
        val testStartMs: Long,
        val testLastSignalMs: Long,
        val trainedThroughMs: Long,
        val forecastCount: Int,
        val metrics: Metrics?
    )
    data class SkippedFold(val number: Int, val reason: String)
    data class Report(
        val folds: List<Fold>,
        val forecasts: List<Forecast>,
        val skipped: List<SkippedFold>,
        val aggregate: Metrics?
    )

    fun validate(samples: List<CandleLabeler.Sample>, datasetId: String,
                 config: Config = Config(),
                 checkCancelled: () -> Unit = {}): Report {
        require(datasetId.isNotBlank())
        require(samples.zipWithNext().all { (a, b) ->
            a.signalAtMs < b.signalAtMs
        }) { "Próbki muszą być posortowane i unikalne" }
        samples.forEach { s ->
            checkCancelled()
            require(s.signalAtMs > 0L && s.labelEndMs >= s.signalAtMs)
            require(listOf(s.bodyAtr, s.rangeAtr, s.return1).all { it.isFinite() })
        }
        val folds = ArrayList<Fold>()
        val forecasts = ArrayList<Forecast>()
        val skipped = ArrayList<SkippedFold>()
        var cursor = config.initialTrainSamples
        var number = 1
        while (cursor < samples.size) {
            checkCancelled()
            val end = cursor + minOf(config.testSamples, samples.size - cursor)
            val test = samples.subList(cursor, end)
            val cutoff = Math.subtractExact(test.first().signalAtMs, config.embargoMs)
            val earlier = samples.subList(0, cursor)
            // Equality is excluded: label information must precede test time.
            val available = earlier.filter {
                checkCancelled()
                it.labelEndMs < cutoff
            }
            val train = available.filter { it.outcome in classes }
            val reason = when {
                train.size < config.training.minSamples -> "Za mało dostępnych próbek treningowych"
                classes.any { c -> train.none { it.outcome == c } } -> "Brak jednej z klas treningowych"
                else -> null
            }
            if (reason != null) {
                skipped += SkippedFold(number, reason)
            } else {
                val model = DirectionModel.train(train, datasetId,
                    config.training, checkCancelled).model
                check(model.trainedThroughMs < cutoff)
                val baseline = classes.maxByOrNull { c -> train.count { it.outcome == c } }!!
                val rows = test.map { sample ->
                    checkCancelled()
                    val p = model.predict(sample)
                    Forecast(number, sample.signalAtMs, model.trainedThroughMs,
                        sample.outcome, p.outcome, baseline, p.up, p.down, p.neutral)
                }
                forecasts += rows
                folds += Fold(number, train.size, earlier.size - available.size,
                    test.first().signalAtMs, test.last().signalAtMs,
                    model.trainedThroughMs, rows.size, metrics(rows))
            }
            cursor = end
            number++
        }
        checkCancelled()
        return Report(folds, forecasts, skipped, metrics(forecasts))
    }

    private fun metrics(rows: List<Forecast>): Metrics? {
        val evaluated = rows.filter { it.actual in classes }
        if (evaluated.isEmpty()) return null
        var correct = 0
        var baselineCorrect = 0
        var loss = 0.0
        var brier = 0.0
        val confusion = Array(3) { IntArray(3) }
        evaluated.forEach { row ->
            val actual = classes.indexOf(row.actual)
            val predicted = classes.indexOf(row.predicted)
            val p = doubleArrayOf(row.up, row.down, row.neutral)
            require(predicted >= 0 && p.all { it.isFinite() && it in 0.0..1.0 })
            require(kotlin.math.abs(p.sum() - 1.0) < 1e-6)
            if (actual == predicted) correct++
            if (row.baseline == row.actual) baselineCorrect++
            confusion[actual][predicted]++
            loss -= ln(p[actual].coerceAtLeast(1e-12))
            for (k in p.indices) {
                val error = p[k] - if (k == actual) 1.0 else 0.0
                brier += error * error
            }
        }
        val n = evaluated.size
        return Metrics(n, correct.toDouble() / n, baselineCorrect.toDouble() / n,
            loss / n, brier / n, confusion.map { it.toList() })
    }

    fun summary(report: Report): String = buildString {
        appendLine("Okna wykonane: ${report.folds.size}; pominięte: ${report.skipped.size}")
        appendLine("Wszystkie prognozy: ${report.forecasts.size}")
        val m = report.aggregate
        if (m == null) {
            appendLine("Brak rozstrzygniętych próbek do metryk klasyfikacji")
        } else {
            appendLine("Ocenione klasyfikacyjnie: ${m.count}")
            appendLine(String.format(Locale.ROOT,
                "Accuracy: %.4f; baseline: %.4f; log loss: %.4f; Brier: %.4f",
                m.accuracy, m.baselineAccuracy, m.logLoss, m.brierScore))
            appendLine("Macierz: wiersze rzeczywiste, kolumny prognozowane; UP, DOWN, NEUTRAL")
            m.confusion.forEach { appendLine(it.joinToString(",")) }
        }
        report.skipped.forEach { appendLine("Pominięte okno ${it.number}: ${it.reason}") }
    }

    fun writeForecastsCsv(file: File, report: Report) {
        file.bufferedWriter().use { out ->
            out.write("fold,signal_ms,trained_through_ms,actual,predicted,baseline,up,down,neutral\n")
            report.forecasts.forEach { f ->
                out.write(listOf(f.fold, f.signalAtMs, f.trainedThroughMs,
                    f.actual.name, f.predicted.name, f.baseline.name,
                    f.up, f.down, f.neutral).joinToString(",") + "\n")
            }
        }
    }
}
