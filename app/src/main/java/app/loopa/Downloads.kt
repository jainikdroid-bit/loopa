package app.loopa

import android.app.DownloadManager
import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.widget.Toast
import java.io.File
import java.nio.ByteBuffer
import kotlin.concurrent.thread

/**
 * Скачивание в Загрузки/Loopa/<автор>_<id>.mp4. Готовый mp4 (RedGIFs, Imgur, гифки Reddit) — системным DownloadManager,
 * с уведомлением о прогрессе; разрешений не нужно (Android 10+). Видео v.redd.it лежит раздельно (DASH: видео и звук) —
 * качаем обе дорожки сами и склеиваем MediaMuxer без перекодирования. Уже скачанное повторно не качаем.
 */
object Downloads {
    private val ui = Handler(Looper.getMainLooper())

    fun get(ctx: Context, g: Gif) {
        val name = "${g.user.ifBlank { "loopa" }}_${g.id}.mp4".replace(Regex("[^A-Za-z0-9._-]"), "_")
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (File(dir, "Loopa/$name").exists()) { toast(ctx, tr("Уже скачано: Загрузки/Loopa/%s", name)); return }
        if (g.hls) { reddit(ctx.applicationContext, g, name); return }
        val rq = DownloadManager.Request(Uri.parse(g.hd))
            .setTitle(name)
            .setDescription("Loopa")
            .addRequestHeader("User-Agent", Api.UA)
            .apply { if (Erome.ours(g.hd)) addRequestHeader("Referer", Erome.REFERER) }
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "Loopa/$name")
        runCatching { (ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(rq) }
            .onSuccess { toast(ctx, tr("Скачиваю в Загрузки/Loopa")) }
            .onFailure { toast(ctx, tr("Не скачать: %s", it.message)) }
    }

    private fun toast(ctx: Context, t: String) = ui.post { Toast.makeText(ctx, t, if (t.length > 40) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show() }

    /** v.redd.it: лучшая видеодорожка и звук из DASH-манифеста → один mp4. */
    private fun reddit(ctx: Context, g: Gif, name: String) {
        toast(ctx, tr("Скачиваю в Загрузки/Loopa (видео и звук)…"))
        thread {
            val tmp = ctx.cacheDir.resolve("dl").apply { mkdirs() }
            val v = File(tmp, "$name.v"); val a = File(tmp, "$name.a"); val out = File(tmp, name)
            runCatching {
                val (vu, au) = tracks(g)
                fetch(vu, v)
                val audio = au != null && g.hasAudio && runCatching { fetch(au, a) }.isSuccess
                mux(v, if (audio) a else null, out)
                save(ctx, out, name)
            }.onSuccess { toast(ctx, tr("Сохранено: Загрузки/Loopa/%s", name)) }
                .onFailure { toast(ctx, tr("Не скачать: %s", it.message ?: it.javaClass.simpleName)) }
            v.delete(); a.delete(); out.delete()
        }
    }

    /** Адреса дорожек из манифеста: видео с наибольшей высотой, звук с наибольшим битрейтом. */
    private fun tracks(g: Gif): Pair<String, String?> {
        val mpd = g.dash.ifEmpty { g.hd.replace("HLSPlaylist.m3u8", "DASHPlaylist.mpd") }.substringBefore("?")
        val base = mpd.substringBeforeLast('/') + "/"
        val x = Net.retry { val c = Net.open(mpd); try { if (c.responseCode !in 200..299) throw Net.fail(c); c.inputStream.bufferedReader().readText() } finally { c.disconnect() } }
        var best: Pair<Int, String>? = null; var audio: Pair<Int, String>? = null
        Regex("<AdaptationSet[^>]*>(.*?)</AdaptationSet>", RegexOption.DOT_MATCHES_ALL).findAll(x).forEach { set ->
            val isAudio = set.value.contains("audio", true) && !set.value.contains("contentType=\"video\"")
            Regex("<Representation([^>]*)>.*?<BaseURL>([^<]+)</BaseURL>", RegexOption.DOT_MATCHES_ALL).findAll(set.groupValues[1]).forEach { r ->
                val attrs = r.groupValues[1]; val url = r.groupValues[2].trim()
                fun num(n: String) = Regex("$n=\"(\\d+)\"").find(attrs)?.groupValues?.get(1)?.toInt() ?: 0
                if (isAudio) { if (audio == null || num("bandwidth") > audio!!.first) audio = num("bandwidth") to url }
                else if (best == null || num("height") > best!!.first) best = num("height") to url
            }
        }
        fun abs(u: String) = if (u.startsWith("http")) u else base + u
        val v = best?.second ?: throw IllegalStateException(tr("в манифесте нет видео"))
        return abs(v) to audio?.second?.let(::abs)
    }

    private fun fetch(url: String, to: File) = Net.retry {
        val c = Net.open(url)
        try {
            if (c.responseCode !in 200..299) throw Net.fail(c)
            c.inputStream.use { i -> to.outputStream().use { i.copyTo(it, 64 shl 10) } }
        } finally { c.disconnect() }
    }

    /** Склеить дорожки в mp4 без перекодирования. */
    private fun mux(video: File, audio: File?, out: File) {
        val mx = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val ex = listOfNotNull(video, audio).map { f -> MediaExtractor().apply { setDataSource(f.path) } }
        try {
            val tracks = ex.map { e ->
                val i = (0 until e.trackCount).first { e.getTrackFormat(it).getString(android.media.MediaFormat.KEY_MIME).orEmpty().let { m -> m.startsWith("video/") || m.startsWith("audio/") } }
                e.selectTrack(i); mx.addTrack(e.getTrackFormat(i))
            }
            mx.start()
            val buf = ByteBuffer.allocate(4 shl 20); val info = MediaCodec.BufferInfo()
            ex.forEachIndexed { k, e ->
                while (true) {
                    info.size = e.readSampleData(buf, 0)
                    if (info.size < 0) break
                    info.offset = 0; info.presentationTimeUs = e.sampleTime
                    info.flags = if (e.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                    mx.writeSampleData(tracks[k], buf, info)
                    e.advance()
                }
            }
            mx.stop()
        } finally { mx.release(); ex.forEach { it.release() } }
    }

    /** В Загрузки/Loopa: Android 10+ — через MediaStore, раньше — прямо в папку. */
    private fun save(ctx: Context, f: File, name: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val cv = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name); put(MediaStore.Downloads.MIME_TYPE, "video/mp4")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Loopa")
            }
            val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv) ?: throw IllegalStateException(tr("нет доступа к Загрузкам"))
            ctx.contentResolver.openOutputStream(uri)!!.use { o -> f.inputStream().use { it.copyTo(o) } }
        } else {
            val d = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Loopa").apply { mkdirs() }
            f.copyTo(File(d, name), overwrite = true)
        }
    }
}
