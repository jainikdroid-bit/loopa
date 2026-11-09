package app.loopa

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/**
 * Сайт со входом: где входить, откуда ловить токен (заголовок Authorization запросов страницы к [hosts], кука
 * [cookie], скрипт [pollJs] по хранилищу страницы), как проверить, что токен — вошедшего пользователя, и куда сохранить.
 */
abstract class Site(val title: String, val login: String, val home: String) {
    open val hosts: Set<String> = emptySet()
    /** Кука с токеном: адрес сайта и имя. */
    open val cookie: Pair<String, String>? = null
    open val pollJs: String? = null
    /** Токен вошедшего пользователя (гостевой не годится); вызывается в фоне. */
    abstract fun verify(t: String): Boolean
    abstract fun save(ctx: Context, t: String, exp: Long)
}

/**
 * RedGIFs: токен из заголовка Authorization запросов страницы к api.redgifs.com (вход через почту идёт через
 * auth2.redgifs.com, токен живёт в памяти страницы), проверка — /v1/me (гостевой там не проходит). Запасной путь —
 * localStorage «auth_data» и sessionStorage «auth_token» (так сайт хранил вход раньше).
 */
object RgSite : Site("RedGIFs", "https://www.redgifs.com/login", "https://www.redgifs.com/") {
    override val hosts = setOf("api.redgifs.com")
    override val pollJs = "(function(){try{var s=sessionStorage.getItem('auth_token');if(s)return s;var a=JSON.parse(localStorage.getItem('auth_data')||'null');return a&&a.data&&a.data.access_token||null}catch(e){return null}})()"
    override fun verify(t: String) = runCatching {
        val c = Net.open("https://api.redgifs.com/v1/me")
        c.setRequestProperty("Authorization", "Bearer $t")
        try { c.responseCode == 200 } finally { c.disconnect() }
    }.getOrDefault(false)
    override fun save(ctx: Context, t: String, exp: Long) = Api.setUser(ctx, t, exp)
}

/**
 * Окно входа под видом обычного Chrome: без пометки «wv» в User-Agent и без заголовка X-Requested-With с именем
 * приложения. По ним Google узнаёт встроенное окно и не пускает войти («Continue with Google» на Reddit висел).
 * Только для окна входа — ленты и API ходят с честным [Api.UA].
 */
object Disguise {
    // REQUESTED_WITH_HEADER_ALLOW_LIST в androidx.webkit помечен @RestrictTo (экспериментальный), но это публичный
    // флаг, и вызов защищён isFeatureSupported — lint здесь ошибается
    @SuppressLint("RestrictedApi")
    fun apply(w: WebView) {
        w.settings.userAgentString = android.webkit.WebSettings.getDefaultUserAgent(w.context)
            .replace("; wv", "").replace(Regex(" Version/\\d+(\\.\\d+)*"), "")
        if (androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST))
            androidx.webkit.WebSettingsCompat.setRequestedWithHeaderOriginAllowList(w.settings, emptySet())
    }
}

