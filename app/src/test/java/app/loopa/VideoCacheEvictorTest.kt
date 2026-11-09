package app.loopa

import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheSpan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class VideoCacheEvictorTest {
    private val removed = ArrayList<String>()
    private lateinit var ev: VideoCacheEvictor

    /** Кэш-заглушка: removeSpan сообщает вытеснителю, как настоящий SimpleCache. */
    private val cache: Cache = java.lang.reflect.Proxy.newProxyInstance(Cache::class.java.classLoader, arrayOf(Cache::class.java)) { p, m, a ->
        if (m.name == "removeSpan") { val s = a[0] as CacheSpan; removed += s.key; ev.onSpanRemoved(p as Cache, s) }
        null
    } as Cache

    private fun span(key: String, len: Long, at: Long) = CacheSpan(key, 0, len, at, File("$key.v"))

    @Test fun evictsLeastRecentlyUsedOverLimit() {
        ev = VideoCacheEvictor(100)
        ev.onSpanAdded(cache, span("a", 40, 1))
        ev.onSpanAdded(cache, span("b", 40, 2))
        ev.onSpanAdded(cache, span("c", 40, 3))
        assertEquals(listOf("a"), removed)
        assertEquals(80, ev.size)
    }

    @Test fun touchedSpanSurvives() {
        ev = VideoCacheEvictor(100)
        val a = span("a", 40, 1)
        ev.onSpanAdded(cache, a)
        ev.onSpanAdded(cache, span("b", 40, 2))
        ev.onSpanTouched(cache, a, span("a", 40, 5))     // «a» прочитали снова
        ev.onSpanAdded(cache, span("c", 40, 6))
        assertEquals(listOf("b"), removed)
    }

    @Test fun makesRoomBeforeNewFile() {
        ev = VideoCacheEvictor(100)
        ev.onSpanAdded(cache, span("a", 60, 1))
        ev.onStartFile(cache, "b", 0, 50)
        assertEquals(listOf("a"), removed)
        assertEquals(0, ev.size)
    }

    @Test fun lowerLimitTrimsAtOnce() {
        ev = VideoCacheEvictor(1000)
        for (i in 1..10) ev.onSpanAdded(cache, span("k$i", 100, i.toLong()))
        assertTrue(removed.isEmpty())
        ev.max = 350; ev.trim(cache)
        assertEquals(300, ev.size)
        assertEquals((1..7).map { "k$it" }, removed)
    }

    @Test fun sizeNeverExceedsLimitUnderLoad() {
        ev = VideoCacheEvictor(5_000)
        val rnd = java.util.Random(1)
        repeat(2_000) { ev.onSpanAdded(cache, span("x$it", 10L + rnd.nextInt(500), it.toLong())); assertTrue(ev.size <= 5_000) }
        assertFalse(removed.isEmpty())
    }
}
