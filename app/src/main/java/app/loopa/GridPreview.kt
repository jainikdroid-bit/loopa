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
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
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
    companion object {
        const val CUTS_MIN = 8.0

        /**
         * Куски превью ролика длиной [d] мс по [len] мс: с 10 %, 50 % и 80 % длины (первый — не раньше конца заставки
         * сайта [introMs]); кусок не выходит за конец ролика. Плеер при открытии стартует, как прежде, после заставки.
         */
        fun previewCuts(d: Long, introMs: Long, len: Long): LongArray {
            val last = (d - len - 200).coerceAtLeast(0)
            fun at(pct: Int, min: Long = 0) = maxOf(min, d * pct / 100).coerceAtMost(last)
            return longArrayOf(at(10, introMs), at(50), at(80))
        }
        /** Программные декодеры — первыми (аппаратные — запасом): для превью сверх числа аппаратных декодеров. */
        private val SOFT_FIRST = MediaCodecSelector { mime, secure, tunneling ->
            MediaCodecUtil.getDecoderInfos(mime, secure, tunneling).sortedBy { if (it.hardwareAccelerated) 1 else 0 }
        }
    }

    /** Ячейка, в которой может играть превью. */
    class Slot(val tex: TextureView) { var pos = -1; var player: ExoPlayer? = null
        /** Какой ролик сейчас привязан к ячейке (id) и какой в ней играет — не совпали, значит превью чужое. */
        var id: String? = null; internal var playing: String? = null
        /** Тонкая полоса сверху плитки: сколько превью уже скачано; null — плитка без полосы. */
        var bar: android.view.View? = null }

    private val idle = ArrayList<ExoPlayer>()
    /** Запас плееров с программным декодером и какие из играющих — такие. */
    private val idleSw = ArrayList<ExoPlayer>()
    private val soft = HashSet<ExoPlayer>()
    private val active = HashMap<Slot, ExoPlayer>()
    /** Нарезка для играющего: начала кусков (мс), длина куска, текущий кусок. */
    private class Cuts(val at: LongArray, val len: Long, val hot: Boolean = false) { var i = 0; @Volatile var looped = false
        /** Все куски уже сыграны хотя бы раз — лежат в кэше. */
        var round = false }
    private val cuts = HashMap<ExoPlayer, Cuts>()
    private val ui = Handler(Looper.getMainLooper())

    /**
     * Сторож: превью, что застряло (грузится дольше [STUCK_BUFFER_MS], позиция стоит [STUCK_FROZEN_MS] или позиция идёт,
     * а новых кадров нет [STUCK_NO_FRAMES_MS] — декодеру не хватило ресурсов), — перезапустить.
     * frames — сколько кадров отдано на экран (считает VideoFrameMetadataListener на потоке плеера).
     */
    private class Watch(var pos: Long = -1, var since: Long = android.os.SystemClock.elapsedRealtime()) {
        val frames = java.util.concurrent.atomic.AtomicLong()
        var listener: androidx.media3.exoplayer.video.VideoFrameMetadataListener? = null
        var seenFrames = 0L; var framesSince = android.os.SystemClock.elapsedRealtime()
        /** С какого момента кадры идут без перерыва (0 — не идут): ≥ [FLOW_OK_MS] — превью сыграло, счётчик перезапусков сбрасывается. */
        var flowSince = 0L
    }
    private val watch = HashMap<ExoPlayer, Watch>()
    /** Сколько раз подряд превью ячейки перезапускали после ошибки или застревания (по id ролика). */
    private val retries = PreviewRetryState()
    private val STUCK_BUFFER_MS = 8_000L
    private val STUCK_FROZEN_MS = 4_000L
    private val STUCK_NO_FRAMES_MS = 3_000L
    private val MIN_PREVIEWS = 2
    private val FLOW_OK_MS = 5_000L
    /** Потолки снижаются после стольких срабатываний «нет кадров» за [HITS_WINDOW_MS] и растут на 1 после [RECOVER_MS] без срабатываний. */
    private val HITS_TO_LOWER = 2
    private val HITS_WINDOW_MS = 60_000L
    private val RECOVER_MS = 120_000L
    private val noFrameHits = ArrayDeque<Long>()
    private var lastCapEvent = android.os.SystemClock.elapsedRealtime()
    private val guard: Runnable = object : Runnable {
        override fun run() {
            val now = android.os.SystemClock.elapsedRealtime()
            active.entries.toList().forEach { (s, p) ->
                if (s !in rendered && loaded(p)) { rendered += s; onWarm?.let { ui.post(it) } }
                val w = watch.getOrPut(p) { Watch() }
                val pos = p.currentPosition
                // играет, позиция идёт, а кадров нет: у плеера отобрали (или не дали) аппаратный декодер — картинка стоит
                val fr = w.frames.get()
                if (fr != w.seenFrames || p.playbackState != Player.STATE_READY || !p.isPlaying) {
                    // кадры идут ≥ FLOW_OK_MS — превью сыграло: прежние перезапуски этого ролика больше не в счёт
                    if (fr != w.seenFrames && p.playbackState == Player.STATE_READY && p.isPlaying) {
                        if (w.flowSince == 0L) w.flowSince = now
                        else if (now - w.flowSince >= FLOW_OK_MS) gifs[s]?.let { retries.healthy(it.id) }
                    } else w.flowSince = 0L
                    w.seenFrames = fr; w.framesSince = now
                }
                else if (now - w.framesSince > STUCK_NO_FRAMES_MS) {
                    android.util.Log.i("Loopa", "превью без кадров ${now - w.framesSince} мс (играет, декодер молчит) — перезапуск, потолок ${active.size - 1}")
                    // аппаратных декодеров на всех не хватает — следующие превью пойдут программным декодером;
                    // программный не успевает — значит, процессор не тянет столько превью разом
                    w.flowSince = 0L
                    lowerCaps(now, p in soft)
                    restart(s); return@forEach
                }
                val moving = p.playbackState == Player.STATE_READY && p.isPlaying && pos != w.pos
                if (moving || p.playbackState == Player.STATE_IDLE && p.playerError == null) { w.pos = pos; w.since = now; return@forEach }
                val stuck = now - w.since > if (p.playbackState == Player.STATE_BUFFERING) STUCK_BUFFER_MS else STUCK_FROZEN_MS
                if (stuck) { android.util.Log.i("Loopa", "превью застряло (${p.playbackState}) — перезапуск"); restart(s) }
            }
            recoverCaps(now)
            if (active.isNotEmpty()) ui.postDelayed(this, 1_000)
        }
    }

    /** Срабатывание «нет кадров»: потолок снижаем, только если за [HITS_WINDOW_MS] их было [HITS_TO_LOWER] (одно — случайность). */
    private fun lowerCaps(now: Long, softPlayer: Boolean) {
        lastCapEvent = now
        noFrameHits.addLast(now)
        while (noFrameHits.isNotEmpty() && now - noFrameHits.first() > HITS_WINDOW_MS) noFrameHits.removeFirst()
        if (noFrameHits.size < HITS_TO_LOWER) return
        noFrameHits.clear()
        if (softPlayer) decoderCap = minOf(decoderCap, (active.size - 1).coerceAtLeast(MIN_PREVIEWS))
        else hwCap = minOf(hwCap, (active.values.count { it !in soft } - 1).coerceAtLeast(1))
    }

    /** Без срабатываний [RECOVER_MS] — вернуть по одному превью к потолкам (до исходных, «без предела»). */
    private fun recoverCaps(now: Long) {
        if (now - lastCapEvent < RECOVER_MS || (decoderCap == Int.MAX_VALUE && hwCap == Int.MAX_VALUE)) return
        lastCapEvent = now
        if (decoderCap != Int.MAX_VALUE) decoderCap = if (decoderCap + 1 >= total) Int.MAX_VALUE else decoderCap + 1
        if (hwCap != Int.MAX_VALUE) hwCap = if (hwCap + 1 >= hwLimit) Int.MAX_VALUE else hwCap + 1
    }
    /** Перезапустить превью ячейки тем же роликом (не больше PreviewRetryState раз подряд — дальше остаётся картинка). */
    private fun restart(s: Slot) {
        val g = gifs[s] ?: return
        discard(s)
        val n = retries.failure(g.id) ?: return
        if (desired[s] != g.id) return
        val r = Runnable {
            pending.remove(s)
            if (!released && enabled && desired[s] == g.id && s.id == g.id &&
                s.tex.isAttachedToWindow && s !in active && active.size < limit()) start(s, g)
        }
        pending[s] = r
        ui.postDelayed(r, 1_500L * n)
    }
    /** Отложенные старты после перезапуска (по ячейке): снимаются в [stop], [stopAll], [release]. */
    private val pending = HashMap<Slot, Runnable>()
    private var desired: Map<Slot, String> = emptyMap()
    private var released = false
    private fun cancelPending(s: Slot) { pending.remove(s)?.let { ui.removeCallbacks(it) } }
    private val gifs = HashMap<Slot, Gif>()
    private val tick: Runnable = object : Runnable {
        override fun run() {
            for ((p, c) in cuts) {
                val pos = p.currentPosition
                // кусок доигран (или ролик кончился и плеер ушёл по кругу в начало) — следующий. Сравнивать позицию с началом
                // куска нельзя: seek к ключевому кадру (CLOSEST_SYNC) приземляется чуть раньше
                if (pos >= c.at[c.i] + c.len || c.looped) { c.looped = false; c.i = (c.i + 1) % c.at.size; if (c.i == 0) c.round = true; p.seekTo(c.at[c.i]) }
            }
            updateBars()
            if (cuts.isNotEmpty() || active.keys.any { it !in rendered }) ui.postDelayed(this, 150)
        }
    }

    /** Полоса загрузки превью: доля скачанного (куски нарезки сыграны / буфер дошёл до конца); скачано — полоса гаснет. */
    private fun updateBars() {
        for ((s, p) in active) {
            val b = s.bar ?: continue
            val c = cuts[p]
            val done = s in rendered || (p.playbackState == Player.STATE_READY && loaded(p))
            val frac = when {
                done -> 1f
                c != null -> ((c.i + ((p.currentPosition - c.at[c.i]).toFloat() / c.len).coerceIn(0f, 1f)) / c.at.size).coerceIn(0f, 0.97f)
                p.duration > 0 -> (p.bufferedPosition.toFloat() / p.duration).coerceIn(0f, 0.97f)
                else -> 0.05f
            }
            b.pivotX = 0f
            b.scaleX = maxOf(frac, 0.05f)
            if (done) { if (b.visibility == android.view.View.VISIBLE) b.animate().alpha(0f).setDuration(400).withEndAction { b.visibility = android.view.View.GONE }.start() }
            else { b.animate().cancel(); b.alpha = 1f; b.visibility = android.view.View.VISIBLE }
        }
    }

    /** Куски: начало (после заставки сайта), ~33 %, ~66 % и концовка; длина — 8 % ролика, но 1,5–2,5 с. */
    private fun plan(p: ExoPlayer, durSec: Double, introMs: Long, id: String) {
        // горячие моменты пришли позже (со страницы ролика при раскрытии адреса) — заменить 10/50/80 на них
        if (durSec < CUTS_MIN || cuts[p]?.let { it.hot || Hotspots.cuts(id, (durSec * 1000).toLong(), it.len) == null } == true) return
        val d = (durSec * 1000).toLong()
        val len = (d * 0.08).toLong().coerceIn(1500, 2500)
        // три куска — начало, середина и ближе к концу ([previewCuts]): сюжет понятен, титры студий в начале мимо;
        // и на медленной сети так же — куски маленькие, один раз скачаны, дальше играют из кэша
        // горячие моменты (Pornhub и сеть, [Hotspots]) — если известны; иначе 10/50/80 %
        val hot = Hotspots.cuts(id, d, len)?.takeIf { it[0] >= introMs }
        val at = hot ?: previewCuts(d, introMs, len)
        if (at[0] > 0) p.seekTo(at[0])
        cuts[p] = Cuts(at, len, hot != null)
        ui.removeCallbacks(tick); ui.post(tick)
    }
    var enabled = true
        set(value) {
            field = value
            if (!value) stopAll()
        }

    /**
     * Сколько играют сразу: все на экране, до предела телефона и декодеров. На медленной сети — столько же, но новые из
     * сети запускаются по очереди ([pick]) и крутят короткий кусок из кэша ([plan]).
     */
    fun limit() = when { !enabled -> 0; Video.slow -> minOf(total, decoderCap); else -> minOf(total, decoderCap) }
    /** Всего превью разом по классу телефона ([DeviceTier.previewTotal]); сверх аппаратных — программным декодером. */
    private val total by lazy { DeviceTier.previewTotal(tier) }
    /** Аппаратных превью — не больше этого (сторож снижает, если видел аппаратное превью без кадров). */
    private var hwCap = Int.MAX_VALUE
    /**
     * Потолок по железу: класс телефона (DeviceTier.previewLimit: слабый 3, средний 5, мощный 8) и число аппаратных
     * декодеров H.264 минус два на плеер (DeviceTier.avcInstances). Уменьшается сам, если сторож видел превью без кадров.
     */
    private val tier by lazy { DeviceTier.detect(ctx) }
    /** Пока фоновый опрос декодеров не кончился, [DeviceTier.avcInstancesCached] даёт 0 — тогда лимит по классу телефона. */
    private val hwLimit get() = DeviceTier.previewLimit(tier, DeviceTier.avcInstancesCached())
    private var decoderCap = Int.MAX_VALUE
    private val SLOW_NET = 2

    /**
     * Кому играть: по порядку важности. На медленной сети новые превью из сети запускаются по очереди: одновременно
     * качают не больше [SLOW_NET]; скачавшее свой кусок ([loaded]) крутит его из кэша и место освобождает — тогда
     * запускается следующее ([onWarm]).
     */
    private fun pick(sorted: List<Pair<Slot, Gif>>): List<Pair<Slot, Gif>> {
        if (!Video.slow) return sorted.take(limit())
        var warming = active.keys.count { it !in rendered }
        return sorted.filter { (s, g) -> s in active || Video.fullyCached(g.sd) || warming++ < SLOW_NET }.take(limit())
    }
    /** Кусок превью скачан: буфер дошёл до конца куска нарезки, а без нарезки — до конца ролика. */
    private fun loaded(p: ExoPlayer): Boolean {
        val c = cuts[p]
        // нарезка: все три куска сыграны хотя бы раз — лежат в кэше; без нарезки — буфер дошёл до конца ролика
        if (c != null) return c.round
        val end = p.duration.takeIf { it > 0 } ?: return false
        return p.bufferedPosition >= end - 100
    }
    /** Ячейки, чьё превью уже скачало свой кусок (или ролик целиком) — дальше крутится из кэша, сеть не просит. */
    private val rendered = HashSet<Slot>()
    /** Кусок превью скачан — можно запускать следующее (сетка зовёт [update] заново). */
    var onWarm: (() -> Unit)? = null

    /** Ячейка, которой коснулись, — первая в очереди: превью запускается сразу. */
    @Volatile var touched: Slot? = null

    /**
     * Плеер превью. [sw] — с программным декодером (Android сам декодирует H.264 программно): им играют превью сверх
     * числа аппаратных декодеров, которые остаются первым плиткам и плееру. У всех — запасной декодер: не дали
     * аппаратный — берётся программный, а не замирает картинка.
     */
    private fun player(sw: Boolean): ExoPlayer = (if (sw) idleSw else idle).removeLastOrNull() ?: ExoPlayer.Builder(ctx,
        DefaultRenderersFactory(ctx).setEnableDecoderFallback(true).apply { if (sw) setMediaCodecSelector(SOFT_FIRST) })
        .setMediaSourceFactory(DefaultMediaSourceFactory(Video.bgPlay))
        // превью: старт с четверти секунды, впрок немного — канал нужнее тому, что откроют
        .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(1_000, 4_000, 250, 500).build())
        .build().apply {
            volume = 0f; repeatMode = Player.REPEAT_MODE_ONE
            // куски нарезки начинаются с ближайшего ключевого кадра: точный seek декодировал бы с предыдущего ключевого
            // вхолостую (картинка стоит), а начало куска на долю секунды раньше — не заметно
            setSeekParameters(SeekParameters.CLOSEST_SYNC)
        }

    /** Какие ячейки играют: видимые целиком, первые [limit] по порядку. */
    fun update(visible: List<Pair<Slot, Gif>>) {
        if (released || !enabled) { stopAll(); return }
        val t = touched
        val candidates = visible.filter { (s, g) -> s.id == g.id && s.tex.isAttachedToWindow }
        if (t != null && candidates.none { it.first === t }) touched = null
        val want = pick(candidates.sortedBy { if (it.first === t) 0 else 1 })
        val next = want.associate { (slot, g) -> slot to g.id }
        pending.keys.filter { desired[it] != next[it] || it !in next }.forEach { cancelPending(it) }
        desired = next
        active.keys.filter { it !in desired }.forEach { stop(it) }
        // Do not resurrect the old binding or bypass an already scheduled retry.
        want.forEach { (s, g) -> if (s in active && s.playing != g.id) stop(s) }
        want.forEach { (s, g) -> if (s !in active && s !in pending && !retries.exhausted(g.id)) start(s, g) }
    }

    private fun start(s: Slot, g: Gif) {
        if (released || !enabled || desired[s] != g.id || s.id != g.id ||
            !s.tex.isAttachedToWindow || active.size >= limit() || retries.exhausted(g.id)) return
        cancelPending(s)
        // аппаратных декодеров — сколько есть (минус плееру), остальным плиткам — программный
        val sw = active.values.count { it !in soft } >= minOf(hwLimit, hwCap)
        val p = runCatching { player(sw) }.getOrElse {
            retries.failure(g.id)
            android.util.Log.w("Loopa", "Preview player creation failed: ${it.javaClass.simpleName}")
            return
        }
        if (sw) soft += p
        active[s] = p; s.player = p; s.playing = g.id; gifs[s] = g
        val w = Watch(); watch[p] = w
        try {
        val fl = androidx.media3.exoplayer.video.VideoFrameMetadataListener { _, _, _, _ -> w.frames.incrementAndGet() }
        w.listener = fl; p.setVideoFrameMetadataListener(fl)
        ui.removeCallbacks(guard); ui.postDelayed(guard, 1_000)
        s.tex.alpha = 0f
        s.bar?.let { it.animate().cancel(); it.pivotX = 0f; it.scaleX = 0.05f; it.alpha = 1f; it.visibility = android.view.View.VISIBLE }
        ui.removeCallbacks(tick); ui.post(tick)
        p.clearVideoSurface()
        p.setVideoTextureView(s.tex)
        p.addListener(object : Player.Listener {
            override fun onVideoSizeChanged(v: VideoSize) { if (s.player === p) crop(s.tex, v.width, v.height) }
            // ошибка сети — не гасить до следующей прокрутки: перезапуск через пару секунд (до [MAX_RETRIES] раз)
            override fun onPlayerError(e: androidx.media3.common.PlaybackException) { if (s.player === p) restart(s) }
            override fun onPositionDiscontinuity(o: Player.PositionInfo, n: Player.PositionInfo, reason: Int) {
                if (reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION && s.player === p) cuts[p]?.looped = true
            }
            override fun onRenderedFirstFrame() { if (s.player === p && s.id == g.id && desired[s] == g.id && enabled && !released) s.tex.alpha = 1f }
            // длительность из API бывает пустой (Imgur) — тогда берём у плеера, когда он её узнал
            override fun onPlaybackStateChanged(st: Int) { if (st == Player.STATE_READY && s.player === p && p.duration > 0) plan(p, p.duration / 1000.0, Sources.intro(g), g.id) }
        }.also { listeners[p] = it })
        // ролик перенесён в файл ([CachedExport]) — играем из него, не из кэша
        val local = CachedExport.localUri(g)
        if (local != null) CachedExport.played(g)
        val u = local?.toString() ?: g.sd
        p.setMediaItem(MediaItem.Builder().setUri(u).setMediaId(u).apply { if (g.hls && local == null) setMimeType(MimeTypes.APPLICATION_M3U8) }.build())
        p.prepare(); p.play()
        val intro = Sources.intro(g)
        if (g.duration > 0) plan(p, g.duration, intro, g.id)
        else if (intro > 0) p.seekTo(intro)   // длительность неизвестна — хотя бы первый кусок уже за заставкой
        } catch (e: Exception) {
            android.util.Log.w("Loopa", "Preview setup failed: ${e.javaClass.simpleName}")
            discard(s)
            retries.failure(g.id)
        }
    }
    private val listeners = HashMap<ExoPlayer, Player.Listener>()

    /** Ячейку переиспользовали или она ушла с экрана — превью остановить, плеер в запас. */
    fun stop(s: Slot) = detach(s, reuse = true)

    private fun discard(s: Slot) = detach(s, reuse = false)

    private fun detach(s: Slot, reuse: Boolean) {
        cancelPending(s)
        val p = active.remove(s) ?: return
        s.player = null; s.playing = null; s.tex.alpha = 0f
        s.bar?.let { it.animate().cancel(); it.visibility = android.view.View.GONE }
        val listener = listeners.remove(p)
        val frame = watch.remove(p)?.listener
        cuts.remove(p); gifs.remove(s); rendered.remove(s)
        val sw = soft.remove(p)
        val ok = runCatching {
            listener?.let { p.removeListener(it) }
            frame?.let { p.clearVideoFrameMetadataListener(it) }
            p.clearVideoTextureView(s.tex)
            p.stop(); p.clearMediaItems()
        }.isSuccess
        // Broken players are never returned to the pool; cap retained idle resources.
        if (ok && reuse && !released && idle.size + idleSw.size < total) {
            if (sw) idleSw += p else idle += p
        } else runCatching { p.release() }
    }

    /** Called when RecyclerView actually detaches or rebinds a slot. */
    fun unbind(s: Slot) {
        desired = desired - s
        if (touched === s) touched = null
        stop(s)
    }

    fun stopAll() {
        desired = emptyMap()
        touched = null
        pending.keys.toList().forEach { cancelPending(it) }
        active.keys.toList().forEach { discard(it) }
        ui.removeCallbacksAndMessages(null)
        (idle + idleSw).forEach { runCatching { it.release() } }
        idle.clear(); idleSw.clear()
    }

    fun release() {
        if (released) return
        released = true
        stopAll()
        retries.clear()
        onWarm = null
    }

    /** Заполнить ячейку как CENTER_CROP: TextureView иначе растягивает кадр. */
    private fun crop(t: TextureView, vw: Int, vh: Int) {
        val w = t.width.toFloat(); val h = t.height.toFloat()
        if (vw <= 0 || vh <= 0 || w <= 0 || h <= 0) return
        val s = maxOf(w / vw, h / vh)
        t.setTransform(Matrix().apply { setScale(vw * s / w, vh * s / h, w / 2, h / 2) })
    }

}
