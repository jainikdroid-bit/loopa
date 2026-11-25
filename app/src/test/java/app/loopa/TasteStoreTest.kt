package app.loopa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TasteStoreTest {
    @Test fun skipsCountUpToFourAndForget() {
        val log = SkipLog()
        repeat(6) { log.bump("a", it.toLong()) }
        assertEquals(4, log.count("a"))
        assertTrue(log.remove("a"))
        assertFalse(log.remove("a"))
        assertEquals(0, log.count("a"))
    }

    @Test fun skipLogEvictsOldestAndKeepsRefreshed() {
        val log = SkipLog(capacity = 800)
        for (i in 0 until 900) {
            log.bump("id$i", i.toLong())
            log.bump("id0", i.toLong()) // самый первый постоянно освежаем — он не должен уйти
        }
        assertEquals(800, log.size)
        assertTrue(log.count("id0") > 0)
        assertEquals(0, log.count("id1"))
        assertTrue(log.count("id899") > 0)
    }

    @Test fun queryProfileKeepsAboutHundredWeights() {
        val profile = QueryProfile()
        for (i in 0 until 300) {
            profile.choose(mapOf("tag:t$i" to 0.5, "pair:3:a$i" to 0.5), mapOf("tag:other" to 0.5), 1.0)
        }
        assertTrue(profile.weights.size <= QueryProfile.MAX_SINGLES + QueryProfile.MAX_PAIRS)
        assertTrue(profile.weights.keys.count { it.startsWith("pair:") } <= QueryProfile.MAX_PAIRS)
        assertTrue("tag:t299" in profile.weights)
        assertFalse("tag:t0" in profile.weights)
    }
}
