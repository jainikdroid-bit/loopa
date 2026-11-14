package app.loopa

import android.content.Context
import android.graphics.Matrix
import android.os.Handler
import android.os.Looper
import android.view.TextureView
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory

/**
 * Живые превью в сетке: видимые целиком ячейки играют свой ролик без звука поверх картинки (картинка видна, пока не
 * пришёл первый кадр). Играют все ячейки на экране (на медленной сети — 3); коснулись ячейки — её превью первым. Нарезкой, как превью на больших видеосайтах: кусочки из начала, около трети, около двух третей и
 * концовка, по 1,5–2,5 с, по кругу — сюжет понятен сразу. Ролики короче [CUTS_MIN] с — целиком. Одновременно — до 3 ячеек, на медленной сети ([Video.slow]) — одна, выключено — ни одной.
 * Ролики идут через общий кэш [Video] — открыл в плеере, и он стартует с уже скачанного. Качество — SD (у потока Reddit
 * HLS сам берёт по сети).
 */
@OptIn(UnstableApi::class)
class GridPreview(private val ctx: Context) {
    companion object { const val CUTS_MIN = 8.0 }

    /** Ячейка, в которой может играть превью. */
    class Slot(val tex: TextureView) { var pos = -1; var player: ExoPlayer? = null
        /** Какой ролик сейчас привязан к ячейке (id) и какой в ней играет — не совпали, значит превью чужое. */
        var id: String? = null; internal var playing: String? = null }

    private val idle = ArrayList<ExoPlayer>()
    private val active = HashMap<Slot, ExoPlayer>()
    /** Нарезка для играющего: начала кусков (мс), длина куска, текущий кусок. */
    private class Cuts(val at: LongArray, val len: Long) { var i = 0 }
    private val cuts = HashMap<ExoPlayer, Cuts>()
    private val ui = Handler(Looper.getMainLooper())

