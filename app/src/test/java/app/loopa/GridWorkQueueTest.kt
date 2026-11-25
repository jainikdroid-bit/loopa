package app.loopa

import org.junit.Assert.*
import org.junit.Test

class GridWorkQueueTest {
    @Test fun foregroundEvictsSpeculationBeforeForeground() {
        val queue = GridWorkQueue<String>(3)
        queue.offer("old", true)
        queue.offer("ahead", false)
        queue.offer("new", true)
        assertEquals(listOf("ahead"), queue.offer("latest", true))
        assertEquals("latest", queue.poll(true))
        assertEquals("new", queue.poll(true))
        assertEquals("old", queue.poll(true))
        assertNull(queue.poll(false))
    }
    @Test fun duplicatePromotionAndCancellation() {
        val queue = GridWorkQueue<String>(3)
        queue.offer("a", false)
        queue.offer("a", true)
        queue.offer("a", false)
        assertEquals(1, queue.size)
        assertNull(queue.poll(false))
        assertEquals("a", queue.poll(true))
        queue.offer("b", false)
        queue.remove("b")
        assertNull(queue.poll(false))
    }
    @Test fun longScrollCannotGrowQueue() {
        val queue = GridWorkQueue<Int>(64)
        repeat(10_000) { queue.offer(it, it % 3 == 0); assertTrue(queue.size <= 64) }
        assertEquals(64, queue.size)
    }
}
