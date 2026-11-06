package app.loopa

import android.annotation.SuppressLint
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
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/**
 * Токен вошедшего пользователя со страницы redgifs.com в WebView. Берём его так же, как его использует сам сайт: из
 * заголовка Authorization запросов страницы к api.redgifs.com (вход через почту идёт через auth2.redgifs.com, токен
 * живёт в памяти страницы). Кандидата проверяем запросом /v1/me (гостевой токен там не проходит). Запасной путь —
 * localStorage «auth_data» и sessionStorage «auth_token» (так сайт хранил вход раньше). Пароль приложение не видит.
 */
class TokenCatcher(private val web: WebView, private val onToken: (String, Long) -> Unit) {
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

            override fun shouldInterceptRequest(v: WebView, r: WebResourceRequest): WebResourceResponse? {
                if (r.url.host == "api.redgifs.com") {
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

    /** Токен из запроса сайта: проверить один раз — если /v1/me отвечает, это вошедший пользователь. */
    private fun check(t: String) {
        if (done || t.length < 20 || !checked.add(t)) return
        thread {
            val ok = runCatching {
                val c = URL("https://api.redgifs.com/v1/me").openConnection() as HttpURLConnection
                c.connectTimeout = 10_000; c.readTimeout = 10_000
                c.setRequestProperty("Authorization", "Bearer $t"); c.setRequestProperty("User-Agent", Api.UA)
                try { c.responseCode == 200 } finally { c.disconnect() }
            }.getOrDefault(false)
            if (ok) ui.post {
                if (done) return@post
                done = true
                CookieManager.getInstance().flush()   // куки входа — на диск сразу, а не когда-нибудь
                onToken(t, exp(t))
            }
        }
    }

    private fun poll() {
        if (done) return
        web.evaluateJavascript("(function(){try{var s=sessionStorage.getItem('auth_token');if(s)return s;var a=JSON.parse(localStorage.getItem('auth_data')||'null');return a&&a.data&&a.data.access_token||null}catch(e){return null}})()") { raw ->
            runCatching { JSONObject("{\"v\":$raw}").optString("v") }.getOrNull()?.takeIf { it.length > 20 && it != "null" }?.let { check(it) }
        }
        ui.postDelayed({ poll() }, 1000)
    }

    companion object {
        /** Срок из JWT (поле exp), 0 — неизвестно. */
        fun exp(t: String) = runCatching {
            JSONObject(String(Base64.decode(t.split('.')[1], Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP))).optLong("exp")
        }.getOrDefault(0L)
    }
}

/**
 * Продление входа без окна: невидимая страница redgifs.com с сохранёнными куками — сайт сам выдаёт себе свежий токен,
 * мы его подхватываем. Запускается при старте, если токен истёк или истекает в ближайшие часы, и когда API ответил
 * «вход истёк». Не вышло за [TIMEOUT] мс — done(false): тогда нужен обычный вход.
 */
object Renewer {
    private const val TIMEOUT = 25_000L
    private val ui = Handler(Looper.getMainLooper())
    var running = false; private set

    fun run(host: ViewGroup, done: (Boolean) -> Unit) {
        if (running) return
        running = true
        val web = WebView(host.context).apply { alpha = 0f }
        host.addView(web, ViewGroup.LayoutParams(1, 1))
        lateinit var catcher: TokenCatcher
        fun finish(ok: Boolean) {
            if (!running) return
            running = false; catcher.stop(); ui.removeCallbacksAndMessages(null)
            host.removeView(web); web.destroy()
            done(ok)
        }
        catcher = TokenCatcher(web) { t, exp -> Api.setUser(host.context, t, exp); finish(true) }
        catcher.start("https://www.redgifs.com/")
        ui.postDelayed({ finish(false) }, TIMEOUT)
    }
}
