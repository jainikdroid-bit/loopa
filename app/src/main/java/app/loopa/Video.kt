package app.loopa

import android.content.Context
import android.net.ConnectivityManager
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.common.C
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy

/**
 * Общее для плеера: дисковый кэш видео (LRU, размер — авто по свободному месту 512 МБ…1 ГБ или из меню; в него пишут и
 * плеер, и предзагрузка соседей, и живые превью сетки — пересмотр без сети, назад без повторной закачки), оценка
 * скорости сети для качества «Авто» и вид сети для умной предзагрузки ([net]).
 */
@OptIn(UnstableApi::class)
object Video {
    lateinit var cache: SimpleCache; private set
    lateinit var meter: DefaultBandwidthMeter; private set
    /** Источник данных: сеть через кэш; скорость закачки идёт в meter. */
    lateinit var source: CacheDataSource.Factory; private set

    /**
     * Начальная оценка скорости — 4,5 Мбит/с (типичный канал через VPN/спутник ~5 Мбит/с), а не оптимистичная по стране
     * и Wi-Fi: «Авто» стартует с SD и переходит на HD, только когда замер покажет, что сеть тянет ([auto]).
     */
    private const val INITIAL_BITRATE = 4_500_000L

    private lateinit var app: Context
    private val evictor = VideoCacheEvictor(512 * PreloadPolicy.MB)
    private val dir get() = app.cacheDir.resolve("video")

    /** Размер кэша из меню, МБ (0 — авто), см. [PreloadPolicy.CACHE_CHOICES]. */
    var cacheChoice: Int
        get() = app.getSharedPreferences("loopa", Context.MODE_PRIVATE).getInt("video_cache_mb", 0)
        set(v) {
            app.getSharedPreferences("loopa", Context.MODE_PRIVATE).edit().putInt("video_cache_mb", v).apply()
            applyCacheSize()
        }
    /** Предел кэша сейчас, байт. */
    val cacheLimit get() = evictor.max
    /** Занято кэшем, байт. */
    val cacheUsed get() = cache.cacheSpace

    /** Пересчитать предел (выбор в меню, свободное место) и сразу ужать кэш, если он больше. */
    private fun applyCacheSize() {
        val used = if (::cache.isInitialized) cache.cacheSpace else 0L
        evictor.max = PreloadPolicy.cacheBytes(cacheChoice, dir.apply { mkdirs() }.usableSpace, used)
        if (::cache.isInitialized) synchronized(cache) { evictor.trim(cache) }
    }

    fun init(ctx: Context) {
        if (::cache.isInitialized) return
        app = ctx.applicationContext
        applyCacheSize()
        cache = SimpleCache(dir, evictor, StandaloneDatabaseProvider(app))
        android.util.Log.i("Loopa", "кэш видео: занято ${cache.cacheSpace shr 20} МБ из ${evictor.max shr 20} МБ")
        meter = DefaultBandwidthMeter.Builder(app).setInitialBitrateEstimate(INITIAL_BITRATE).build()
        // один OkHttp на всё ([Net.http]): соединения к CDN переиспользуются (HTTP/2), DNS и переадресации помнятся;
        // запрос — открытый диапазон «с позиции до конца», видео идёт одним потоком, а не мелкими кусками
        val http = OkHttpDataSource.Factory(Net.http).setUserAgent(Api.UA).setTransferListener(Counted(meter))
        // Erome отдаёт видео только с Referer своего сайта (без него 403)
        val ref = ResolvingDataSource.Factory(http) { spec -> resolveSpec(spec) }
        source = cached(ref)
        play = Split.Factory(source, ref)
        // фон (живые превью сетки, видео заранее) — тот же кэш, но свои соединения ([Net.bg]): открыли ролик — фон
        // обрывается целиком и не стоит в очереди перед ним
        val bgHttp = OkHttpDataSource.Factory(Net.bg).setUserAgent(Api.UA).setTransferListener(Counted(meter))
        val bgRef = ResolvingDataSource.Factory(bgHttp) { spec -> resolveSpec(spec) }
        bgSource = cached(bgRef)
        bgPlay = Split.Factory(bgSource, bgRef)
    }

