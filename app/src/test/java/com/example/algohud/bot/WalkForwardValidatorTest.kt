package com.example.algohud.bot

import org.junit.Assert.*
import org.junit.Test

class WalkForwardValidatorTest {
    private val tf = 60_000L
    private val classes = listOf(CandleLabeler.Outcome.UP,
        CandleLabeler.Outcome.DOWN, CandleLabeler.Outcome.NEUTRAL)
    private fun rows(n: Int = 24) = (0 until n).map { i ->
        val body = when (i % 3) { 0 -> 1.0; 1 -> -1.0; else -> 0.0 }
        CandleLabeler.Sample((i + 1) * tf, (i + 3) * tf,
            1.0, body, 2.0, body / 100.0, classes[i % 3])
    }
    private fun config(embargo: Long = 0L) = WalkForwardValidator.Config(
        initialTrainSamples = 9, testSamples = 3, embargoMs = embargo,
        training = DirectionModel.Config(epochs = 20, minSamples = 3))

    @Test fun overlappingTrainingLabelsArePurged() {
        val report = WalkForwardValidator.validate(rows(), "test", config())
        assertEquals(5, report.folds.size)
        assertEquals(7, report.folds.first().trainCount)
        assertEquals(2, report.folds.first().purgedCount)
        report.folds.forEach { assertTrue(it.trainedThroughMs < it.testStartMs) }
        assertEquals(15, report.aggregate!!.count)
    }

    @Test fun embargoAddsTemporalSeparation() {
        val report = WalkForwardValidator.validate(rows(), "test", config(tf))
        assertEquals(6, report.folds.first().trainCount)
        assertEquals(3, report.folds.first().purgedCount)
        report.folds.forEach { assertTrue(it.trainedThroughMs < it.testStartMs - tf) }
    }

    @Test fun ambiguousForecastsAreRetainedButNotScoredAsClasses() {
        val data = rows().mapIndexed { i, s ->
            if (i >= 9) s.copy(outcome = CandleLabeler.Outcome.AMBIGUOUS) else s
        }
        val report = WalkForwardValidator.validate(data, "test", config())
        assertEquals(15, report.forecasts.size)
        assertTrue(report.forecasts.all { it.actual == CandleLabeler.Outcome.AMBIGUOUS })
        assertNull(report.aggregate)
        assertTrue(report.folds.all { it.metrics == null })
    }

    @Test fun changingFutureOutcomesDoesNotChangeFirstFoldPredictions() {
        val original = rows()
        val changed = original.mapIndexed { i, s ->
            if (i >= 9) s.copy(outcome = CandleLabeler.Outcome.DOWN) else s
        }
        val a = WalkForwardValidator.validate(original, "test", config())
            .forecasts.filter { it.fold == 1 }
        val b = WalkForwardValidator.validate(changed, "test", config())
            .forecasts.filter { it.fold == 1 }
        assertEquals(a.map { listOf(it.up, it.down, it.neutral) },
            b.map { listOf(it.up, it.down, it.neutral) })
        assertEquals(a.map { it.baseline }, b.map { it.baseline })
    }

    @Test fun testSignalsAreUniqueAndFinalPartialWindowIsIncluded() {
        val report = WalkForwardValidator.validate(rows(23), "test", config())
        val times = report.forecasts.map { it.signalAtMs }
        assertEquals(14, times.size)
        assertEquals(times.size, times.toSet().size)
        assertEquals(times.sorted(), times)
        assertEquals(2, report.folds.last().forecastCount)
    }

    @Test fun missingTrainingClassesAreReportedInsteadOfFabricated() {
        val data = rows().map { it.copy(outcome = CandleLabeler.Outcome.UP) }
        val report = WalkForwardValidator.validate(data, "test", config())
        assertTrue(report.forecasts.isEmpty())
        assertEquals(5, report.skipped.size)
        assertNull(report.aggregate)
    }

    @Test(expected = IllegalStateException::class)
    fun cancellationIsPropagated() {
        WalkForwardValidator.validate(rows(), "test", config()) {
            throw IllegalStateException("cancelled")
        }
    }
}