/** Ловит токен вошедшего пользователя со страницы сайта в WebView. Пароль приложение не видит. */
class TokenCatcher(
    private val web: WebView, private val site: Site,
    /** Страница: (адрес, ошибка загрузки или null) — для подписи в окне входа. */
    private val onPage: ((String, String?) -> Unit)? = null,
    private val onToken: (String, Long) -> Unit,
) {
    private val ui = Handler(Looper.getMainLooper())
    private val checked = ConcurrentHashMap.newKeySet<String>()
    @Volatile var done = false; private set

    @SuppressLint("SetJavaScriptEnabled")
    fun start(url: String) {
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        web.webViewClient = object : WebViewClient() {
            // приложения Reddit/Google не открываем (intent://, reddit://) — вход идёт здесь же
            override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest) = !(r.url.scheme == "https" || r.url.scheme == "http")

            override fun onPageStarted(v: WebView, url: String, f: android.graphics.Bitmap?) { onPage?.invoke(url, null) }
            override fun onReceivedError(v: WebView, r: WebResourceRequest, e: android.webkit.WebResourceError) {
                if (r.isForMainFrame) onPage?.invoke(r.url.toString(), e.description.toString())
            }
            override fun onReceivedHttpError(v: WebView, r: WebResourceRequest, e: WebResourceResponse) {
                if (r.isForMainFrame && e.statusCode >= 400) onPage?.invoke(r.url.toString(), "HTTP ${e.statusCode}")
            }

            override fun shouldInterceptRequest(v: WebView, r: WebResourceRequest): WebResourceResponse? {
                if (r.url.host in site.hosts) {
                    val a = r.requestHeaders.entries.firstOrNull { it.key.equals("Authorization", true) }?.value
                    if (a != null && a.startsWith("Bearer ", true)) check(a.substring(7).trim())
                }
                return null   // запрос идёт как обычно
            }
        }
        web.loadUrl(url)
        poll()
    }

    fun stop() { done = true; ui.removeCallbacksAndMessages(null) }

    /** Кандидата проверить один раз; подошёл — сохранить куки на диск сразу и отдать токен. */
    private fun check(t: String) {
        if (done || t.length < 20 || !checked.add(t)) return
        thread {
            if (site.verify(t)) ui.post {
                if (done) return@post
                done = true
                CookieManager.getInstance().flush()
                onToken(t, exp(t))
            }
        }
    }

    /** Раз в секунду: кука с токеном и хранилище страницы. */
    private fun poll() {
        if (done) return
        site.cookie?.let { (url, name) -> cookie(url, name)?.let { check(it) } }
        site.pollJs?.let { js ->
            web.evaluateJavascript(js) { raw ->
                runCatching { JSONObject("{\"v\":$raw}").optString("v") }.getOrNull()?.takeIf { it.length > 20 && it != "null" }?.let { check(it) }
            }
        }
        ui.postDelayed({ poll() }, 1000)
    }

    companion object {
        /** Срок из JWT (поле exp), 0 — неизвестно. */
        fun exp(t: String) = runCatching {
            JSONObject(String(Base64.decode(t.split('.')[1], Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP))).optLong("exp")
        }.getOrDefault(0L)

        fun cookie(url: String, name: String) = CookieManager.getInstance().getCookie(url)
            ?.split(";")?.map { it.trim() }?.firstOrNull { it.startsWith("$name=") }?.substringAfter("=")
            ?.let { java.net.URLDecoder.decode(it, "UTF-8") }?.takeIf { it.isNotBlank() }

        /** Выйти только с одного сайта: стереть его куки, не трогая остальные. */
        fun clearCookies(url: String, domain: String) {
            val cm = CookieManager.getInstance()
            cm.getCookie(url)?.split(";")?.map { it.trim().substringBefore("=") }?.filter { it.isNotEmpty() }?.forEach { n ->
                cm.setCookie(url, "$n=; Max-Age=0; Path=/")
                cm.setCookie(url, "$n=; Max-Age=0; Path=/; Domain=$domain")
            }
            cm.flush()
        }
    }
}

/**
 * Продление входа без окна: невидимая страница сайта с сохранёнными куками — сайт сам выдаёт себе свежий токен, мы его
 * подхватываем. Запускается при старте, если токен истёк или истекает в ближайшие часы, и когда API ответил «вход
 * истёк». Не вышло за [TIMEOUT] мс — done(false): тогда нужен обычный вход. Один сайт за раз.
 */
object Renewer {
    private const val TIMEOUT = 25_000L
    private val ui = Handler(Looper.getMainLooper())
    var running = false; private set

    fun run(host: ViewGroup, site: Site, done: (Boolean) -> Unit) {
        if (running) return
        running = true
        // Reddit — тем же «браузером», что и при входе: сессия не видит смены клиента
        val web = WebView(host.context).apply { alpha = 0f; if (site === Reddit.SITE) Disguise.apply(this) }
        host.addView(web, ViewGroup.LayoutParams(1, 1))
        lateinit var catcher: TokenCatcher
        fun finish(ok: Boolean) {
            if (!running) return
            running = false; catcher.stop(); ui.removeCallbacksAndMessages(null)
            host.removeView(web); web.destroy()
            done(ok)
        }
        // save может ходить в сеть (Reddit узнаёт имя) — не на главном потоке
        catcher = TokenCatcher(web, site) { t, exp -> thread { site.save(host.context, t, exp); ui.post { finish(true) } } }
        catcher.start(site.home)
        ui.postDelayed({ finish(false) }, TIMEOUT)
    }
}