    /**
     * Запрос к CDN: ленивый адрес ([Sources]) заменяется настоящим (сеть, но мы уже в потоке загрузки), заголовки сайта
     * добавляются. Ключ кэша берётся из исходного запроса ([CacheDataSource] стоит снаружи) — остаётся ленивым адресом.
     * Erome отдаёт видео только с Referer своего сайта (без него 403).
     */
    private fun resolveSpec(spec: androidx.media3.datasource.DataSpec): androidx.media3.datasource.DataSpec {
        var s = spec
        if (Sources.isLazy(s.uri.toString())) {
            val r = try { Sources.resolve(s.uri.toString()) } catch (e: Exception) { throw unresolved(e, s) }
            s = s.withUri(android.net.Uri.parse(r.url)).withAdditionalHeaders(r.headers)
        }
        return if (Erome.ours(s.uri.toString())) s.withAdditionalHeaders(mapOf("Referer" to Erome.REFERER)) else s
    }

    /**
     * Адрес ролика не узнать. Ролик удалён или закрыт (ответ 4xx, на странице нет видео) — как ответ 404: плеер не
     * повторяет ([errors]) и показывает «не открывается». Обрыв и таймаут — обычная ошибка сети, её повторяют.
     */
    private fun unresolved(e: Exception, spec: androidx.media3.datasource.DataSpec): java.io.IOException {
        val gone = (e is HttpError && !e.transient) || e is IllegalStateException || e is IllegalArgumentException
        if (!gone) return java.io.IOException("не удалось узнать адрес видео: ${e.message}", e)
        return HttpDataSource.InvalidResponseCodeException(404, e.message, java.io.IOException(e), emptyMap(), spec, ByteArray(0))
    }

    /** Ролик целиком лежит в дисковом кэше — превью и просмотр сети не просят. */
    fun fullyCached(url: String): Boolean {
        if (!::cache.isInitialized || url.isEmpty() || Sources.uncached(url)) return false
        return runCatching {
            val len = androidx.media3.datasource.cache.ContentMetadata.getContentLength(cache.getContentMetadata(url))
            len > 0 && cache.isCached(url, 0, len)
        }.getOrDefault(false)
    }

    /** Для плеера и превью: как [source]/[bgSource], но ленивый HLS-плейлист — мимо кэша ([Split]). */
    lateinit var play: DataSource.Factory; private set
    lateinit var bgPlay: DataSource.Factory; private set

    /**
     * Ленивые HLS-плейлисты (xHamster, FikFap — [Sources.uncached]) идут мимо дискового кэша: в кэше под постоянным
     * ленивым ключом остался бы плейлист со ссылками на сегменты, подписи которых через час-три истекают. Остальное
     * (mp4, сегменты по настоящим адресам) — через кэш, как раньше.
     */
    private class Split(private val cached: DataSource, private val direct: DataSource) : DataSource {
        private var cur: DataSource? = null
        override fun addTransferListener(l: androidx.media3.datasource.TransferListener) { cached.addTransferListener(l); direct.addTransferListener(l) }
        override fun open(spec: androidx.media3.datasource.DataSpec): Long =
            (if (Sources.uncached(spec.uri.toString())) direct else cached).also { cur = it }.open(spec)
        override fun read(buffer: ByteArray, offset: Int, length: Int) = cur!!.read(buffer, offset, length)
        override fun getUri(): android.net.Uri? = cur?.uri
        override fun getResponseHeaders(): Map<String, List<String>> = cur?.responseHeaders ?: emptyMap()
        override fun close() { try { cur?.close() } finally { cur = null } }
        class Factory(private val cached: DataSource.Factory, private val direct: DataSource.Factory) : DataSource.Factory {
            override fun createDataSource(): DataSource = Split(cached.createDataSource(), direct.createDataSource())
        }
    }

    /** Источник для фона: превью в сетке и предзагрузка ([Prefetch]). */
    lateinit var bgSource: CacheDataSource.Factory; private set

    private fun cached(upstream: DataSource.Factory) = CacheDataSource.Factory().setCache(cache)
        .setUpstreamDataSourceFactory(upstream).setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        .setEventListener(object : CacheDataSource.EventListener {
            override fun onCachedBytesRead(cacheSizeBytes: Long, cachedBytesRead: Long) { fromCache.addAndGet(cachedBytesRead) }
            override fun onCacheIgnored(reason: Int) {}
        })

