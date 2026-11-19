package app.loopa

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HotspotsTest {
    /** Спад к концу ролика (как у настоящих данных) и три всплеска на 30 %, 55 % и 80 %. */
    private fun sample(n: Int = 100): IntArray = IntArray(n) { i ->
        val base = 10_000 - i * 60
        base + when (i) { 30, 55, 80 -> 2_000; 29, 31, 54, 56, 79, 81 -> 600; else -> 0 }
    }

    @Test fun `разбор со страницы`() {
        val h = Hotspots.parse("""x "hotspots":[913920346,16564706,"13515988",12313705] y""")
        assertArrayEquals(intArrayOf(913920346, 16564706, 13515988, 12313705), h)
        assertEquals(0, Hotspots.parse("нет данных").size)
    }

    @Test fun `три пика по времени, а не самые просматриваемые отрезки`() {
        val d = 500_000L; val len = 2_000L
        val at = Hotspots.peaks(sample(), d, len)!!
        // отрезок 30 из 100 → 150 с, кусок чуть раньше пика (len/3)
        assertArrayEquals(longArrayOf(150_000 - 666, 275_000 - 666, 400_000 - 666), at)
    }

    @Test fun `ровная статистика без пиков - данных нет`() {
        assertNull(Hotspots.peaks(IntArray(100) { 10_000 - it * 50 }, 500_000, 2_000))
        assertNull(Hotspots.peaks(IntArray(5) { 100 }, 500_000, 2_000))
    }

    @Test fun `запомненные данные дают куски по id`() {
        Hotspots.put("ph:test", sample())
        assertEquals(3, Hotspots.cuts("ph:test", 500_000, 2_000)!!.size)
        assertNull(Hotspots.cuts("ph:none", 500_000, 2_000))
    }
}
