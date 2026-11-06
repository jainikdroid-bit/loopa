package app.loopa

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.preload.DefaultPreloadManager
import androidx.media3.exoplayer.source.preload.TargetPreloadStatusControl
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Плеер как в TikTok — по схеме официального демо Google «shortform» (androidx/media, demos/shortform):
 * вертикальный ViewPager2, DefaultPreloadManager заранее грузит по секунде в соседние ролики (свайп — видео сразу),
 * общий дисковый кэш, старт после 0,5 с буфера. Один ExoPlayer переезжает на видимую страницу.
 * Касание — пауза; справа — лайк, скачать, звук, качество; снизу — автор, теги, перемотка.
 */
@OptIn(UnstableApi::class)
class PlayerActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var player: ExoPlayer
    private lateinit var preload: DefaultPreloadManager
    private val control = Control()
    /** Ролики под управлением PreloadManager: номер в выдаче → MediaItem (окно вокруг текущего). */
    private val managed = HashMap<Int, MediaItem>()
    private lateinit var pager: ViewPager2
    private val rv get() = pager.getChildAt(0) as RecyclerView
    private var current = -1
    private var holder: PageHolder? = null
    private val prefs by lazy { getSharedPreferences("loopa", MODE_PRIVATE) }
    /** Качество: auto (по скорости сети) / hd / sd. */
    private var quality get() = prefs.getString("quality", "auto")!!; set(v) { prefs.edit().putString("quality", v).apply() }
    private fun url(g: Gif) = when (quality) { "hd" -> g.hd; "sd" -> g.sd; else -> Video.auto(g) }
    private fun qLabel() = when (quality) { "hd" -> "HD"; "sd" -> "SD"; else -> "Авто" }
    /** Высота системных кнопок снизу и справа (экран от края до края) — панель управления над ними. */
    private var navBottom = 0
    private var navRight = 0
    private var muted get() = prefs.getBoolean("muted", false); set(v) { prefs.edit().putBoolean("muted", v).apply() }
    private val onFeed: () -> Unit = { pager.adapter?.notifyDataSetChanged(); sync() }

    /**
     * Как в демо: соседние ±2 — загрузить первую секунду, до ±4 — подготовить источник (заголовок файла), дальше — ничего.
     * Медленная сеть ([Video.slow]): только следующий ролик и полсекунды — канал нужен текущему.
     */
    inner class Control : TargetPreloadStatusControl<Int, DefaultPreloadManager.PreloadStatus> {
        var cur = C.INDEX_UNSET
        override fun getTargetPreloadStatus(rank: Int): DefaultPreloadManager.PreloadStatus? {
            val d = abs(rank - cur)
            if (Video.slow) return when {
                rank == cur + 1 -> DefaultPreloadManager.PreloadStatus.specifiedRangeLoaded(500L)
                d <= 2 -> DefaultPreloadManager.PreloadStatus.SOURCE_PREPARED
                else -> null
            }
            return when {
                d <= 2 -> DefaultPreloadManager.PreloadStatus.specifiedRangeLoaded(1000L)
                d <= 4 -> DefaultPreloadManager.PreloadStatus.SOURCE_PREPARED
                else -> null
            }
        }
    }

    /** Нет связи: повтор через 2, 4, 8… 30 с или сразу, как связь вернулась. */
    private var fails = 0
    private val replay = Runnable { if (player.playerError != null) { player.prepare(); player.play() } }
    private val onOnline: () -> Unit = { ui.removeCallbacks(replay); replay.run() }
    /** С какого момента текущий ролик стоит в буферизации (0 — играет). */
    private var bufferingSince = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = Color.BLACK; window.navigationBarColor = Color.BLACK
        Video.init(this); Interests.init(this)
        Net.init(this)
        // буфер как в демо shortform: старт после 0,5 с, держим 5–20 с вперёд; после подвисания — 2,5 с запаса
        val load = DefaultLoadControl.Builder().setBufferDurationsMs(5_000, 20_000, 500, 2_500)
            .setPrioritizeTimeOverSizeThresholds(true).build()
        val pb = DefaultPreloadManager.Builder(applicationContext, control).setLoadControl(load).setBandwidthMeter(Video.meter)
            .setMediaSourceFactory(androidx.media3.exoplayer.source.DefaultMediaSourceFactory(Video.source)
                // обрывы на плохой сети: до 10 попыток догрузить кусок, прежде чем сдаться
                .setLoadErrorHandlingPolicy(androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy(10)))
        player = pb.buildExoPlayer().apply {
            repeatMode = Player.REPEAT_MODE_ONE
            volume = if (muted) 0f else 1f
            addListener(object : Player.Listener {
                override fun onRenderedFirstFrame() { holder?.poster?.visibility = View.GONE }
                override fun onVideoSizeChanged(s: VideoSize) { holder?.fit(s.width, s.height) }
                override fun onPlaybackStateChanged(st: Int) {
                    holder?.spin?.visibility = if (st == Player.STATE_BUFFERING) View.VISIBLE else View.GONE
                    bufferingSince = if (st == Player.STATE_BUFFERING) bufferingSince.takeIf { it > 0 } ?: System.currentTimeMillis() else 0L
                    if (st == Player.STATE_READY) { fails = 0; holder?.spin?.text = "загрузка…" }
                }
                override fun onPlayerError(e: androidx.media3.common.PlaybackException) {
                    // сеть: показать и повторить самим; прочее (битый файл) — следующий ролик
                    if (e.errorCode in 2000..2999) {
                        holder?.spin?.apply { text = "нет связи — повторю…"; visibility = View.VISIBLE }
                        ui.removeCallbacks(replay); ui.postDelayed(replay, minOf(30_000L, 2000L shl minOf(fails, 4))); fails++
                    } else if (current + 1 < Feed.items.size) pager.setCurrentItem(current + 1, true)
                }
            })
        }
        preload = pb.build()
        pager = ViewPager2(this).apply {
            orientation = ViewPager2.ORIENTATION_VERTICAL; setBackgroundColor(Color.BLACK)
            offscreenPageLimit = 1
            adapter = PageAdapter()
            registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) { settle(position) }
            })
        }
        pager.setOnApplyWindowInsetsListener { _, ins ->
            @Suppress("DEPRECATION") run { navBottom = ins.systemWindowInsetBottom; navRight = ins.systemWindowInsetRight }
            for (i in 0 until rv.childCount) (rv.getChildViewHolder(rv.getChildAt(i)) as? PageHolder)?.pad()
            ins
        }
        setContentView(pager)
        pager.setCurrentItem(intent.getIntExtra("pos", 0), false)
        Feed.listeners += onFeed
        Net.online += onOnline
        tick()
    }

    override fun onPause() { super.onPause(); player.pause(); report() }
    override fun onResume() { super.onResume(); if (current >= 0) { player.play(); shownAt = System.currentTimeMillis() } }
    override fun onDestroy() {
        Feed.listeners -= onFeed; Net.online -= onOnline; ui.removeCallbacksAndMessages(null)
        player.release(); preload.release()
        super.onDestroy()
    }

    /** v.redd.it — поток HLS (качество подстраивает сам плеер), остальное — mp4 выбранного качества. */
    private fun item(g: Gif) = url(g).let { u ->
        MediaItem.Builder().setUri(u).setMediaId(u).apply { if (g.hls) setMimeType(androidx.media3.common.MimeTypes.APPLICATION_M3U8) }.build()
    }

    /** Держать в PreloadManager окно вокруг текущего: −2…+6 (вперёд листают чаще). */
    private fun sync() {
        if (current < 0) return
        val want = (maxOf(0, current - 2)..minOf(Feed.items.size - 1, current + 6)).toSet()
        managed.keys.filter { it !in want }.forEach { preload.remove(managed.remove(it)!!) }
        want.filter { it !in managed }.forEach { i -> item(Feed.items[i]).also { managed[i] = it; preload.add(it, i) } }
        preload.invalidate()
    }

    /** Сколько текущий ролик был на экране — сигнал для «Для вас» ([Interests.watched]). */
    private var shownAt = 0L
    private fun report() {
        val g = Feed.items.getOrNull(current) ?: return
        if (shownAt == 0L) return
        val sec = (System.currentTimeMillis() - shownAt) / 1000.0
        val dur = if (g.duration > 0) g.duration else player.duration.takeIf { it > 0 }?.div(1000.0) ?: 0.0
        Interests.watched(g, if (dur > 0) sec / dur else 0.0, sec)
        shownAt = 0L
    }

    /** Страница выбрана — играть её ролик (источник уже подгружен PreloadManager). */
    private fun settle(pos: Int) {
        if (pos == current || pos !in Feed.items.indices) return
        val h = rv.findViewHolderForAdapterPosition(pos) as? PageHolder ?: run { pager.post { settle(pos) }; return }
        report(); shownAt = System.currentTimeMillis()
        holder?.poster?.visibility = View.VISIBLE
        current = pos; holder = h
        control.cur = pos; preload.setCurrentPlayingIndex(pos)
        sync()
        player.setVideoTextureView(h.video)
        player.setMediaSource(preload.getMediaSource(managed[pos]!!) ?: return)
        player.prepare(); player.play()
        if (pos > Feed.items.size - 6) Feed.more()
    }

    /** Качество сменили — перезапустить текущий с той же секунды, окно предзагрузки — заново. */
    private fun requality() {
        val at = player.currentPosition
        managed.values.forEach { preload.remove(it) }; managed.clear()
        sync()
        player.setMediaSource(preload.getMediaSource(managed[current]!!) ?: return, at)
        player.prepare(); player.play()
    }

    /** Полоса перемотки текущей страницы. */
    private fun tick() {
        // «Авто» на HD подвис дольше 4 с — сеть не тянет: этот и следующие ролики в SD, с той же секунды
        if (quality == "auto" && bufferingSince > 0 && System.currentTimeMillis() - bufferingSince > 4000 && !Video.sdOnly) {
            Feed.items.getOrNull(current)?.takeIf { it.sd != it.hd && player.currentMediaItem?.mediaId == it.hd }?.let { Video.stalled(); bufferingSince = 0; requality() }
        }
        holder?.let { h ->
            val d = player.duration
            if (d > 0 && !h.dragging) { h.seek.max = d.toInt(); h.seek.progress = player.currentPosition.toInt() }
        }
        ui.postDelayed({ tick() }, 250)
    }

    /** Лайкнутые — помним у себя (в «Моих лайках» — все). */
    private fun liked(id: String) = Feed.mode == "liked" && !prefs.getStringSet("unliked", emptySet())!!.contains(id) || prefs.getStringSet("liked", emptySet())!!.contains(id)
    private fun setLiked(id: String, on: Boolean) {
        val l = HashSet(prefs.getStringSet("liked", emptySet())!!); val u = HashSet(prefs.getStringSet("unliked", emptySet())!!)
        if (on) { l += id; u -= id } else { l -= id; u += id }
        prefs.edit().putStringSet("liked", l).putStringSet("unliked", u).apply()
    }

    private fun back(extra: String, v: String) {
        startActivity(Intent(this, MainActivity::class.java).putExtra(extra, if (extra == "login") null else v).putExtra("login", extra == "login").addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        finish()
    }

    inner class PageHolder(root: FrameLayout) : RecyclerView.ViewHolder(root) {
        val video = TextureView(this@PlayerActivity)
        val poster = ImageView(this@PlayerActivity).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        val spin = TextView(this@PlayerActivity).apply { text = "загрузка…"; setTextColor(Color.WHITE); textSize = 14f; visibility = View.GONE }
        val user = TextView(this@PlayerActivity).apply { setTextColor(Color.WHITE); textSize = 16f; setShadowLayer(4f, 0f, 0f, Color.BLACK) }
        val info = TextView(this@PlayerActivity).apply { setTextColor(0xFFDDDDDD.toInt()); textSize = 12f; setShadowLayer(4f, 0f, 0f, Color.BLACK) }
        val tags = LinearLayout(this@PlayerActivity).apply { orientation = LinearLayout.HORIZONTAL }
        val seek = SeekBar(this@PlayerActivity)
        var dragging = false
        private var col: View? = null
        private var bottom: View? = null

        /** Отступы под системные кнопки (их высота приходит в pager.setOnApplyWindowInsetsListener). */
        fun pad() {
            bottom?.setPadding(dp(12), dp(8), dp(12) + navRight, dp(16) + navBottom)
            (col?.layoutParams as? FrameLayout.LayoutParams)?.let { it.marginEnd = dp(8) + navRight; col?.layoutParams = it }
        }
        val sound = side("")
        val qBtn = side("")
        val dl = side("⬇")
        val like = side("♡")
        val pausedMark = TextView(this@PlayerActivity).apply { text = "❚❚"; textSize = 48f; setTextColor(0xCCFFFFFF.toInt()); visibility = View.GONE }

        init {
            root.layoutParams = RecyclerView.LayoutParams(-1, -1)
            root.addView(video, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
            root.addView(poster, FrameLayout.LayoutParams(-1, -1))
            root.addView(spin, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
            root.addView(pausedMark, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
            root.setOnClickListener {
                if (player.isPlaying) { player.pause(); pausedMark.visibility = View.VISIBLE } else { player.play(); pausedMark.visibility = View.GONE }
            }
            val col = LinearLayout(this@PlayerActivity).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
                addView(like); addView(dl); addView(sound); addView(qBtn) }
            root.addView(col, FrameLayout.LayoutParams(-2, -2, Gravity.END or Gravity.CENTER_VERTICAL).apply { marginEnd = dp(8) })
            this.col = col
            val bottom = LinearLayout(this@PlayerActivity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(8), dp(12), dp(16))
                background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0xCC000000.toInt(), 0x00000000))
                addView(user); addView(info)
                addView(HorizontalScrollView(this@PlayerActivity).apply { isHorizontalScrollBarEnabled = false; addView(tags) }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
                addView(seek, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
            }
            root.addView(bottom, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
            this.bottom = bottom
            seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) { if (fromUser && holder === this@PageHolder) player.seekTo(p.toLong()) }
                override fun onStartTrackingTouch(s: SeekBar) { dragging = true }
                override fun onStopTrackingTouch(s: SeekBar) { dragging = false }
            })
            pad()
        }

        /** Видео вписать в экран с сохранением пропорций. */
        fun fit(w: Int, h: Int) {
            if (w <= 0 || h <= 0) return
            val pw = itemView.width.toFloat(); val ph = itemView.height.toFloat()
            val s = minOf(pw / w, ph / h)
            video.layoutParams = FrameLayout.LayoutParams((w * s).roundToInt(), (h * s).roundToInt(), Gravity.CENTER)
        }

        fun bind(g: Gif) {
            poster.visibility = View.VISIBLE; pausedMark.visibility = View.GONE
            Thumbs.load(poster, g.thumb)
            fit(g.w, g.h)
            // пост Reddit: автор — его посты на Reddit, вместо просмотров — рейтинг и комментарии, ниже — заголовок
            user.text = if (g.reddit) "u/${g.user}" else "@${g.user}"
            user.setOnClickListener { if (g.user.isNotBlank()) { if (g.reddit) back("sub", "u/${g.user}") else back("author", g.user) } }
            info.maxLines = 3
            info.text = if (g.reddit) "${MainActivity.time(g.duration)} · ↑ ${MainActivity.count(g.likes)} · 💬 ${MainActivity.count(g.views)}" +
                (if (!g.hasAudio) " · без звука" else "") + (if (g.description.isNotBlank()) "\n" + g.description else "")
                else "${MainActivity.time(g.duration)} · ${MainActivity.count(g.views)} просмотров · ♥ ${MainActivity.count(g.likes)}" + if (!g.hasAudio) " · без звука" else ""
            tags.removeAllViews()
            g.tags.forEach { t ->
                tags.addView(TextView(this@PlayerActivity).apply {
                    text = if (t.startsWith("r/")) t else "#$t"; setTextColor(Color.WHITE); textSize = 13f; setPadding(dp(10), dp(5), dp(10), dp(5))
                    background = GradientDrawable().apply { setColor(0x66FFFFFF); cornerRadius = dp(20).toFloat() }
                    setOnClickListener { if (t.startsWith("r/")) back("sub", t.substring(2)) else back("tag", t) }
                }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(6) })
            }
            dl.setOnClickListener { Downloads.get(this@PlayerActivity, g); Interests.download(g) }
            // качество у потока HLS выбирает сам плеер
            qBtn.visibility = if (g.hls) View.GONE else View.VISIBLE
            fun showLike() { like.text = if (liked(g.id)) "♥" else "♡"; like.setTextColor(if (liked(g.id)) 0xFFFF4D5E.toInt() else Color.WHITE) }
            showLike()
            like.setOnClickListener {
                // Reddit: лайк — только у себя, для «Для вас» (голос на Reddit не ставим)
                if (g.reddit) { val on = !liked(g.id); setLiked(g.id, on); showLike(); Interests.like(g, on); return@setOnClickListener }
                if (!Api.loggedIn) { back("login", "1"); return@setOnClickListener }
                val on = !liked(g.id); setLiked(g.id, on); showLike(); Interests.like(g, on)
                val src = when {
                    Feed.mode == "foryou" -> "for-you"; Feed.mode == "liked" -> "favourites"; Feed.author != null -> "profile"
                    Feed.text.isNotEmpty() -> if (Feed.byTag) "tag-gifs" else "search-gifs"; else -> "trending"
                }
                val position = bindingAdapterPosition
                kotlin.concurrent.thread {
                    val err = runCatching { Api.like(g.id, on, src, position) }.getOrElse { it.message ?: "нет связи" }
                    if (err != null) ui.post { setLiked(g.id, !on); showLike(); Interests.like(g, !on); android.widget.Toast.makeText(this@PlayerActivity, "Лайк не прошёл — $err", android.widget.Toast.LENGTH_LONG).show() }
                }
            }
            sound.text = if (muted) "🔇" else "🔊"
            sound.setOnClickListener { muted = !muted; player.volume = if (muted) 0f else 1f; sound.text = if (muted) "🔇" else "🔊" }
            qBtn.text = qLabel(); qBtn.textSize = 15f
            qBtn.setOnClickListener {
                this@PlayerActivity.quality = when (this@PlayerActivity.quality) { "auto" -> "hd"; "hd" -> "sd"; else -> "auto" }
                qBtn.text = qLabel()
                android.widget.Toast.makeText(this@PlayerActivity, when (this@PlayerActivity.quality) { "hd" -> "Всегда HD"; "sd" -> "Всегда SD (быстрее)"; else -> "Авто: HD на быстрой сети, SD на медленной" }, android.widget.Toast.LENGTH_SHORT).show()
                if (holder === this) requality()
            }
            pad()
            seek.progress = 0
        }
    }

    private fun side(t: String) = TextView(this).apply {
        text = t; textSize = 22f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x66000000) }
        layoutParams = LinearLayout.LayoutParams(dp(54), dp(54)).apply { bottomMargin = dp(12) }
    }

    private inner class PageAdapter : RecyclerView.Adapter<PageHolder>() {
        override fun getItemCount() = Feed.items.size
        override fun onCreateViewHolder(p: ViewGroup, t: Int) = PageHolder(FrameLayout(p.context))
        override fun onBindViewHolder(h: PageHolder, pos: Int) = h.bind(Feed.items[pos])
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()
}
