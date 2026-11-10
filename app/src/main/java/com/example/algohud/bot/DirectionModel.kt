package com.example.algohud.bot

import kotlin.math.exp
import kotlin.math.sqrt

/** Training and prediction only; no execution or Android dependencies. */
object DirectionModel {
    private val classes = listOf(CandleLabeler.Outcome.UP,
        CandleLabeler.Outcome.DOWN, CandleLabeler.Outcome.NEUTRAL)

    data class Config(
        val epochs: Int = 200,
        val learningRate: Double = 0.05,
        val l2: Double = 0.001,
        val minSamples: Int = 60
    ) {
        init {
            require(epochs > 0)
            require(learningRate.isFinite() && learningRate > 0.0)
            require(l2.isFinite() && l2 >= 0.0)
            require(minSamples >= 3)
        }
    }

    data class Prediction(val up: Double, val down: Double, val neutral: Double) {
        val outcome: CandleLabeler.Outcome
            get() = classes[listOf(up, down, neutral).indices.maxByOrNull {
                listOf(up, down, neutral)[it]
            }!!]
    }

    class Model internal constructor(
        val datasetId: String,
        val trainedThroughMs: Long,
        val trainingCount: Int,
        means: DoubleArray,
        scales: DoubleArray,
        weights: Array<DoubleArray>
    ) {
        private val means = means.copyOf()
        private val scales = scales.copyOf()
        private val weights = Array(weights.size) { weights[it].copyOf() }

        fun predict(sample: CandleLabeler.Sample): Prediction {
            val x = vector(features(sample), means, scales)
            val p = probabilities(weights, x)
            return Prediction(p[0], p[1], p[2])
        }
    }

    data class TrainingResult(val model: Model, val excludedAmbiguous: Int)

    private fun features(s: CandleLabeler.Sample): DoubleArray {
        val x = doubleArrayOf(s.bodyAtr, s.rangeAtr, s.return1)
        require(x.all { it.isFinite() }) { "Niepoprawne cechy" }
        return x
    }

    private fun vector(raw: DoubleArray, means: DoubleArray,
                       scales: DoubleArray): DoubleArray =
        doubleArrayOf(1.0, *((raw.indices.map {
            ((raw[it] - means[it]) / scales[it]).coerceIn(-20.0, 20.0)
        }).toDoubleArray()))

    private fun probabilities(w: Array<DoubleArray>, x: DoubleArray): DoubleArray {
        val scores = DoubleArray(3) { k -> x.indices.sumOf { w[k][it] * x[it] } }
        require(scores.all { it.isFinite() }) { "Niestabilne wagi modelu" }
        val peak = scores.maxOrNull()!!
        val p = DoubleArray(3) { exp(scores[it] - peak) }
        val total = p.sum()
        return DoubleArray(3) { p[it] / total }
    }

    fun train(samples: List<CandleLabeler.Sample>, datasetId: String,
              config: Config = Config(),
              checkCancelled: () -> Unit = {}): TrainingResult {
        require(datasetId.isNotBlank())
        val usable = samples.filter { it.outcome in classes }
        require(usable.size >= config.minSamples) { "Za mało próbek treningowych" }
        require(classes.all { c -> usable.any { it.outcome == c } }) {
            "Trening wymaga próbek wszystkich trzech klas"
        }
        require(usable.all { it.labelEndMs >= it.signalAtMs })
        val raw = usable.map { features(it) }
        val means = DoubleArray(3) { j -> raw.sumOf { it[j] / raw.size } }
        val scales = DoubleArray(3) { j ->
            sqrt(raw.sumOf { val d = it[j] - means[j]; d * d / raw.size })
                .coerceAtLeast(1e-9)
        }
        require(means.all { it.isFinite() } && scales.all { it.isFinite() })
        val xs = raw.map { vector(it, means, scales) }
        val ys = usable.map { classes.indexOf(it.outcome) }
        val w = Array(3) { DoubleArray(4) }
        repeat(config.epochs) {
            checkCancelled()
            val gradient = Array(3) { DoubleArray(4) }
            xs.forEachIndexed { i, x ->
                checkCancelled()
                val p = probabilities(w, x)
                for (k in 0..2) for (j in 0..3) {
                    gradient[k][j] += (p[k] - if (ys[i] == k) 1.0 else 0.0) *
                        x[j] / xs.size
                }
            }
            for (k in 0..2) for (j in 0..3) {
                val penalty = if (j == 0) 0.0 else config.l2 * w[k][j]
                w[k][j] -= config.learningRate * (gradient[k][j] + penalty)
                require(w[k][j].isFinite()) { "Trening rozbieżny" }
            }
        }
        checkCancelled()
        return TrainingResult(Model(datasetId, usable.maxOf { it.labelEndMs },
            usable.size, means, scales, w), samples.size - usable.size)
    }
}
