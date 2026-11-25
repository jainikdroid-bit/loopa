package app.loopa

import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.UriUtil
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist
import java.util.concurrent.Executors
import java.util.concurrent.Future

/** A single cancellable cache writer for the currently playing VOD. */
@OptIn(UnstableApi::class)
internal class CacheBackfill {
    private val pool = Executors.newSingleThreadExecutor()
    private var target: String? = null
    private var active: Job? = null
    private var future: Future<*>? = null

    private class Job {
        @Volatile var cancelled = false
        @Volatile var writer: CacheWriter? = null
        @Volatile var retryAt = Long.MAX_VALUE
        /** Сколько байт уже запланировано этой задачей (потолок [MAX_BYTES] на ролик). */
        var planned = 0L
        @Volatile var checkedAt = 0L
    }

    /** Докачка нужна только на быстрой безлимитной сети: на мобильной, медленной и в экономии трафика — нет. */
    private fun netAllowed() = Video.net() == NetKind.FAST

    fun start(key: String, playlist: HlsMediaPlaylist?) {
        if (target == key && SystemClock.elapsedRealtime() < (active?.retryAt ?: 0L)) return
        stop()
        if (!netAllowed()) return
        target = key
        val job = Job()
        active = job
        future = pool.submit {
            try {
                // предзагрузка начал роликов важнее: ждём её конца (не дольше 30 с), потом перепроверяем сеть
                val end = SystemClock.elapsedRealtime() + IDLE_WAIT_MS
                while (Prefetch.busy && !job.cancelled && SystemClock.elapsedRealtime() < end) Thread.sleep(250)
                if (job.cancelled) return@submit
                if (Prefetch.busy || !netAllowed()) { job.retryAt = SystemClock.elapsedRealtime() + RETRY_MS; return@submit }
                if (playlist == null) {
                    val length = ContentMetadata.getContentLength(Video.cache.getContentMetadata(key))
                    if (length <= 0) {
                        job.retryAt = SystemClock.elapsedRealtime() + 5_000
                    } else if (length <= minOf(Video.cacheLimit, MAX_BYTES)) {
                        fill(job, key, 0, length)
                    }
                } else if (playlist.hasEndTag && playlist.protectionSchemes == null) {
                    val visited = HashSet<Triple<String, Long, Long>>()
                    for (segment in playlist.segments) {
                        if (job.cancelled) break
                        if (segment.hasGapTag || segment.drmInitData != null || segment.fullSegmentEncryptionKeyUri != null) continue
                        for (part in listOfNotNull(segment.initializationSegment, segment)) {
                            val url = UriUtil.resolve(playlist.baseUri, part.url)
                            if (visited.add(Triple(url, part.byteRangeOffset, part.byteRangeLength)))
                                fill(job, url, part.byteRangeOffset, part.byteRangeLength)
                        }
                    }
                }
            } catch (e: Exception) {
                if (!job.cancelled) job.retryAt = SystemClock.elapsedRealtime() + 30_000
            }
        }
    }

    private fun fill(job: Job, uri: String, offset: Long, length: Long) {
        if (job.cancelled || length == 0L) return
        val known = if (length == C.LENGTH_UNSET.toLong())
            ContentMetadata.getContentLength(Video.cache.getContentMetadata(uri)) - offset else length
        if (known > Video.cacheLimit || known > 0 && Video.cache.isCached(uri, offset, known)) return
        if (known > 0) {
            if (job.planned + known > MAX_BYTES) { job.cancelled = true; return }
            job.planned += known
        }
        val spec = DataSpec.Builder().setUri(uri).setPosition(offset).setLength(length).build()
        // во время работы раз в секунду смотрим сеть: стала лимитной/медленной/экономия — бросаем и пробуем позже
        val progress = CacheWriter.ProgressListener { _, _, _ ->
            val now = SystemClock.elapsedRealtime()
            if (now - job.checkedAt >= 1_000) {
                job.checkedAt = now
                if (!netAllowed() && !job.cancelled) {
                    job.retryAt = now + RETRY_MS
                    job.cancelled = true
                    job.writer?.cancel()
                }
            }
        }
        val writer = CacheWriter(Video.bgSource.createDataSourceForDownloading(), spec, null, progress)
        job.writer = writer
        if (!job.cancelled) writer.cache()
        job.writer = null
    }

    fun stop() {
        active?.let { it.cancelled = true; it.writer?.cancel() }
        future?.cancel(true)
        future = null
        active = null
        target = null
    }

    fun close() { stop(); pool.shutdownNow() }

    private companion object {
        /** Потолок докачки на один ролик. */
        const val MAX_BYTES = 80L * 1024 * 1024
        const val IDLE_WAIT_MS = 30_000L
        const val RETRY_MS = 30_000L
    }
}
