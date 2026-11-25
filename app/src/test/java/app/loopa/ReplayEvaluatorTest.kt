package app.loopa

import org.junit.Assert.*
import org.junit.Test

class ReplayEvaluatorTest {
    private fun rows(n: Int) = List(n) { i ->
        val y = if (i % 2 == 0) 1f else 0f
        ReplaySample(listOf((if (y == 1f) "tag:alpha" else "tag:beta") to 1f), y, 1f, true)
    }
    @Test fun minimumSamples() { assertNull(ReplayEvaluator.run(rows(99))) }
    @Test fun candidateBeatsConstantOnSyntheticData() {
        val result = ReplayEvaluator.run(rows(400))!!
        assertEquals(320, result.train)
        assertEquals(80, result.test)
        assertTrue(result.loss < result.baseline)
    }
    @Test fun cancelled() { assertNull(ReplayEvaluator.run(rows(100)) { false }) }
    @Test fun oneClassHoldoutRejected() {
        assertNull(ReplayEvaluator.run(rows(80) + List(20) { rows(1).first() }))
    }
}
