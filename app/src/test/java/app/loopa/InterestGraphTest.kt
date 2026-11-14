package app.loopa

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InterestGraphTest {
    /** Две плотные группы и один тег, что пришёл только от соседей. */
    private val own = mapOf("a1" to 9.0, "a2" to 8.0, "a3" to 7.0, "b1" to 5.0, "b2" to 4.0, "b3" to 3.0)
    private val pairs = mapOf(
        "a1,a2" to 5.0, "a1,a3" to 4.0, "a2,a3" to 4.0, "b1,b2" to 3.0, "b1,b3" to 3.0, "b2,b3" to 2.0,
        "a3,b1" to 0.2, "a1,s" to 1.0)
    private val act get() = TagNet.activate(own, pairs)

    private fun graph() = JSONObject(InterestGraph.build(own, pairs, act))

    @Test fun `узлы — по убыванию активации, чужой тег помечен suggested`() {
        val g = graph()
        val nodes = g.getJSONArray("nodes")
        val ids = List(nodes.length()) { nodes.getJSONObject(it).getString("id") }
        assertEquals("a1", ids.first())
        val s = (0 until nodes.length()).map { nodes.getJSONObject(it) }.first { it.getString("id") == "s" }
        assertTrue(s.getBoolean("suggested"))
        assertFalse(nodes.getJSONObject(0).getBoolean("suggested"))
    }

    @Test fun `слабые и пустые теги не попадают, число узлов ограничено`() {
        val many = (1..300).associate { "t%03d".format(it) to 1.0 + it / 100.0 }
        val nodes = InterestGraph.nodes(many, many + ("low" to 0.2) + ("x,y" to 5.0))
        assertEquals(InterestGraph.MAX_NODES, nodes.size)
        assertTrue(nodes.none { it.id == "low" || ',' in it.id })
        assertEquals("t300", nodes.first().id)
    }

    @Test fun `связи только между выбранными узлами и не больше FANOUT на узел`() {
        val hub = (1..30).associate { "n%02d".format(it) to 2.0 } + ("hub" to 9.0)
        val pr = (1..30).associate { "hub,n%02d".format(it) to 1.0 + it } + ("hub,ghost" to 50.0) + ("n01,n02" to -1.0)
        val ns = InterestGraph.nodes(hub, hub)
        val ls = InterestGraph.links(ns, pr)
        assertTrue(ls.none { it.a == "ghost" || it.b == "ghost" })
        assertTrue(ls.none { it.w <= 0 })
        val deg = HashMap<String, Int>()
        ls.forEach { deg.merge(it.a, 1, Int::plus); deg.merge(it.b, 1, Int::plus) }
        assertTrue(deg.values.all { it <= TagNet.FANOUT })
        assertEquals(TagNet.FANOUT, deg["hub"])
        // у хаба остались сильнейшие
        assertTrue(ls.any { it.b == "n30" || it.a == "n30" })
    }

    @Test fun `две группы — два сообщества, нумерация по активации, цвета из палитры`() {
        val g = graph()
        val cs = g.getJSONArray("communities")
        val nodes = g.getJSONArray("nodes")
        fun c(id: String) = (0 until nodes.length()).map { nodes.getJSONObject(it) }.first { it.getString("id") == id }.getInt("c")
        assertEquals(c("a1"), c("a2")); assertEquals(c("a1"), c("a3")); assertEquals(c("a1"), c("s"))
        assertEquals(c("b1"), c("b2")); assertEquals(c("b1"), c("b3"))
        assertEquals(0, c("a1")); assertEquals(1, c("b1"))
        assertEquals(2, cs.length())
        assertEquals("a1", cs.getJSONObject(0).getString("name"))
        assertEquals("#4E79A7", cs.getJSONObject(0).getString("color"))
        assertEquals("#F28E2B", cs.getJSONObject(1).getString("color"))
        assertEquals(4, cs.getJSONObject(0).getInt("size"))
    }

    @Test fun `результат детерминирован`() {
        assertEquals(InterestGraph.build(own, pairs, act), InterestGraph.build(own, pairs, act))
    }

    @Test fun `цвета идут по кругу`() {
        val ids = (1..12).map { "iso$it" }
        val m = ids.associateWith { 2.0 }
        val cs = JSONObject(InterestGraph.build(m, emptyMap(), m)).getJSONArray("communities")
        assertEquals(12, cs.length())
        assertEquals(cs.getJSONObject(0).getString("color"), cs.getJSONObject(10).getString("color"))
    }

    @Test fun `пустой вход — пустой граф`() {
        val g = JSONObject(InterestGraph.build(emptyMap(), emptyMap(), emptyMap()))
        assertEquals(0, g.getJSONArray("nodes").length())
        assertEquals(0, g.getJSONArray("communities").length())
    }

    @Test fun `тег с ручной поправкой остаётся в графе и несёт boost`() {
        val weak = act + ("z" to 0.05)
        val g = JSONObject(InterestGraph.build(own, pairs, weak, mapOf("z" to 0.1)))
        val ns = g.getJSONArray("nodes")
        val z = (0 until ns.length()).map { ns.getJSONObject(it) }.first { it.getString("id") == "z" }
        assertEquals(0.1, z.getDouble("boost"), 1e-9)
        assertFalse(z.getBoolean("suggested"))
    }
}
