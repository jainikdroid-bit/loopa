package app.loopa

import kotlin.math.ln

internal data class ReplaySample(val features: List<Pair<String, Float>>, val label: Float, val weight: Float, val video: Boolean)
internal data class ReplayMetrics(val train: Int, val test: Int, val loss: Double, val baseline: Double, val brier: Double)

/** Chronological evaluation: no test sample may update the candidate. Synthetic or local data only. */
internal object ReplayEvaluator {
    private fun loss(y: Double, p: Double): Double {
        val safe = p.coerceIn(1e-6, 1.0 - 1e-6)
        return -y * ln(safe) - (1.0-y)*ln(1.0-safe)
    }
    fun run(samples: List<ReplaySample>, active: () -> Boolean = { true }): ReplayMetrics? {
        val valid = samples.filter { it.features.isNotEmpty() && it.features.all { f -> f.second.isFinite() } &&
            it.label.isFinite() && it.label in 0f..1f && it.weight.isFinite() && it.weight > 0f }
        if (valid.size < 100) return null
        val split = valid.size * 4 / 5
        val train = valid.take(split)
        val test = valid.drop(split).filter { it.video }
        if (test.none { it.label == 0f } || test.none { it.label == 1f }) return null
        val prior = (train.sumOf { it.label.toDouble() } + 1.0) / (train.size + 2.0)
        val model = FmModel()
        for (s in train) {
            if (!active()) return null
            model.learn(s.features, s.label, s.weight, s.video)
        }
        var l = 0.0; var base = 0.0; var brier = 0.0
        for (s in test) {
            if (!active()) return null
            val y = s.label.toDouble()
            val p = model.predict(s.features)
            l += loss(y, p); base += loss(y, prior); brier += (p-y)*(p-y)
        }
        return ReplayMetrics(train.size, test.size, l/test.size, base/test.size, brier/test.size)
    }
}
