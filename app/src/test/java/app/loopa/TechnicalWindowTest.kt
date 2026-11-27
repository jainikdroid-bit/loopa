package app.loopa

import org.junit.Assert.*
import org.junit.Test

class TechnicalWindowTest {
    @Test fun rejectsArbitraryLabelsAndCapsCounts() {
        val counts = mapOf("first_frame" to 9999)
        assertEquals(counts, TechnicalWindow.increment(counts, "private_query"))
        val full = TechnicalWindow.increment(counts, "playback_error")
        assertEquals(10000, full.values.sum())
        assertEquals(full, TechnicalWindow.increment(full, "first_frame"))
    }
    @Test fun expiresAfterThirtyDaysOrClockRollback() {
        val start = 1000L
        assertFalse(TechnicalWindow.expired(start, start + TechnicalWindow.AGE - 1))
        assertTrue(TechnicalWindow.expired(start, start + TechnicalWindow.AGE))
        assertTrue(TechnicalWindow.expired(start, start - 1))
        assertTrue(TechnicalWindow.expired(0, start))
    }
}
