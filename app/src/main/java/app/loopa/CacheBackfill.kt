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
    }

    fun start(key: String, playlist: HlsMediaPlaylist?) {
        if (target == key && SystemClock.elapsedRealtime() < (active?.retryAt ?: 0L)) return
        stop()
        if (Video.net() in listOf(NetKind.SAVER, NetKind.OFFLINE)) return
        target = key
        val job = Job()
        active = job
        future = pool.submit {
            try {
                if (playlist == null) {
                    val length = ContentMetadata.getContentLength(Video.cache.getContentMetadata(key))
                    if (length <= 0) {
                        job.retryAt = SystemClock.elapsedRealtime() + 5_000
                    } else if (length <= Video.cacheLimit) {
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
        val spec = DataSpec.Builder().setUri(uri).setPosition(offset).setLength(length).build()
        val writer = CacheWriter(Video.bgSource.createDataSourceForDownloading(), spec, null, null)
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
}
