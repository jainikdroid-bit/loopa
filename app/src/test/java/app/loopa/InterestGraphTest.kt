package app.loopa

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InterestGraphTest {
    /** Две плотные группы и один тег, что пришёл только от соседей. */
    private val own = mapOf("tag:a1" to 9.0, "tag:a2" to 8.0, "tag:a3" to 7.0, "tag:b1" to 5.0, "tag:b2" to 4.0, "tag:b3" to 3.0)
    private val pairs = mapOf(
        "tag:a1|tag:a2" to 5.0, "tag:a1|tag:a3" to 4.0, "tag:a2|tag:a3" to 4.0, "tag:b1|tag:b2" to 3.0, "tag:b1|tag:b3" to 3.0,
        "tag:b2|tag:b3" to 2.0, "tag:a3|tag:b1" to 0.2, "tag:a1|tag:s" to 1.0)
    private val act get() = TagNet.activate(own, pairs)

    private fun graph() = JSONObject(InterestGraph.build(own, pairs, act))
    private fun JSONObject.nodeList() = getJSONArray("nodes").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }

    @Test fun `узлы — по убыванию активации, чужой тег помечен suggested`() {
        val nodes = graph().nodeList()
        assertEquals("tag:a1", nodes.first().getString("id"))
        assertTrue(nodes.first { it.getString("id") == "tag:s" }.getBoolean("suggested"))
        assertFalse(nodes.first().getBoolean("suggested"))
    }

    @Test fun `у узла есть тип и имя без префикса`() {
        val mixed = mapOf("tag:blonde" to 5.0, "user:anna" to 4.0, "sub:gonewild" to 3.0, "kw:sunset" to 2.0, "q:big blonde" to 2.5, "site:erome" to 1.0)
        val nodes = JSONObject(InterestGraph.build(mixed, emptyMap(), mixed)).nodeList()
        assertEquals(6, nodes.size)
        val byId = nodes.associateBy { it.getString("id") }
        assertEquals("user", byId.getValue("user:anna").getString("t")); assertEquals("anna", byId.getValue("user:anna").getString("name"))
        assertEquals("q", byId.getValue("q:big blonde").getString("t")); assertEquals("big blonde", byId.getValue("q:big blonde").getString("name"))
        assertEquals("site", byId.getValue("site:erome").getString("t")); assertEquals("erome", byId.getValue("site:erome").getString("name"))
        assertEquals("sub", byId.getValue("sub:gonewild").getString("t")); assertEquals("kw", byId.getValue("kw:sunset").getString("t"))
    }

    @Test fun `двоеточие внутри термина не ломает тип и имя`() {
        val m = mapOf("q:a: b" to 3.0)
        val n = JSONObject(InterestGraph.build(m, emptyMap(), m)).nodeList().single()
        assertEquals("q", n.getString("t")); assertEquals("a: b", n.getString("name"))
    }

    @Test fun `слабые узлы не попадают, число узлов ограничено`() {
        val many = (1..2200).associate { "tag:t%04d".format(it) to 1.0 + it / 100.0 }
        val nodes = InterestGraph.nodes(many, many + ("tag:low" to 0.2))
        assertEquals(2000, InterestGraph.MAX_NODES)
        assertEquals(InterestGraph.MAX_NODES, nodes.size)
        assertTrue(nodes.none { it.id == "tag:low" })
        assertEquals("tag:t2200", nodes.first().id)
    }

    @Test fun `связи только между выбранными узлами и не больше FANOUT на узел`() {
        val hub = (1..30).associate { "tag:n%02d".format(it) to 2.0 } + ("tag:hub" to 9.0)
        val pr = (1..30).associate { "tag:hub|tag:n%02d".format(it) to 1.0 + it } + ("tag:hub|tag:ghost" to 50.0) + ("tag:n01|tag:n02" to -1.0)
        val ns = InterestGraph.nodes(hub, hub)
        val ls = InterestGraph.links(ns, pr)
        assertTrue(ls.none { it.a == "tag:ghost" || it.b == "tag:ghost" })
        assertTrue(ls.none { it.w <= 0 })
        val deg = HashMap<String, Int>()
        ls.forEach { deg.merge(it.a, 1, Int::plus); deg.merge(it.b, 1, Int::plus) }
        assertTrue(deg.values.all { it <= TagNet.FANOUT })
        assertEquals(TagNet.FANOUT, deg["tag:hub"])
        // у хаба остались сильнейшие
        assertTrue(ls.any { it.b == "tag:n30" || it.a == "tag:n30" })
    }

    @Test fun `связь между типами попадает в граф, и узлы разных типов сливаются в сообщество`() {
        val nodes = own + mapOf("user:anna" to 3.0)
        val links = pairs + mapOf("tag:a1|user:anna" to 3.0, "tag:a2|user:anna" to 2.0)
        val g = JSONObject(InterestGraph.build(nodes, links, TagNet.activate(nodes, links)))
        val ls = g.getJSONArray("links")
        val ends = (0 until ls.length()).map { ls.getJSONObject(it) }.map { it.getString("source") to it.getString("target") }
        assertTrue(ends.any { it == ("tag:a1" to "user:anna") || it == ("user:anna" to "tag:a1") })
        val ns = g.nodeList().associateBy { it.getString("id") }
        assertEquals(ns.getValue("tag:a1").getInt("c"), ns.getValue("user:anna").getInt("c"))
    }

    @Test fun `две группы — два сообщества, нумерация по активации, цвета из палитры`() {
        val g = graph()
        val cs = g.getJSONArray("communities")
        val ns = g.nodeList().associateBy { it.getString("id") }
        fun c(id: String) = ns.getValue("tag:$id").getInt("c")
        assertEquals(c("a1"), c("a2")); assertEquals(c("a1"), c("a3")); assertEquals(c("a1"), c("s"))
        assertEquals(c("b1"), c("b2")); assertEquals(c("b1"), c("b3"))
        assertEquals(0, c("a1")); assertEquals(1, c("b1"))
        assertEquals(2, cs.length())
        // имя сообщества — имя самого тяжёлого узла, без префикса, и его тип
        assertEquals("a1", cs.getJSONObject(0).getString("name"))
        assertEquals("tag", cs.getJSONObject(0).getString("t"))
        assertEquals("#4E79A7", cs.getJSONObject(0).getString("color"))
        assertEquals("#F28E2B", cs.getJSONObject(1).getString("color"))
        assertEquals(4, cs.getJSONObject(0).getInt("size"))
    }

    @Test fun `результат детерминирован`() {
        assertEquals(InterestGraph.build(own, pairs, act), InterestGraph.build(own, pairs, act))
    }

    @Test fun `цвета идут по кругу`() {
        val m = (1..12).associate { "tag:iso$it" to 2.0 }
        val cs = JSONObject(InterestGraph.build(m, emptyMap(), m)).getJSONArray("communities")
        assertEquals(12, cs.length())
        assertEquals(cs.getJSONObject(0).getString("color"), cs.getJSONObject(10).getString("color"))
    }

    @Test fun `пустой вход — пустой граф`() {
        val g = JSONObject(InterestGraph.build(emptyMap(), emptyMap(), emptyMap()))
        assertEquals(0, g.getJSONArray("nodes").length())
        assertEquals(0, g.getJSONArray("communities").length())
    }

    @Test fun `узел с ручной поправкой остаётся в графе и несёт boost`() {
        val weak = act + ("user:z" to 0.05)
        val g = JSONObject(InterestGraph.build(own, pairs, weak, mapOf("user:z" to 0.1)))
        val z = g.nodeList().first { it.getString("id") == "user:z" }
        assertEquals(0.1, z.getDouble("boost"), 1e-9)
        assertEquals("user", z.getString("t"))
        assertFalse(z.getBoolean("suggested"))
    }
}
