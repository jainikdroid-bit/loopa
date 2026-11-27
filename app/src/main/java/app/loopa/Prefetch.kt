package app.loopa

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheWriter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.ThreadPoolExecutor

/**
 * Начало видео в дисковый кэш заранее — для ячеек сетки, где остановится бросок ([Forecaster]): живое превью и плеер
 * (на канале ~5 Мбит/с «Авто» — тот же SD-файл) стартуют с диска. Второй приоритет после картинок: два потока, только
 * первые [Forecaster.videoBytes] байт, а не файл. Новый прогноз отменяет загрузки, которые из него выпали. В плеере не
 * работает ([pause]) — там своя предзагрузка, и канал у ролика на экране не отнимаем.
 */
@OptIn(UnstableApi::class)
object Prefetch {
    private val pool = Executors.newFixedThreadPool(2) as ThreadPoolExecutor
    private val jobs = ConcurrentHashMap<String, PrefetchJob>()
    @Volatile var paused = false
        private set
    /** Идёт ли сейчас предзагрузка начал роликов. */
    val busy get() = jobs.isNotEmpty()

    /** Загрузить начало этих роликов (адрес → байт); остальное, что ещё грузится, отменить. */
    @Synchronized fun video(urls: List<String>, bytes: Long) {
        val want = if (paused || bytes <= 0) emptySet() else urls.filter { it.isNotEmpty() && !it.contains(".m3u8") && !Sources.uncached(it) && !Video.fullyCached(it) }.toSet()
        val stale = jobs.keys.filter { it !in want }
        stale.forEach { jobs.remove(it)?.cancel() }
        if (stale.isNotEmpty()) pool.purge()
        want.forEach { u ->
            val job = PrefetchJob()
            if (jobs.putIfAbsent(u, job) != null) return@forEach
            val task = FutureTask<Unit> {
                try {
                    // картинки (на экране и про запас) — первыми: пока они грузятся, видео заранее ждёт (не дольше 8 с)
                    val end = android.os.SystemClock.elapsedRealtime() + 8_000
                    while (Thumbs.loading && !paused && !job.cancelled && android.os.SystemClock.elapsedRealtime() < end) Thread.sleep(50)
                    if (paused || job.cancelled) return@FutureTask
                    val spec = DataSpec.Builder().setUri(u).setPosition(0).setLength(bytes).build()
                    val w = CacheWriter(Video.bgSource.createDataSource(), spec, null, null)
                    if (!job.attachWriter { w.cancel() }) return@FutureTask
                    if (!paused && !job.cancelled) w.cache()
                } catch (_: Exception) {
                    // отменили или нет сети — не страшно, это только заранее
                } finally { jobs.remove(u, job) }
            }
            job.attach(task)
            pool.execute(task)
        }
    }

    /** Остановить всё (открыли плеер — канал ему). */
    @Synchronized fun pause() {
        paused = true
        jobs.values.forEach { it.cancel() }
        jobs.clear()
        pool.purge()
    }

    @Synchronized fun resume() { paused = false }
}
