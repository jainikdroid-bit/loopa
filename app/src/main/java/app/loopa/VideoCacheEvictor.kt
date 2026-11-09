package app.loopa

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheEvictor
import androidx.media3.datasource.cache.CacheSpan

/**
 * LRU для дискового кэша видео — как LeastRecentlyUsedCacheEvictor, но предел [max] можно менять на ходу (меню «Кэш
 * видео»): давно не читанное удаляется первым, пока занятое + новый файл не влезут в предел. SimpleCache вызывает его
 * под своим замком; [trim] снаружи — тоже под замком кэша.
 */
@OptIn(UnstableApi::class)
class VideoCacheEvictor(@Volatile var max: Long) : CacheEvictor {
    private val spans = java.util.TreeSet<CacheSpan>(compareBy<CacheSpan> { it.lastTouchTimestamp }.thenBy { it })
    /** Сколько сейчас занято, байт. */
    var size = 0L; private set

    override fun requiresCacheSpanTouches() = true
    override fun onCacheInitialized() {}
    override fun onStartFile(cache: Cache, key: String, position: Long, length: Long) {
        if (length != C.LENGTH_UNSET.toLong()) evict(cache, length)
    }
    override fun onSpanAdded(cache: Cache, span: CacheSpan) { spans += span; size += span.length; evict(cache, 0) }
    override fun onSpanRemoved(cache: Cache, span: CacheSpan) { if (spans.remove(span)) size -= span.length }
    override fun onSpanTouched(cache: Cache, old: CacheSpan, new: CacheSpan) { onSpanRemoved(cache, old); onSpanAdded(cache, new) }

    /** Ужать до предела (после смены предела в меню). */
    fun trim(cache: Cache) = evict(cache, 0)

    private fun evict(cache: Cache, need: Long) {
        while (size + need > max && spans.isNotEmpty()) cache.removeSpan(spans.first())
    }
}
