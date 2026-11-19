package app.loopa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CacheMapTest {
    @Test fun mergesAdjacentAndOverlappingSpans() {
        val p = CacheMap.parts(listOf(500L to 500L, 0L to 250L, 250L to 100L, 300L to 100L), 1000)
        assertEquals(listOf(CacheMap.Part(0f, 0.4f), CacheMap.Part(0.5f, 1f)), p)
    }

    @Test fun keepsGapsAndClampsToLength() {
        val p = CacheMap.parts(listOf(100L to 100L, 900L to 500L, 2000L to 10L), 1000)
        assertEquals(listOf(CacheMap.Part(0.1f, 0.2f), CacheMap.Part(0.9f, 1f)), p)
    }

    @Test fun unknownLengthOrEmptySpansGiveNothing() {
        assertTrue(CacheMap.parts(listOf(0L to 100L), 0).isEmpty())
        assertTrue(CacheMap.parts(listOf(0L to 0L), 1000).isEmpty())
    }

    @Test fun timesAreProportionalToDuration() {
        val t = CacheMap.times(listOf(CacheMap.Part(0f, 0.25f), CacheMap.Part(0.5f, 1f)), 20_000)
        assertEquals(listOf(0L..5_000L, 10_000L..20_000L), t)
    }

    @Test fun cleanReplacesUnsafeAndTrims() {
        assertEquals("a_b", CacheMap.clean("a / b"))
        assertEquals("Привет_мир", CacheMap.clean("  Привет, мир!! "))
        assertEquals("x.y", CacheMap.clean("..x.y.."))
        assertEquals("", CacheMap.clean("???"))
    }

    @Test fun cleanLimitsLength() {
        val s = CacheMap.clean("a".repeat(100))
        assertEquals(CacheMap.NAME_MAX, s.length)
    }

    @Test fun fileNameUsesAuthorThenTitle() {
        assertEquals("redgifs_SomeUser_abc123.mp4", CacheMap.fileName("redgifs", "SomeUser", "title", "abc123"))
        assertEquals("reddit_My_title_xy.mp4", CacheMap.fileName("reddit", "", "My title\nsecond", "xy"))
        assertEquals("erome_77.mp4", CacheMap.fileName("erome", "", "", "77"))
    }
}
