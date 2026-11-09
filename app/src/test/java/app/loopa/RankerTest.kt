package app.loopa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RankerTest {
    private fun gif(id: String, user: String = "", tags: List<String> = emptyList(), likes: Long = 0, views: Long = 0) =
        Gif(id, "", id, "", 0, 0, 10.0, user, tags, false, views, likes)

    @Test fun `ролик с кучей тегов не выигрывает только числом тегов`() {
        assertEquals(1.0, Ranker.norm(3), 1e-9)
        assertEquals(1.0, Ranker.norm(5), 1e-9)
        assertEquals(0.5, Ranker.norm(20), 1e-9)
        // одинаковый «сырой» вкус: у ролика с 20 тегами итог ниже
        assertTrue(Ranker.value(8.0, 20, 0, 0, 0.0) < Ranker.value(8.0, 5, 0, 0, 0.0))
    }

    @Test fun `доля лайков важнее голого числа лайков`() {
        // 300 лайков на 5 000 просмотров лучше, чем 600 лайков на 500 000
        assertTrue(Ranker.quality(300, 5_000) > Ranker.quality(600, 500_000))
    }

    @Test fun `три лайка на десять просмотров — ещё не шедевр`() {
        assertTrue(Ranker.quality(3, 10) < Ranker.quality(300, 5_000))
    }

    @Test fun `один автор не идёт подряд, когда есть другие`() {
        val l = listOf(gif("a1", "anna") to 5.0, gif("a2", "anna") to 4.9, gif("a3", "anna") to 4.8, gif("b1", "bella") to 4.5, gif("c1", "cora") to 4.4)
        val out = Ranker.diversify(l)
        assertEquals("a1", out[0].id)
        assertTrue("после anna — другой автор", out[1].user != "anna")
        assertEquals(5, out.size)
    }

    @Test fun `сильный ролик того же автора всё равно попадает в ленту`() {
        val l = listOf(gif("a1", "anna") to 9.0, gif("a2", "anna") to 8.9, gif("b1", "bella") to 1.0)
        assertEquals(listOf("a1", "a2", "b1"), Ranker.diversify(l).map { it.id })
    }

    @Test fun `соседние ролики не про одно и то же`() {
        val t = listOf("Riding", "Blonde", "POV")
        val l = listOf(gif("x1", "u1", t) to 5.0, gif("x2", "u2", t) to 4.9, gif("y1", "u3", listOf("Outdoor", "Feet")) to 4.6)
        assertEquals(listOf("x1", "y1", "x2"), Ranker.diversify(l).map { it.id })
    }

    @Test fun `явно не во вкусе убирается, но лента не пустеет`() {
        val l = listOf("a" to 1.0, "b" to 0.5, "c" to 0.1)
        assertEquals(listOf("a", "b"), Ranker.dropDisliked(l) { if (it == "c") -2.0 else 0.5 }.map { it.first })
        // почти всё «не во вкусе» — оставляем как есть
        assertEquals(3, Ranker.dropDisliked(l) { if (it == "a") 0.5 else -2.0 }.size)
    }

    @Test fun `добавка к поиску не перепрыгивает уровень релевантности`() {
        val max = Ranker.searchBonus(1e6, 5, 1_000_000, 1_000_000)
        val min = Ranker.searchBonus(-1e6, 5, 0, 0)
        assertTrue(max <= 1.5); assertTrue(min >= -1.0)
    }
}
