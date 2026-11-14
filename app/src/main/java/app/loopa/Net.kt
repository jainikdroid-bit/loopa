package app.loopa

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.http.HttpResponseCache
import android.os.Handler
import android.os.Looper
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Ответ сервера с кодом ошибки: 429 и 5xx — временные (повторяем), остальные — нет. */
class HttpError(val code: Int, msg: String = "HTTP $code") : IOException(msg) {
    val transient get() = code == 429 || code >= 500
    var retryAfterMs = 0L
}

/**
 * Сеть для плохих каналов: повторы с паузой при обрывах, таймаутах и 429/5xx; дисковый HTTP-кэш (превью и ответы, которые
 * сервер разрешает кэшировать, — второй раз без сети); оповещение «связь вернулась» — лента и плеер сразу повторяют.
 */
object Net {
    const val CONNECT_MS = 15_000      // высокий пинг: соединение через спутник/роуминг может идти секунды
    const val READ_MS = 30_000
    private const val ATTEMPTS = 4
    private val ui = Handler(Looper.getMainLooper())
    /** Слушатели «появилась сеть» — вызываются на главном потоке. */
    val online = LinkedHashSet<() -> Unit>()
    private var inited = false

    fun init(ctx: Context) {
        if (inited) return
        inited = true
        val app = ctx.applicationContext
        runCatching { HttpResponseCache.install(app.cacheDir.resolve("http"), 64L shl 20) }
        initImg(app)
        val cm = app.getSystemService(ConnectivityManager::class.java)
        runCatching {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(n: Network) { ui.post { online.toList().forEach { it() } } }
            })
        }
    }

    /** Ошибка, которую стоит повторить: обрыв, таймаут, 429, 5xx. */
    fun transient(e: Throwable) = e is HttpError && e.transient || e is IOException && e !is HttpError

    /** Выполнить с повторами: пауза 1, 2, 4 с (или сколько просит сервер в Retry-After, но не больше 10 с). */
    fun <T> retry(block: () -> T): T {
        var wait = 1000L
        repeat(ATTEMPTS - 1) {
            try { return block() } catch (e: Exception) {
                if (!transient(e)) throw e
                Thread.sleep((e as? HttpError)?.retryAfterMs?.takeIf { it > 0 }?.coerceAtMost(10_000) ?: wait)
                wait *= 2
            }
        }
        return block()
    }

    /** GET/запрос с общими таймаутами; ошибка HTTP — [HttpError]. */
    fun open(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = CONNECT_MS; readTimeout = READ_MS
        setRequestProperty("User-Agent", Api.UA)
    }

    fun fail(c: HttpURLConnection): HttpError = HttpError(c.responseCode).apply {
        retryAfterMs = (c.getHeaderField("Retry-After")?.toLongOrNull() ?: 0) * 1000
    }

    // ---- один клиент OkHttp для видео и картинок: канал с большим пингом (VPN, спутник, ~800 мс) ----
    // Каждое новое соединение — TCP + TLS ≈ 2–3 RTT ≈ 2 с, поэтому соединения живут и переиспользуются (пул, HTTP/2 —
    // много запросов по одному соединению), DNS запоминается, переадресации запоминаются, к серверам видео и картинок
    // соединение открывается заранее ([warm]).

    /** Тайм-ауты видео и картинок: на пинге 800 мс короткие только рвут почти скачанное и начинают заново. */
    const val MEDIA_CONNECT_MS = 15_000L
    const val MEDIA_READ_MS = 20_000L

    /** DNS с памятью на 10 минут: повторный поиск адреса — ещё один круг по долгому каналу. */
    private object CachedDns : okhttp3.Dns {
        private const val TTL = 10 * 60_000L
        private val memo = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, List<java.net.InetAddress>>>()
        override fun lookup(hostname: String): List<java.net.InetAddress> {
            memo[hostname]?.takeIf { System.currentTimeMillis() - it.first < TTL }?.let { return it.second }
            return okhttp3.Dns.SYSTEM.lookup(hostname).also { memo[hostname] = System.currentTimeMillis() to it }
        }
    }

    /**
     * Переадресации запоминаются на 30 минут: ссылка Eporner /dload/… ведёт на CDN, и без памяти каждый запрос куска
     * видео шёл бы через лишний круг. Запомненный адрес ответил ошибкой (ссылка CDN истекла) — забыть и пойти по исходному.
     */
    /**
     * CDN, которые без Referer своего сайта отвечают 403 (Erome, FikFap на b-cdn.net): подставить его во все запросы —
     * видео, сегменты HLS, превью. Сегменты плейлиста иначе ушли бы без него: их адреса плеер берёт из плейлиста сам.
     */
    private object SiteReferer : okhttp3.Interceptor {
        override fun intercept(chain: okhttp3.Interceptor.Chain): okhttp3.Response {
            val req = chain.request()
            if (req.header("Referer") != null) return chain.proceed(req)
            val u = req.url.toString()
            val ref = when { Erome.ours(u) -> Erome.REFERER; FikFap.ours(u) -> FikFap.REFERER; else -> null }
            return chain.proceed(if (ref == null) req else req.newBuilder().header("Referer", ref).build())
        }
    }

    private object Redirects : okhttp3.Interceptor {
        private const val TTL = 30 * 60_000L
        private val memo = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, okhttp3.HttpUrl>>()
        override fun intercept(chain: okhttp3.Interceptor.Chain): okhttp3.Response {
            val req = chain.request(); val key = req.url.toString()
            memo[key]?.takeIf { System.currentTimeMillis() - it.first < TTL }?.let { (_, to) ->
                val r = chain.proceed(req.newBuilder().url(to).build())
                if (r.isSuccessful) return r
                r.close(); memo.remove(key)
            }
            val r = chain.proceed(req)
            if (r.priorResponse != null && r.isSuccessful && r.request.url.toString() != key) memo[key] = System.currentTimeMillis() to r.request.url
            return r
        }
    }

    /**
     * Журнал медленных запросов: ответ пришёл позже [SLOW_MS] — в лог «что, сколько ждали, сколько ещё запросов шло
     * одновременно». По нему видно, чем занят канал, когда ролик долго стартует.
     */
    private const val SLOW_MS = 2_000L
    private val running = java.util.concurrent.atomic.AtomicInteger()
    private class SlowLog : okhttp3.EventListener() {
        private var t0 = 0L; private var parallel = 0; private var connect = 0L
        override fun callStart(call: okhttp3.Call) { t0 = System.currentTimeMillis(); parallel = running.incrementAndGet() }
        override fun connectStart(call: okhttp3.Call, inetSocketAddress: java.net.InetSocketAddress, proxy: java.net.Proxy) { connect = System.currentTimeMillis() }
        override fun responseHeadersEnd(call: okhttp3.Call, response: okhttp3.Response) {
            val dt = System.currentTimeMillis() - t0
            if (dt > SLOW_MS) android.util.Log.i("Loopa", "медленный запрос: ${call.request().method} ${call.request().url.host}…${call.request().url.encodedPath.takeLast(32)}" +
                " — ответ через $dt мс${if (connect > 0) " (новое соединение)" else ""}, одновременно запросов: $parallel")
        }
        override fun callEnd(call: okhttp3.Call) { running.decrementAndGet() }
        override fun callFailed(call: okhttp3.Call, ioe: IOException) { running.decrementAndGet() }
    }

    /** Общий клиент: видео (через media3 OkHttpDataSource), запросы прогрева. */
    val http: okhttp3.OkHttpClient by lazy {
        okhttp3.OkHttpClient.Builder()
            .connectionPool(okhttp3.ConnectionPool(16, 5, java.util.concurrent.TimeUnit.MINUTES))
            .dispatcher(okhttp3.Dispatcher().apply { maxRequestsPerHost = 16 })
            .dns(CachedDns)
            .connectTimeout(MEDIA_CONNECT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
            .readTimeout(MEDIA_READ_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(true)
            .addInterceptor(Redirects)
            .addInterceptor(SiteReferer)
            .eventListenerFactory { SlowLog() }
            .build()
    }

    /**
     * Фон — живые превью сетки и видео заранее: свои соединения. Отменённый запрос видео оставляет в соединении «хвост»
     * уже отправленных сервером данных; в общем соединении (HTTP/2) он стоял перед ответом ролику, который открыли.
     */
    val bg: okhttp3.OkHttpClient by lazy {
        // очередь своя, но предел на хост — как у общего: каждое живое превью держит поток видео открытым, и при пределе по
        // умолчанию (5) шестое-восьмое превью с того же CDN стояли в очереди — картинка в плитке замирала
        http.newBuilder().connectionPool(okhttp3.ConnectionPool(2, 2, java.util.concurrent.TimeUnit.MINUTES))
            .dispatcher(okhttp3.Dispatcher().apply { maxRequestsPerHost = 16 }).build()
    }

    /** Открыли ролик — фон оборвать совсем: запросы отменить, соединения закрыть (хвосты пропадают вместе с ними). */
    fun dropBackground() {
        // не в главном потоке: закрытие соединения пишет в сеть (TLS close_notify)
        Thread {
            runCatching { bg.dispatcher.cancelAll(); bg.connectionPool.evictAll() }
            // соединение освобождается чуть позже отмены — закрыть и его
            runCatching { Thread.sleep(300); bg.connectionPool.evictAll() }
        }.start()
    }

    /** Картинки: тот же пул соединений + дисковый кэш (превью не меняются — храним, даже если сервер не разрешил). */
    lateinit var img: okhttp3.OkHttpClient; private set

    /** Предел кэша картинок — от выбранного размера кэша видео ([PreloadPolicy.imageCacheBytes]); меняется при следующем запуске. */
    private fun imgCacheBytes(app: Context): Long {
        val choice = app.getSharedPreferences("loopa", Context.MODE_PRIVATE).getInt("video_cache_mb", 0)
        return PreloadPolicy.imageCacheBytes(PreloadPolicy.cacheBytes(choice, app.cacheDir.usableSpace))
    }
    /** Занято кэшем картинок, байт. */
    val imgCacheUsed get() = runCatching { img.cache?.size() ?: 0L }.getOrDefault(0L)

    private fun initImg(app: Context) {
        img = http.newBuilder()
            .cache(okhttp3.Cache(app.cacheDir.resolve("img"), imgCacheBytes(app)))
            .addNetworkInterceptor { ch ->
                ch.proceed(ch.request()).newBuilder().header("Cache-Control", "public, max-age=2592000").removeHeader("Pragma").build()
            }
            .build()
    }

    /** Когда к хосту последний раз открывали соединение заранее. */
    private val warmed = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /**
     * Открыть соединения к серверам этих адресов заранее (HEAD, ответ не нужен): первый ролик и первая картинка не
     * ждут TCP + TLS. Не чаще раза в 4 минуты на хост — пул держит соединение 5 минут.
     */
    fun warm(urls: Collection<String>) {
        val now = System.currentTimeMillis()
        urls.mapNotNull { it.toHttpUrlOrNull() }.distinctBy { it.host }.forEach { u ->
            if (now - (warmed[u.host] ?: 0L) < 4 * 60_000L) return@forEach
            warmed[u.host] = now
            val rq = okhttp3.Request.Builder().url(u).head().header("User-Agent", Api.UA)
                .apply { if (Erome.ours(u.toString())) header("Referer", Erome.REFERER) }.build()
            http.newCall(rq).enqueue(object : okhttp3.Callback {
                override fun onFailure(call: okhttp3.Call, e: IOException) { warmed.remove(u.host) }
                override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) { response.close() }
            })
        }
    }
    private fun String.toHttpUrlOrNull() = runCatching { okhttp3.HttpUrl.Companion.run { this@toHttpUrlOrNull.toHttpUrl() } }.getOrNull()
}