    /**
     * Ошибки загрузки видео: обрывы и таймауты — повторять (до 10 попыток, пауза 1…5 с); ответ сервера 4xx (ролик удалён,
     * ссылка истекла, нет доступа) — сразу ошибка: повторы его не исправят, а «загрузка…» висела бы под минуту.
     */
    val errors: LoadErrorHandlingPolicy = object : DefaultLoadErrorHandlingPolicy(10) {
        override fun getRetryDelayMsFor(info: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
            var e: Throwable? = info.exception
            while (e != null) {
                if (e is HttpDataSource.InvalidResponseCodeException) {
                    val c = e.responseCode
                    if (c in 400..499 && c != 408 && c != 429) return C.TIME_UNSET
                }
                e = e.cause
            }
            return super.getRetryDelayMsFor(info)
        }
    }

    /** «Авто»: HD, если сеть тянет ~2 битрейта HD (RedGIFs HD ≈ 4–5 Мбит/с), иначе SD (в 5–6 раз легче). */
    fun auto(g: Gif) = if (!sdOnly && meter.bitrateEstimate >= SLOW_BPS) g.hd else g.sd

    /** До какого времени «Авто» держит SD: ролик подвис на HD — сеть не тянет, 10 минут не пробуем HD снова. */
    private var sdUntil = 0L
    val sdOnly get() = System.currentTimeMillis() < sdUntil
    fun stalled() { sdUntil = System.currentTimeMillis() + 10 * 60_000L }

    /**
     * Сеть для предзагрузки: экономия трафика (Data Saver для приложения на лимитной сети) — ничего не грузить заранее;
     * медленная ([slow]); лимитная (мобильная); иначе быстрая.
     */
    fun net(): NetKind {
        val cm = app.getSystemService(ConnectivityManager::class.java) ?: return NetKind.FAST
        val n = cm.activeNetwork ?: return NetKind.OFFLINE
        if (cm.getNetworkCapabilities(n) == null) return NetKind.OFFLINE
        val metered = cm.isActiveNetworkMetered
        return when {
            metered && cm.restrictBackgroundStatus == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED -> NetKind.SAVER
            slow -> NetKind.SLOW
            metered -> NetKind.METERED
            else -> NetKind.FAST
        }
    }

    /** Счётчики для журнала: сколько байт видео прочитано с диска и скачано из сети (с запуска). */
    val fromCache = java.util.concurrent.atomic.AtomicLong()
    val fromNet = java.util.concurrent.atomic.AtomicLong()

    /** Считает скачанное из сети и передаёт дальше в оценку скорости. */
    private class Counted(private val to: androidx.media3.datasource.TransferListener) : androidx.media3.datasource.TransferListener {
        override fun onTransferInitializing(s: androidx.media3.datasource.DataSource, d: androidx.media3.datasource.DataSpec, n: Boolean) = to.onTransferInitializing(s, d, n)
        override fun onTransferStart(s: androidx.media3.datasource.DataSource, d: androidx.media3.datasource.DataSpec, n: Boolean) = to.onTransferStart(s, d, n)
        override fun onBytesTransferred(s: androidx.media3.datasource.DataSource, d: androidx.media3.datasource.DataSpec, n: Boolean, b: Int) {
            if (n) fromNet.addAndGet(b.toLong()); to.onBytesTransferred(s, d, n, b)
        }
        override fun onTransferEnd(s: androidx.media3.datasource.DataSource, d: androidx.media3.datasource.DataSpec, n: Boolean) = to.onTransferEnd(s, d, n)
    }

    /**
     * Медленная сеть (< [SLOW_BPS] или недавно подвисали): предзагрузка соседей скромнее, чтобы не отнимать канал у текущего.
     * Порог 9 Мбит/с — тот же, что у HD в «Авто» ([auto]): на 5 Мбит/с восемь живых превью и предзагрузка видео разом
     * забивали канал — картинки ждали по 10 с, открытый ролик висел на «загрузке»; а оценка скорости на таком канале
     * скачет до 6–7 Мбит/с. Пока скорость не замерена, считаем сеть медленной ([INITIAL_BITRATE] ниже порога).
     */
    const val SLOW_BPS = 9_000_000L
    val slow get() = sdOnly || meter.bitrateEstimate < SLOW_BPS
}