    /** Сторож: превью, что застряло (грузится дольше [STUCK_BUFFER_MS] или кадр стоит [STUCK_FROZEN_MS]), — перезапустить. */
    private class Watch(var pos: Long = -1, var since: Long = System.currentTimeMillis())
    private val watch = HashMap<ExoPlayer, Watch>()
    /** Сколько раз подряд превью ячейки перезапускали после ошибки или застревания (по id ролика). */
    private val retries = HashMap<String, Int>()
    private val STUCK_BUFFER_MS = 8_000L
    private val STUCK_FROZEN_MS = 4_000L
    private val MAX_RETRIES = 3
    private val guard: Runnable = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            active.entries.toList().forEach { (s, p) ->
                val w = watch.getOrPut(p) { Watch() }
                val pos = p.currentPosition
                val moving = p.playbackState == Player.STATE_READY && p.isPlaying && pos != w.pos
                if (moving || p.playbackState == Player.STATE_IDLE && p.playerError == null) { w.pos = pos; w.since = now; return@forEach }
                val stuck = now - w.since > if (p.playbackState == Player.STATE_BUFFERING) STUCK_BUFFER_MS else STUCK_FROZEN_MS
                if (stuck) { android.util.Log.i("Loopa", "превью застряло (${p.playbackState}) — перезапуск"); restart(s) }
            }
            if (active.isNotEmpty()) ui.postDelayed(this, 1_000)
        }
    }
    /** Перезапустить превью ячейки тем же роликом (не больше [MAX_RETRIES] раз подряд — дальше остаётся картинка). */
    private fun restart(s: Slot) {
        val g = gifs[s] ?: return
        val n = (retries[g.id] ?: 0) + 1
        stop(s)
        if (n > MAX_RETRIES) return
        if (retries.size > 500) retries.clear()
        retries[g.id] = n
        ui.postDelayed({ if (s !in active && s.id == g.id && enabled) start(s, g) }, 1_500L * n)
    }
    private val gifs = HashMap<Slot, Gif>()
    private val tick: Runnable = object : Runnable {
        override fun run() {
            for ((p, c) in cuts) {
                val pos = p.currentPosition
                // кусок доигран (или плеер ушёл по кругу в начало) — следующий
                if (pos >= c.at[c.i] + c.len || pos < c.at[c.i] - 300) { c.i = (c.i + 1) % c.at.size; p.seekTo(c.at[c.i]) }
            }
            if (cuts.isNotEmpty()) ui.postDelayed(this, 150)
        }
    }

    /** Куски: начало (после заставки сайта), ~33 %, ~66 % и концовка; длина — 8 % ролика, но 1,5–2,5 с. */
    private fun plan(p: ExoPlayer, durSec: Double, introMs: Long) {
        if (durSec < CUTS_MIN || cuts.containsKey(p)) return
        val d = (durSec * 1000).toLong()
        val len = (d * 0.08).toLong().coerceIn(1500, 2500)
        // заставку сайта пропускаем: первый кусок — сразу за ней, остальные — в пределах [intro, конец]
        val end = (d - len - 200).coerceAtLeast(0)
        val lo = introMs.coerceAtMost(end)
        val span = d - lo
        if (lo > 0) p.seekTo(lo)
        cuts[p] = Cuts(longArrayOf(lo, lo + span * 33 / 100, lo + span * 66 / 100, end.coerceAtLeast(lo)), len)
        ui.removeCallbacks(tick); ui.post(tick)
    }
    var enabled = true

    /**
     * Сколько играют сразу: все на экране (до 8); на медленной сети — до [SLOW_TOTAL], из них из сети не больше
     * [SLOW_NET] (каждое такое превью — свой поток видео), остальные — только ролики, целиком лежащие в кэше.
     */
    fun limit() = when { !enabled -> 0; Video.slow -> SLOW_TOTAL; else -> 8 }
    private val SLOW_TOTAL = 6
    private val SLOW_NET = 2

    /** Кому играть: по порядку важности; на медленной сети сетевых — не больше [SLOW_NET]. */
    private fun pick(sorted: List<Pair<Slot, Gif>>): List<Pair<Slot, Gif>> {
        if (!Video.slow) return sorted.take(limit())
        var net = 0
        return sorted.filter { (_, g) -> Video.fullyCached(g.sd) || net++ < SLOW_NET }.take(limit())
    }

    /** Ячейка, которой коснулись, — первая в очереди: превью запускается сразу. */
    @Volatile var touched: Slot? = null

    private fun player(): ExoPlayer = idle.removeLastOrNull() ?: ExoPlayer.Builder(ctx)
        .setMediaSourceFactory(DefaultMediaSourceFactory(Video.bgPlay))
        // превью: старт с четверти секунды, впрок немного — канал нужнее тому, что откроют
        .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(1_000, 4_000, 250, 500).build())
        .build().apply { volume = 0f; repeatMode = Player.REPEAT_MODE_ONE }

    /** Какие ячейки играют: видимые целиком, первые [limit] по порядку. */
    fun update(visible: List<Pair<Slot, Gif>>) {
        val t = touched
        val want = pick(visible.sortedBy { if (it.first === t) 0 else 1 })
        val keep = want.map { it.first }.toSet()
        active.keys.filter { it !in keep }.forEach { stop(it) }
        // ячейку перепривязали к другому ролику — его превью, а не прежнее
        want.forEach { (s, g) -> if (s in active && s.playing != g.id) stop(s) }
        want.forEach { (s, g) -> if (s !in active) start(s, g) }
    }

    private fun start(s: Slot, g: Gif) {
        val p = player()
        active[s] = p; s.player = p; s.playing = g.id; gifs[s] = g
        watch[p] = Watch(); ui.removeCallbacks(guard); ui.postDelayed(guard, 1_000)
        s.tex.alpha = 0f
        p.clearVideoSurface()
        p.setVideoTextureView(s.tex)
        p.addListener(object : Player.Listener {
            override fun onVideoSizeChanged(v: VideoSize) { if (s.player === p) crop(s.tex, v.width, v.height) }
            // ошибка сети — не гасить до следующей прокрутки: перезапуск через пару секунд (до [MAX_RETRIES] раз)
            override fun onPlayerError(e: androidx.media3.common.PlaybackException) { if (s.player === p) restart(s) }
            override fun onRenderedFirstFrame() { if (s.player === p) s.tex.alpha = 1f }
            // длительность из API бывает пустой (Imgur) — тогда берём у плеера, когда он её узнал
            override fun onPlaybackStateChanged(st: Int) { if (st == Player.STATE_READY && s.player === p && p.duration > 0) plan(p, p.duration / 1000.0, Sources.intro(g)) }
        }.also { listeners[p] = it })
        val u = g.sd
        p.setMediaItem(MediaItem.Builder().setUri(u).setMediaId(u).apply { if (g.hls) setMimeType(MimeTypes.APPLICATION_M3U8) }.build())
        p.prepare(); p.play()
        val intro = Sources.intro(g)
        if (g.duration > 0) plan(p, g.duration, intro)
        else if (intro > 0) p.seekTo(intro)   // длительность неизвестна — хотя бы первый кусок уже за заставкой
    }
    private val listeners = HashMap<ExoPlayer, Player.Listener>()

    /** Ячейку переиспользовали или она ушла с экрана — превью остановить, плеер в запас. */
    fun stop(s: Slot) {
        val p = active.remove(s) ?: return
        s.player = null; s.playing = null; s.tex.alpha = 0f
        listeners.remove(p)?.let { p.removeListener(it) }
        cuts.remove(p); watch.remove(p); gifs.remove(s)
        p.stop(); p.clearMediaItems(); p.clearVideoTextureView(s.tex)
        idle += p
    }

    fun stopAll() = active.keys.toList().forEach { stop(it) }

    fun release() { stopAll(); idle.forEach { it.release() }; idle.clear() }

    /** Заполнить ячейку как CENTER_CROP: TextureView иначе растягивает кадр. */
    private fun crop(t: TextureView, vw: Int, vh: Int) {
        val w = t.width.toFloat(); val h = t.height.toFloat()
        if (vw <= 0 || vh <= 0 || w <= 0 || h <= 0) return
        val s = maxOf(w / vw, h / vh)
        t.setTransform(Matrix().apply { setScale(vw * s / w, vh * s / h, w / 2, h / 2) })
    }
}
