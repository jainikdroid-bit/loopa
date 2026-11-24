package app.loopa

import org.junit.Assert.*
import org.junit.Test

class RecentTilesTest {
    @Test fun repeatedRefreshAndRestartRotateLeaders() {
        val pool = listOf("a", "b", "c")
        var recent = RecentTiles()
        val leaders = (1..3).map {
            val order = recent.order()
            val leader = pool.minBy { order[it] ?: 0 }
            recent.update(listOf(leader))
            val saved = recent.saved()
            recent = RecentTiles().also { it.restore(saved) }
            leader
        }
        assertEquals(pool, leaders)
    }

    @Test fun stationaryTilesAreNotRepeatedAndHistoryIsBounded() {
        val recent = RecentTiles(2)
        recent.update(listOf("a", "b"))
        assertFalse(recent.update(listOf("a", "b")))
        recent.update(listOf("c"))
        assertEquals(listOf("a", "c"), recent.saved())
    }
}
