package app.loopa

import android.content.Context
import android.webkit.CookieManager
import java.net.HttpURLConnection

/**
 * Запросы приложения к сайтам из [Sources.ALL] «как у браузера», когда пользователь прошёл на сайте капчу или возрастное окно
 * во встроенном браузере ([SourceCheckActivity]):
 *  - куки браузера ([CookieManager]) добавляются к запросу, если сам запрос их не задал (а заданные запросом — главнее);
 *  - для сайтов, где проверка пройдена ([markVerified]), подставляется User-Agent браузера ([Disguise.ua]): Cloudflare
 *    привязывает cf_clearance к User-Agent, с чужим кука бесполезна.
 * Относится к запросам [Net.open] (страницы) и к общему клиенту OkHttp (видео, картинки того же сайта).
 */
object WebAccess {
    private const val KEY = "verified_domains"
    private const val OPENED = "web_check_opened"
    private var app: Context? = null
    private val verifiedSet = java.util.concurrent.CopyOnWriteArraySet<String>()
    /** Пользователь хоть раз открывал встроенный браузер проверки ([SourceCheckActivity]) — его куки могут пригодиться запросам. */
    @Volatile private var opened = false
    private val browserUa by lazy { app?.let { runCatching { Disguise.ua(it) }.getOrNull() } }
    /** Домены сайтов: хост из [Source.home] без «www.». */
    private val siteDomains by lazy { Sources.ALL.mapNotNull { runCatching { java.net.URI(it.home).host }.getOrNull() }.map(::bare).toSet() }

    fun init(ctx: Context) {
        if (app != null) return
        app = ctx.applicationContext
        verifiedSet.addAll(prefs()?.getStringSet(KEY, emptySet()).orEmpty())
        opened = prefs()?.getBoolean(OPENED, false) ?: false
    }

    /** Открыт встроенный браузер проверки: с этого момента (и навсегда) куки браузера добавляются к запросам сайтов. */
    fun noteOpened() {
        if (opened) return
        opened = true
        prefs()?.edit()?.putBoolean(OPENED, true)?.apply()
    }
    private fun prefs() = app?.getSharedPreferences("loopa", Context.MODE_PRIVATE)

    internal fun bare(host: String) = host.lowercase().removePrefix("www.")
    internal fun inDomain(host: String, domain: String) = host == domain || host.endsWith(".$domain")

    /** Хост — сайт из [Sources.ALL] (или его поддомен: api., cdn., static.). */
    fun isSite(host: String): Boolean { val h = bare(host); return siteDomains.any { inDomain(h, it) } }

    /** На сайте этого хоста пользователь прошёл проверку во встроенном браузере. */
    fun verified(host: String): Boolean { val h = bare(host); return verifiedSet.any { inDomain(h, it) } }

    /** Запомнить сайт [source] как проверенный. */
    fun markVerified(source: Source) {
        val d = runCatching { bare(java.net.URI(source.home).host) }.getOrNull() ?: return
        if (verifiedSet.add(d)) prefs()?.edit()?.putStringSet(KEY, verifiedSet.toSet())?.apply()
    }

    /**
     * Куки браузера для адреса сайта; null — кук нет или трогать браузер рано. CookieManager поднимает WebView (десятки мс
     * и память на первом обращении), поэтому до первой проверки пользователя ([noteOpened]) и на непроверенных хостах
     * его не трогаем: запросы идут как раньше, без кук браузера.
     */
    private fun webCookie(url: String, host: String): String? {
        if (!opened && !verified(host)) return null
        return runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    /** Куки и User-Agent браузера — в запрос страницы ([Net.open]). */
    fun decorate(c: HttpURLConnection) {
        val host = c.url.host
        if (!isSite(host)) return
        webCookie(c.url.toString(), host)?.let { c.setRequestProperty("Cookie", it) }
        if (verified(host)) browserUa?.let { c.setRequestProperty("User-Agent", it) }
    }

    /** Куки к [own] (свои главнее): «a=1; b=2» + «b=9; c=3» -> «b=2; c=3; a=1» по порядку... см. тест. */
    fun mergeCookies(own: String?, web: String?): String? {
        fun parse(s: String?) = s.orEmpty().split(';').map { it.trim() }.filter { it.isNotEmpty() }
            .associate { it.substringBefore('=').trim() to it }
        val o = parse(own); val w = parse(web)
        if (o.isEmpty() && w.isEmpty()) return null
        return (w - o.keys).values.plus(o.values).joinToString("; ")
    }

    /** OkHttp: то же для видео и картинок сайта. */
    val interceptor = okhttp3.Interceptor { chain ->
        val req = chain.request()
        val host = req.url.host
        if (!isSite(host)) return@Interceptor chain.proceed(req)
        val b = req.newBuilder()
        webCookie(req.url.toString(), host)?.let { web -> b.header("Cookie", mergeCookies(req.header("Cookie"), web).orEmpty()) }
        if (verified(host)) browserUa?.let { b.header("User-Agent", it) }
        chain.proceed(b.build())
    }
}
