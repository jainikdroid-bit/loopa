package app.loopa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ForecastTest {
    // сетка 2 столбца, ряд 500 px, экран 2000 px (4 ряда = 8 ячеек), видно 0..7
    private fun f(first: Int = 0, last: Int = 7, v: Float = 0f, fling: Int = 0, count: Int = 200, budget: Int = 4) =
        Forecaster.forecast(first, last, 2, 500, 2000, v, fling, count, budget)

    @Test fun flingDownStopsWhereTheFlingEnds() {
        // бросок на 5000 px = 10 рядов = 20 ячеек вниз
        val r = f(fling = 5000)
        assertEquals(20..27, r.stop)
        assertEquals((20..27).toList(), r.thumbs.take(8))
    }

    @Test fun thumbsCoverMarginBeyondStopThenFlownOver() {
        val r = f(fling = 5000)
        // запас — экран за остановкой
        assertEquals((28..35).toList(), r.thumbs.subList(8, 16))
        // затем пролетаемое, от остановки к текущему месту
        assertEquals((19 downTo 8).toList(), r.thumbs.subList(16, r.thumbs.size))
        assertFalse(r.thumbs.any { it in 0..7 })
    }

    @Test fun flingUpGoesBackwards() {
        val r = f(first = 40, last = 47, fling = -3000)
        assertEquals(28..35, r.stop)
        assertEquals((27 downTo 20).toList(), r.thumbs.subList(8, 16))
        assertEquals((36..39).toList(), r.thumbs.subList(16, r.thumbs.size))
        assertFalse(r.more)
    }

    @Test fun withoutFlingUsesVelocityOverHorizon() {
        // 1000 px/с × 2 с = 2000 px = 4 ряда = 8 ячеек
        assertEquals(8..15, f(v = 1000f).stop)
        // стоим — остановка там же, запас — экран ниже
        val still = f()
        assertEquals(0..7, still.stop)
        assertEquals((8..15).toList(), still.thumbs.subList(8, 16))
    }

    @Test fun videoAtStopCentreFirstWithinBudget() {
        val r = f(fling = 5000, budget = 2)
        assertEquals(2, r.video.size)
        assertTrue(r.video.all { it in 23..24 })
        assertEquals(emptyList<Int>(), f(fling = 5000, budget = 0).video)
    }

    @Test fun stopClampedToFeedEnd() {
        val r = f(first = 180, last = 187, fling = 50_000)
        assertEquals(199..199, r.stop)
        assertTrue(r.more)
    }

    @Test fun asksNextPageThreeScreensBeforeEnd() {
        // 3 экрана = 24 ячейки: остановка на 20..27, конец 50 → 27 + 24 = 51 ≥ 49
        assertTrue(f(fling = 5000, count = 50).more)
        assertFalse(f(fling = 5000, count = 100).more)
    }

    @Test fun longFlingCapsThumbCount() {
        val r = f(fling = 200_000, count = 10_000)
        assertEquals(Forecaster.MAX_THUMBS, r.thumbs.size)
    }

    @Test fun emptyFeedAsksForMore() {
        val r = Forecaster.forecast(-1, -1, 2, 500, 2000, 0f, 0, 0, 4)
        assertTrue(r.thumbs.isEmpty())
        assertTrue(r.more)
    }

    @Test fun thumbBudgetByNetwork() {
        assertEquals(32, Forecaster.thumbBudget(NetKind.FAST, 8))
        assertEquals(16, Forecaster.thumbBudget(NetKind.METERED, 8))
        assertEquals(8, Forecaster.thumbBudget(NetKind.SAVER, 8))
        assertEquals(Forecaster.MAX_THUMBS, Forecaster.thumbBudget(NetKind.FAST, 1000))
    }

    @Test fun videoBudgetByNetwork() {
        assertEquals(4, Forecaster.videoBudget(NetKind.FAST, 2))
        assertEquals(2, Forecaster.videoBudget(NetKind.METERED, 2))
        assertEquals(0, Forecaster.videoBudget(NetKind.SAVER, 2))
        assertEquals(0, Forecaster.videoBudget(NetKind.SLOW, 2))
    }
}
