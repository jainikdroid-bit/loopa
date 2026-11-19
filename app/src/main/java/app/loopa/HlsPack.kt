package app.loopa

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.net.URI
import java.nio.ByteBuffer

/**
 * Разбор HLS-плейлистов (чистая часть, без Android — [HlsPackTest]) и склейка скачанных сегментов в один файл без
 * перекодирования: сегменты подряд (у fMP4 — сначала init) → MediaExtractor → MediaMuxer в mp4.
 */
object HlsPack {
    /** Вариант качества из главного плейлиста: адрес его плейлиста, адрес плейлиста отдельного звука (null — звук внутри или нет) и битрейт. */
    class Variant(val url: String, val audio: String?, val bandwidth: Long)
    /** Медиа-плейлист: init-сегмент fMP4 (если есть), сегменты по порядку, шифрование (не поддерживается). */
    class Media(val init: String?, val segments: List<String>, val encrypted: Boolean)

    fun isMaster(text: String) = text.contains("#EXT-X-STREAM-INF")

    /** Адрес [ref] относительно [base]; не разобрать — как есть. */
    fun resolve(base: String, ref: String): String = runCatching { URI(base).resolve(ref.trim()).toString() }.getOrDefault(ref.trim())

    private fun attr(line: String, name: String): String? =
        Regex("(?:^|[:,])$name=(\"([^\"]*)\"|[^,]*)").find(line)?.let { it.groupValues[2].ifEmpty { it.groupValues[1].trim('"') } }

    /** Варианты главного плейлиста, от лучшего битрейта. */
    fun variants(text: String, base: String): List<Variant> {
        val lines = text.lines().map { it.trim() }
        val audio = HashMap<String, String>()
        lines.filter { it.startsWith("#EXT-X-MEDIA:") && attr(it, "TYPE") == "AUDIO" }.forEach { l ->
            val g = attr(l, "GROUP-ID"); val u = attr(l, "URI")
            if (g != null && u != null && g !in audio) audio[g] = resolve(base, u)
        }
        val out = ArrayList<Variant>()
        lines.forEachIndexed { i, l ->
            if (!l.startsWith("#EXT-X-STREAM-INF:")) return@forEachIndexed
            val u = lines.drop(i + 1).firstOrNull { it.isNotEmpty() && !it.startsWith("#") } ?: return@forEachIndexed
            out += Variant(resolve(base, u), attr(l, "AUDIO")?.let { audio[it] }, attr(l, "BANDWIDTH")?.toLongOrNull() ?: 0L)
        }
        return out.sortedByDescending { it.bandwidth }
    }

    /** Медиа-плейлист: сегменты по порядку. */
    fun media(text: String, base: String): Media {
        val lines = text.lines().map { it.trim() }
        var init: String? = null; var enc = false
        val segs = ArrayList<String>()
        var expect = false
        for (l in lines) {
            when {
                l.startsWith("#EXT-X-MAP:") -> attr(l, "URI")?.let { init = resolve(base, it) }
                l.startsWith("#EXT-X-KEY:") -> if (attr(l, "METHOD").let { it != null && it != "NONE" }) enc = true
                l.startsWith("#EXTINF") -> expect = true
                l.isNotEmpty() && !l.startsWith("#") -> if (expect) { segs += resolve(base, l); expect = false }
            }
        }
        return Media(init, segs, enc)
    }

    /**
     * Дорожки видео и звука из [video] и (если в нём звука нет) первая звуковая из [audio] → mp4 без перекодирования.
     * Исключение — не склеилось (формат, нет дорожек).
     */
    fun mux(video: File, audio: File?, out: File) {
        val ex = ArrayList<MediaExtractor>()
        val mx = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var started = false
        try {
            val v = MediaExtractor().apply { setDataSource(video.path) }.also { ex += it }
            val maps = ArrayList<Pair<MediaExtractor, Map<Int, Int>>>()
            fun mime(e: MediaExtractor, i: Int) = e.getTrackFormat(i).getString(MediaFormat.KEY_MIME).orEmpty()
            val vm = HashMap<Int, Int>()
            for (i in 0 until v.trackCount) if (mime(v, i).let { it.startsWith("video/") || it.startsWith("audio/") }) { v.selectTrack(i); vm[i] = mx.addTrack(v.getTrackFormat(i)) }
            if (vm.isEmpty()) throw IllegalStateException("в сегментах нет дорожек")
            maps += v to vm
            val hasAudio = (0 until v.trackCount).any { mime(v, it).startsWith("audio/") }
            if (audio != null && !hasAudio) {
                val a = MediaExtractor().apply { setDataSource(audio.path) }.also { ex += it }
                val i = (0 until a.trackCount).firstOrNull { mime(a, it).startsWith("audio/") }
                if (i != null) { a.selectTrack(i); maps += a to mapOf(i to mx.addTrack(a.getTrackFormat(i))) }
            }
            mx.start(); started = true
            val buf = ByteBuffer.allocate(4 shl 20); val info = MediaCodec.BufferInfo()
            // семплы дорожек вперемешку по времени (раньше — всё видео, потом весь звук: плеер на таком файле тянет диск туда-сюда)
            while (true) {
                var best = -1; var bestAt = Long.MAX_VALUE
                maps.forEachIndexed { k, (e, _) ->
                    if (e.sampleTrackIndex >= 0) maxOf(0L, e.sampleTime).let { if (it < bestAt) { bestAt = it; best = k } }
                }
                if (best < 0) break
                val (e, m) = maps[best]
                info.size = e.readSampleData(buf, 0)
                if (info.size < 0) break
                info.offset = 0; info.presentationTimeUs = e.sampleTime
                info.flags = if (e.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                m[e.sampleTrackIndex]?.let { mx.writeSampleData(it, buf, info) }
                e.advance()
            }
        } finally {
            if (started) runCatching { mx.stop() }
            runCatching { mx.release() }
            ex.forEach { runCatching { it.release() } }
        }
    }
}
