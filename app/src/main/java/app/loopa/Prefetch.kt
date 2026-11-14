package app.loopa

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheWriter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * Начало видео в дисковый кэш заранее — для ячеек сетки, где остановится бросок ([Forecaster]): живое превью и плеер
 * (на канале ~5 Мбит/с «Авто» — тот же SD-файл) стартуют с диска. Второй приоритет после картинок: два потока, только
 * первые [Forecaster.videoBytes] байт, а не файл. Новый прогноз отменяет загрузки, которые из него выпали. В плеере не
 * работает ([pause]) — там своя предзагрузка, и канал у ролика на экране не отнимаем.
 */
@OptIn(UnstableApi::class)
object Prefetch {
    private val pool = Executors.newFixedThreadPool(2)
    private val jobs = ConcurrentHashMap<String, Pair<Future<*>, CacheWriter?>>()
    @Volatile var paused = false

    /** Загрузить начало этих роликов (адрес → байт); остальное, что ещё грузится, отменить. */
    fun video(urls: List<String>, bytes: Long) {
        val want = if (paused || bytes <= 0) emptySet() else urls.filter { it.isNotEmpty() && !it.contains(".m3u8") && !Sources.uncached(it) }.toSet()
        jobs.keys.filter { it !in want }.forEach { cancel(it) }
        want.forEach { u ->
            if (jobs.containsKey(u)) return@forEach
            val box = arrayOfNulls<CacheWriter>(1)
            val f = pool.submit {
                try {
                    // картинки (на экране и про запас) — первыми: пока они грузятся, видео заранее ждёт (не дольше 8 с)
                    val end = System.currentTimeMillis() + 8_000
                    while (Thumbs.loading && !paused && System.currentTimeMillis() < end) Thread.sleep(50)
                    if (paused) return@submit
                    val spec = DataSpec.Builder().setUri(u).setPosition(0).setLength(bytes).build()
                    val w = CacheWriter(Video.bgSource.createDataSource(), spec, null, null)
                    box[0] = w
                    w.cache()
                } catch (_: Exception) {
                    // отменили или нет сети — не страшно, это только заранее
                } finally { jobs.remove(u) }
            }
            jobs[u] = f to box[0]
        }
    }

    private fun cancel(u: String) {
        val (f, w) = jobs.remove(u) ?: return
        w?.cancel(); f.cancel(true)
    }

    /** Остановить всё (открыли плеер — канал ему). */
    fun pause() { paused = true; jobs.keys.toList().forEach { cancel(it) } }
    fun resume() { paused = false }
}
