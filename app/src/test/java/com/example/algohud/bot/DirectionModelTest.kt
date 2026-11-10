package com.example.algohud.bot

import org.junit.Assert.*
import org.junit.Test

class DirectionModelTest {
    private fun sample(i: Int, outcome: CandleLabeler.Outcome,
                       body: Double) = CandleLabeler.Sample(
        (i + 1) * 60_000L, (i + 2) * 60_000L,
        1.0, body, 2.0, body / 100.0, outcome)

    private fun training() = (0 until 30).map { i ->
        when (i % 3) {
            0 -> sample(i, CandleLabeler.Outcome.UP, 1.0)
            1 -> sample(i, CandleLabeler.Outcome.DOWN, -1.0)
            else -> sample(i, CandleLabeler.Outcome.NEUTRAL, 0.0)
        }
    }

    private val config = DirectionModel.Config(minSamples = 3)

    @Test fun probabilitiesAreFiniteAndSumToOne() {
        val model = DirectionModel.train(training(), "test", config).model
        val p = model.predict(training().first())
        assertTrue(listOf(p.up, p.down, p.neutral).all { it.isFinite() && it in 0.0..1.0 })
        assertEquals(1.0, p.up + p.down + p.neutral, 1e-12)
    }

    @Test fun simpleSeparablePatternsAreLearned() {
        val rows = training()
        val model = DirectionModel.train(rows, "test", config).model
        rows.forEach { assertEquals(it.outcome, model.predict(it).outcome) }
    }

    @Test fun trainingIsDeterministic() {
        val rows = training()
        val a = DirectionModel.train(rows, "test", config).model.predict(rows[0])
        val b = DirectionModel.train(rows, "test", config).model.predict(rows[0])
        assertEquals(a, b)
    }

    @Test fun ambiguousRowsAreExcludedFromTrainingButCanBePredicted() {
        val ambiguous = sample(100, CandleLabeler.Outcome.AMBIGUOUS, 0.5)
        val base = DirectionModel.train(training(), "test", config)
        val added = DirectionModel.train(training() + ambiguous, "test", config)
        assertEquals(1, added.excludedAmbiguous)
        assertEquals(30, added.model.trainingCount)
        assertEquals(base.model.trainedThroughMs, added.model.trainedThroughMs)
        assertEquals(base.model.predict(ambiguous), added.model.predict(ambiguous))
    }

    @Test(expected = IllegalArgumentException::class)
    fun missingClassesRejectTraining() {
        DirectionModel.train(training().filter {
            it.outcome == CandleLabeler.Outcome.UP
        }, "test", config)
    }

    @Test(expected = IllegalStateException::class)
    fun cancellationIsPropagated() {
        DirectionModel.train(training(), "test", config) {
            throw IllegalStateException("cancelled")
        }
    }
}
