package app.loopa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RelevanceTest {
    private fun gif(id: String, tags: List<String> = emptyList(), title: String = "", site: String = "", sub: String = "", likes: Long = 0) =
        Gif(id, "", id, "", 0, 0, 10.0, "", tags, false, 0, likes, title, sub, site = site)

    private fun score(g: Gif, q: String, syn: List<String> = emptyList(), alias: List<String> = emptyList()) = Relevance.score(g, q, alias, syn)

    @Test fun `однословный запрос - слово в теге целым словом или с окончанием`() {
        assertEquals(6, Relevance.level(score(gif("a", tags = listOf("Twin")), "twin")))
        assertEquals(6, Relevance.level(score(gif("b", tags = listOf("Twins")), "twin")))
    }

    @Test fun `однословный запрос - слово в названии ниже тега но показывается`() {
        val inTitle = score(gif("a", title = "Hot twin sisters"), "twin")
        val inTag = score(gif("b", tags = listOf("twin")), "twin")
        assertTrue(inTitle > 0 && inTitle < inTag)
    }

    @Test fun `однословный запрос - по корню и внутри слова похожее, ниже точного`() {
        // «twink» и «doorknob» — не то же слово: в выдаче, но в конце (уровень «похожее»)
        val inside = score(gif("a", tags = listOf("twink")), "twin")
        val root = score(gif("c", tags = listOf("fist")), "fisting")
        assertEquals(2, Relevance.level(inside))
        assertEquals(2, Relevance.level(root))
        assertTrue(root > inside)
        assertEquals(2, Relevance.level(score(gif("b", title = "doorknob review"), "knob")))
        assertTrue(score(gif("d", title = "knob"), "knob") > root && score(gif("f", tags = listOf("fisting")), "fisting") > root)
        assertEquals(0.0, score(gif("e", tags = listOf("cat")), "knob"), 0.0)
    }

    @Test fun `одна и та же оценка для любого источника`() {
        val tags = listOf("standing", "dildo")
        val s = listOf(gif("r", tags), gif("x", tags, site = "xvideos"), gif("p", tags, site = "pornhub"))
            .map { score(it, "standing dildo") }
        assertEquals(1, s.toSet().size)
        assertTrue(s[0] > 0)
        // у xVideos нет тегов — по названию, и это тот же уровень, что в названии у RedGIFs
        assertEquals(score(gif("t1", title = "Standing dildo ride"), "standing dildo"), score(gif("t2", title = "Standing dildo ride", site = "xvideos"), "standing dildo"), 0.0)
    }

    @Test fun `многословный запрос - не все слова только похожее для любого источника`() {
        for (site in listOf("", "xvideos", "pornhub", "erome")) {
            assertEquals(site, 2, Relevance.level(score(gif("a", tags = listOf("bondage"), title = "bondage fun", site = site), "bondage gear")))
        }
        // меньше половины слов — мимо
        assertEquals(0.0, score(gif("z", tags = listOf("red")), "red latex bondage gear"), 0.0)
        assertTrue(score(gif("b", tags = listOf("Bondage Gear")), "bondage gear") >= 12)
    }

    @Test fun `многословный запрос - все слова врозь ниже всех слов в одном теге`() {
        val oneTag = score(gif("a", tags = listOf("Standing Dildo")), "dildo standing")
        val apart = score(gif("b", tags = listOf("Standing Doggy", "Dildo")), "dildo standing")
        assertTrue(oneTag >= 12 && apart in 9.0..10.0)
        assertEquals(5, Relevance.level(oneTag))
        assertEquals(4, Relevance.level(apart))
        assertTrue(Relevance.exact(apart))
    }

    @Test fun `часть слов ниже синонима, синоним ниже точного`() {
        val partial = score(gif("r", title = "bondage party", sub = "bondage"), "bondage gear")
        val syn = score(gif("s", title = "harness ride"), "bondage gear", syn = listOf("harness"))
        val exact = score(gif("e", tags = listOf("bondage", "gear")), "bondage gear")
        assertTrue(partial in 1.0..4.0 && syn in 4.0..6.0 && exact >= 9)
    }

    @Test fun `синоним показывается всегда, но после точных`() {
        val s = score(gif("a", title = "doorknob ride"), "knob", syn = listOf("doorknob"))
        assertEquals(3, Relevance.level(s))
        assertTrue(Relevance.visible(s))
        assertFalse(Relevance.exact(s))
        assertFalse(Relevance.visible(0.0))
        val top = gif("t", tags = listOf("knob")); val near = gif("n", title = "doorknob ride", site = "xvideos")
        val out = Relevance.order(listOf(near, top), ranks(near to s, top to score(top, "knob"))).map { it.id }
        assertEquals(listOf("t", "n"), out)
    }

    @Test fun `запрос не латиницей - по фразе целиком`() {
        assertTrue(score(gif("a", title = "горячие близнецы"), "близнецы") > 0)
    }

    // --- чередование источников внутри уровня ---

    private fun ranks(vararg r: Pair<Gif, Double>): (Gif) -> Relevance.Rank? { val m = r.associate { it.first.id to Relevance.Rank(it.second) }; return { m[it.id] } }

    @Test fun `внутри уровня источники чередуются а не все RedGIFs потом остальные`() {
        val a = (1..4).map { gif("rg$it") }
        val b = (1..2).map { gif("xv$it", site = "xvideos") }
        val c = (1..2).map { gif("ph$it", site = "pornhub") }
        val rk = ranks(*(a + b + c).map { it to 20.0 }.toTypedArray())
        val out = Relevance.order(a + b + c, rk).map { it.id }
        // по кругу: источники при равной оценке по алфавиту при равной оценке; остаток самого большого источника — в конце
        assertEquals(listOf("ph1", "rg1", "xv1", "ph2", "rg2", "xv2", "rg3", "rg4"), out)
    }

    @Test fun `высший уровень всегда раньше низшего даже у другого источника`() {
        val top = gif("x", site = "xvideos"); val low1 = gif("rg1"); val low2 = gif("rg2")
        val out = Relevance.order(listOf(low1, low2, top), ranks(top to 20.5, low1 to 12.2, low2 to 12.1)).map { it.id }
        assertEquals(listOf("x", "rg1", "rg2"), out)
    }

    @Test fun `один уровень 16-22 - фраза с окончанием и без чередуются вместе`() {
        val rg = gif("rg"); val xv = gif("xv", site = "xvideos")
        val out = Relevance.order(listOf(rg, xv), ranks(rg to 22.0, xv to 16.0)).map { it.id }
        assertEquals(listOf("rg", "xv"), out)
        val out2 = Relevance.order(listOf(rg, xv, gif("rg2")), ranks(rg to 22.0, xv to 16.0, gif("rg2") to 21.0)).map { it.id }
        assertEquals(listOf("rg", "xv", "rg2"), out2)
    }

    @Test fun `внутри источника при равной оценке популярнее выше`() {
        val a = gif("a", likes = 5); val b = gif("b", likes = 50)
        assertEquals(listOf("b", "a"), Relevance.order(listOf(a, b), ranks(a to 20.0, b to 20.0)).map { it.id })
    }

    @Test fun `порядок не теряет и не дублирует ролики`() {
        val items = (1..30).map { gif("g$it", site = listOf("", "xvideos", "pornhub", "erome")[it % 4]) }
        val out = Relevance.order(items) { g -> Relevance.Rank((g.id.drop(1).toInt() % 5 * 4).toDouble()) }
        assertEquals(items.map { it.id }.toSet(), out.map { it.id }.toSet())
        assertEquals(30, out.size)
    }
}
