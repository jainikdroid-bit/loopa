package app.loopa

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.CacheSpan
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Единый «уборщик» места: дисковый кэш видео и сохранённые из него ролики ([CachedExport]) вместе не больше предела кэша
 * ([Video.cacheLimit]). Пока сумма больше — удаляется самое давнее по последнему просмотру среди ВСЕГО: кусков кэша
 * (время чтения у SimpleCache) и сохранённых файлов (время последней игры в индексе); ролик на экране не трогаем.
 * Выбор жертв — чистая [StorageBudget.victims]. Запросы ([request]) склеиваются и выполняются в фоне чуть позже: зовут
 * после переноса в файл, при росте кэша ([VideoCacheEvictor.onOver]) и при смене предела в меню.
 */
@OptIn(UnstableApi::class)
object CacheReaper {
    private const val DELAY_MS = 1_500L
    private val queued = AtomicBoolean(false)
    private val pool = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "cache-reaper").apply { priority = Thread.MIN_PRIORITY; isDaemon = true } }

    /** Проверить общий предел (в фоне, склеивается с соседними запросами). */
    fun request() {
        if (queued.compareAndSet(false, true)) pool.schedule({
            queued.set(false)
            try { run() } catch (e: Throwable) { android.util.Log.w("Loopa", "уборщик места: ${e.message}") }
        }, DELAY_MS, TimeUnit.MILLISECONDS)
    }

    private fun run() {
        if (!Video.ready) return
        val limit = Video.cacheLimit
        val saved = CachedExport.entries()
        if (Video.cacheUsed + saved.sumOf { it.size } <= limit) return
        val spans = HashMap<String, CacheSpan>()
        val items = ArrayList<StorageBudget.Item>()
        for (sp in Video.spans()) {
            val id = "c:${sp.key}:${sp.position}"
            spans[id] = sp
            items += StorageBudget.Item(id, StorageBudget.Kind.CACHE, sp.length, sp.lastTouchTimestamp)
        }
        saved.forEach { items += StorageBudget.Item("s:${it.id}", StorageBudget.Kind.SAVED, it.size, it.at) }
        val keep = setOfNotNull(CachedExport.playing?.let { "s:$it" })
        var files = 0; var chunks = 0
        for (v in StorageBudget.victims(items, limit, keep)) {
            if (v.kind == StorageBudget.Kind.SAVED) { if (CachedExport.evict(v.id.removePrefix("s:"))) files++ }
            else spans[v.id]?.let { Video.dropSpan(it); chunks++ }
        }
        android.util.Log.i("Loopa", "уборщик места: предел ${limit shr 20} МБ, убрано файлов $files, кусков кэша $chunks")
    }
}
