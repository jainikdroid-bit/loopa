package app.loopa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TasteModelTest {
    private fun f(vararg n: String) = n.map { it to (1.0 / Math.sqrt(n.size.toDouble())).toFloat() }
    private fun gif(tags: List<String>, user: String = "", dur: Double = 20.0, audio: Boolean = false, desc: String = "") =
        Gif("id", "", "", "", 0, 0, dur, user, tags, audio, 0, 0, description = desc)

    @Test fun oppositeChoicesRankUnseenVideosDifferently() {
        val dance = f("site:one", "tag:dance")
        val music = f("site:one", "tag:music")
        for ((chosen, skipped) in listOf(dance to music, music to dance)) {
            val model = FmModel(bits = 10)
            model.learn(f("site:one"), 1f, 1f)
            val shared = model.logit(f("site:one"))
            repeat(5) { model.prefer(chosen, skipped) }
            assertEquals(shared, model.logit(f("site:one")), 0.0)
            val a = chosen + ("user:new" to 1f)
            val b = skipped + ("user:new" to 1f)
            val first = model.bonus(a)
            assertTrue(first > model.bonus(b))
            assertEquals(first, model.bonus(a), 0.0)
            val restored = FmModel(bits = 10)
            assertTrue(restored.load(model.toBytes()))
            assertEquals(first, restored.bonus(a), 0.0)
        }
    }

    @Test fun `пустая модель даёт 0,5`() {
        val m = FmModel()
        assertEquals(0.5, m.predict(f("tag:a", "tag:b")), 1e-3)   // v ~ N(0; 0,01): пары почти не вносят
        assertEquals(0.5, m.predict(emptyList()), 1e-9)
        assertEquals(0L, m.examples)
    }

    @Test fun `учит сочетание a вместе с b - линейной модели это недоступно`() {
        val m = FmModel()
        repeat(400) {
            m.learn(f("tag:a", "tag:b"), 1f, 1f)
            m.learn(f("tag:a", "tag:c"), 0f, 1f)
            m.learn(f("tag:b", "tag:d"), 0f, 1f)
            m.learn(f("tag:a"), 0f, 1f)
            m.learn(f("tag:b"), 0f, 1f)
        }
        val ab = m.predict(f("tag:a", "tag:b"))
        val a = m.predict(f("tag:a", "tag:c")); val b = m.predict(f("tag:b", "tag:d"))
        assertTrue("ab=$ab", ab > 0.8)
        assertTrue("a=$a", a < 0.3); assertTrue("b=$b", b < 0.3)
        // новый ролик с тем же сочетанием, но другим третьим тегом — тоже выше одиночных
        assertTrue(m.predict(f("tag:a", "tag:b", "tag:z")) > m.predict(f("tag:a", "tag:z")))
    }

    @Test fun `сохранение и загрузка дают те же предсказания`() {
        val m = FmModel()
        repeat(50) { m.learn(f("tag:a", "tag:b", "user:u"), 1f, 2f); m.learn(f("tag:c", "dur:<30"), 0f, 1f) }
        val bytes = m.toBytes()
        val r = FmModel()
        assertTrue(r.load(bytes))
        for (x in listOf(f("tag:a", "tag:b"), f("tag:c"), f("user:u", "tag:a"), f("tag:never"))) assertEquals(m.logit(x), r.logit(x), 0.0)
        assertEquals(m.examples, r.examples)
        assertEquals(m.topPairs(5), r.topPairs(5))
        assertFalse(FmModel().load(byteArrayOf(1, 2, 3)))
        assertFalse(FmModel(bits = 12, k = 4).load(bytes))
    }

    @Test fun `вес примера усиливает обучение`() {
        val light = FmModel(); val heavy = FmModel()
        repeat(3) { light.learn(f("tag:x"), 1f, 0.1f); heavy.learn(f("tag:x"), 1f, 3f) }
        assertTrue(heavy.predict(f("tag:x")) > light.predict(f("tag:x")))
        val none = FmModel(); none.learn(f("tag:x"), 1f, 0f)
        assertEquals(0L, none.examples)
    }

    @Test fun `начальные факторы воспроизводимы, хеш стабилен, сброс возвращает нуль`() {
        val a = FmModel(); val b = FmModel()
        assertEquals(a.bucket("tag:abc"), b.bucket("tag:abc"))
        assertTrue(a.bucket("tag:abc") in 0 until (1 shl 15))
        assertTrue(a.toBytes().contentEquals(b.toBytes()))
        a.learn(f("tag:a", "tag:b"), 1f, 1f)
        a.reset()
        assertTrue(a.toBytes().contentEquals(b.toBytes()))
    }

    @Test fun `сильные сочетания показывают выученную пару`() {
        val m = FmModel()
        repeat(300) { m.learn(f("tag:a", "tag:b"), 1f, 1f); m.learn(f("tag:a", "tag:c"), 0f, 1f); m.learn(f("tag:b", "tag:d"), 0f, 1f) }
        val top = m.topPairs(3)
        assertTrue(top.isNotEmpty())
        assertEquals(setOf("tag:a", "tag:b"), setOf(top[0].first, top[0].second))
    }

    @Test fun `признаки ролика нормированы по группам`() {
        val g = gif(List(16) { "t$it" } + "r/sub", user = "Bob", dur = 45.0, audio = true, desc = "alpha beta gamma delta epsilon zeta eta theta")
        val ft = TasteModel.features(g).toMap()
        assertEquals(0.25f, ft["tag:t3"]!!, 1e-6f)
        assertFalse(ft.keys.any { it.startsWith("tag:r/") })
        assertEquals(1f, ft["user:bob"]!!, 0f)
        assertEquals(1f, ft["dur:<120"]!!, 0f)
        assertEquals(1f, ft["audio"]!!, 0f)
        assertEquals(6, ft.keys.count { it.startsWith("kw:") })
        assertEquals("dur:600+", TasteModel.bucketOfDuration(900.0))
    }

    @Test fun `доля модели растёт с числом примеров`() {
        assertEquals(0.0, TasteModel.alpha(), 0.0)   // свежая JVM теста: модель пуста
    }

    @Test fun `пустая модель - добавка 0`() {
        val m = FmModel()
        assertEquals(0.0, m.bonus(f("tag:a", "tag:b")), 1e-9)
        assertEquals(0.0, m.bonus(emptyList()), 0.0)
        assertEquals(0.0, m.bonus(f("tag:zzz")), 1e-2)
    }

    /** 40 роликов по 3 тега из 25 (детерминированно). */
    private fun catalog() = List(40) { i -> f("tag:t${i % 25}", "tag:t${(i * 7 + 3) % 25}", "tag:t${(i * 11 + 5) % 25}") }

    @Test fun `модель на одних плюсах не даёт систематически отрицательной добавки`() {
        val m = FmModel()
        val items = catalog()
        repeat(6) { items.forEach { m.learn(it, 1f, 1f) } }
        m.bonus(items[0])   // прогрев скользящего среднего
        val bonuses = (items + items).map { m.bonus(it) }
        val mean = bonuses.average()
        assertTrue("mean=$mean", mean > -0.3)
        assertTrue("есть и плюс, и не-минус", bonuses.any { it > 0 })
    }

    @Test fun `добавка ограничена сверху масштабом и снизу пределом`() {
        val m = FmModel()
        repeat(300) { m.learn(f("tag:good", "tag:fine"), 1f, 3f); m.learn(f("tag:bad", "tag:awful"), 0f, 3f) }
        repeat(50) { m.bonus(f("tag:other", "tag:thing")) }   // среднее — около «обычного» ролика
        val good = m.bonus(f("tag:good", "tag:fine")); val bad = m.bonus(f("tag:bad", "tag:awful"))
        assertTrue("good=$good", good > 1.0 && good <= FmModel.BONUS_SCALE)
        assertTrue("bad=$bad", bad < 0.0 && bad >= -FmModel.BONUS_NEG_MAX)
    }

    @Test fun `пример из одного признака не растит счётчик роликов и не двигает v`() {
        val m = FmModel()
        m.learn(f("tag:a"), 1f, 1f, video = false)
        assertEquals(1L, m.examples); assertEquals(0L, m.videoExamples)
        val before = m.toBytes()
        m.learn(f("tag:a"), 1f, 1f, video = false)
        // v и gv не менялись: различаются только w, gw, w0, gw0 и счётчик
        val vFrom = 40 + 4 * 2 * (1 shl 15)
        assertTrue(before.copyOfRange(vFrom, before.size).contentEquals(m.toBytes().copyOfRange(vFrom, before.size)))
        m.learn(f("tag:a", "tag:b"), 1f, 1f)
        assertEquals(1L, m.videoExamples)
    }

    @Test fun `v1 читается, число роликов берётся из общего счётчика`() {
        val m = FmModel(); repeat(5) { m.learn(f("tag:a", "tag:b"), 1f, 1f) }
        val v2 = m.toBytes()
        // v1: версия 1 и без поля videoExamples (8 байт после examples)
        val v1 = v2.copyOfRange(0, 24) + v2.copyOfRange(32, v2.size)
        java.nio.ByteBuffer.wrap(v1).putInt(4, 1)
        val r = FmModel(); assertTrue(r.load(v1))
        assertEquals(5L, r.examples); assertEquals(5L, r.videoExamples)
        assertEquals(m.logit(f("tag:a", "tag:b")), r.logit(f("tag:a", "tag:b")), 0.0)
    }

    @Test fun `файл с NaN отвергается, модель не тронута`() {
        val m = FmModel(); repeat(5) { m.learn(f("tag:a", "tag:b"), 1f, 1f) }
        val bytes = m.toBytes()
        val bad = bytes.copyOf(); java.nio.ByteBuffer.wrap(bad).putFloat(40 + 4 * 100, Float.NaN)
        val r = FmModel()
        val before = r.logit(f("tag:a"))
        assertFalse(r.load(bad))
        assertEquals(0L, r.examples); assertEquals(before, r.logit(f("tag:a")), 0.0)
        assertTrue(r.load(bytes))
    }
}
