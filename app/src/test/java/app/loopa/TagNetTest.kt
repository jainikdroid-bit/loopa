package app.loopa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TagNetTest {
    @Test fun `тег рядом с любимыми получает вес, хотя сам не лайкался`() {
        val nodes = mapOf("blonde" to 10.0, "pov" to 8.0)
        val pairs = mapOf("blonde,redhead" to 4.0)
        val act = TagNet.activate(nodes, pairs)
        assertTrue((act["redhead"] ?: 0.0) > 0.0)
        // но меньше самого любимого
        assertTrue(act["redhead"]!! < act["blonde"]!!)
    }

    @Test fun `частая связь переносит больше, чем разовая`() {
        val nodes = mapOf("a" to 10.0)
        val once = TagNet.activate(nodes, mapOf("a,x" to 1.0))["x"]!!
        val often = TagNet.activate(nodes, mapOf("a,x" to 10.0))["x"]!!
        assertTrue(often > once * 2)
    }

    @Test fun `группа любимых тегов усиливает каждый свой тег`() {
        val nodes = mapOf("a" to 5.0, "b" to 5.0, "c" to 5.0)
        val act = TagNet.activate(nodes, mapOf("a,b" to 3.0, "a,c" to 3.0, "b,c" to 3.0))
        assertTrue(act["a"]!! > 5.0)
    }

    @Test fun `нелюбимые соседи и отрицательные связи не переносят ничего`() {
        val act = TagNet.activate(mapOf("bad" to -6.0, "good" to 4.0), mapOf("bad,x" to 5.0, "good,y" to -2.0))
        assertNull(act["x"])
        assertNull(act["y"])
        assertEquals(-6.0, act["bad"]!!, 1e-9)
    }

    @Test fun `учитываются только сильнейшие связи узла`() {
        val pairs = (1..30).associate { "hub,t$it" to it.toDouble() }
        assertEquals(TagNet.FANOUT, TagNet.edges(pairs)["hub"]!!.size)
        assertEquals(30.0, TagNet.edges(pairs)["hub"]!!.first().second, 1e-9)
    }

    @Test fun `ключ связи один для обоих порядков`() {
        assertEquals("a,b", TagNet.pairKey("b", "a"))
        assertEquals("a,b", TagNet.pairKey("a", "b"))
        assertNull(TagNet.pairKey("a", "a"))
        assertNull(TagNet.pairKey("a", ""))
        assertNull(TagNet.pairKey("a,c", "b"))
    }

    @Test fun `пропуск ожидаемого во вкусе штрафуется сильнее, но не больше вдвое`() {
        assertEquals(1.0, TagNet.surprise(0.0), 1e-9)
        assertEquals(1.0, TagNet.surprise(-5.0), 1e-9)
        assertTrue(TagNet.surprise(5.0) > 1.5)
        assertEquals(2.0, TagNet.surprise(1e6), 1e-9)
    }
}
