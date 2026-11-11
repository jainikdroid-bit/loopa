package app.loopa

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONArray
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * Невидимый WebView — настоящий браузер с куками сессии. Reddit режет запросы приложения (и даже .json в браузере), а
 * обычные страницы браузеру отдаёт: открываем страницу и выполняем в ней скрипт ([page]) или скрипт с запросом из самой
 * страницы ([run] — для подгрузки следующих порций, как при прокрутке). Один запрос за раз; вызывать из фона.
 */
object WebJson {
    private val ui = Handler(Looper.getMainLooper())
    private var web: WebView? = null
    private val lock = Any()

    @SuppressLint("SetJavaScriptEnabled")
    private fun view(ctx: Context) = web ?: WebView(ctx.applicationContext).also {
        it.settings.javaScriptEnabled = true; it.settings.domStorageEnabled = true
        it.settings.blockNetworkImage = true          // картинки странице не нужны — экономим канал
        Disguise.apply(it); web = it
    }

    /** Открыть страницу и вернуть результат скрипта (строка; JSON — по договорённости со скриптом). */
    /** Открыть страницу; обрыв соединения (бывает на VPN) — ещё до 2 попыток с паузой. */
    fun page(ctx: Context, url: String, js: String, timeoutMs: Long = 20_000, accept: String? = null): String? {
        repeat(2) { n ->
            pageOnce(ctx, url, js, timeoutMs, accept)?.let { return it }
            if (!lastWasError) return null                       // не ошибка, а тайм-аут — повтор не поможет
            android.util.Log.i("Loopa", "WebJson повтор ${n + 1}: $url"); Thread.sleep(1500L * (n + 1))
        }
        return pageOnce(ctx, url, js, timeoutMs, accept)
    }
    @Volatile private var lastWasError = false

    private fun pageOnce(ctx: Context, url: String, js: String, timeoutMs: Long, accept: String? = null): String? = synchronized(lock) {
        lastWasError = false
        val want = url.substringBefore('?').trimEnd('/').lowercase()
        val f = CompletableFuture<String?>()
        ui.post {
            val w = view(ctx)
            w.webViewClient = object : WebViewClient() {
                override fun onPageFinished(v: WebView, u: String) {
                    if (f.isDone) return
                    // запоздавшее окончание прошлой страницы — не наш ответ
                    if (u.substringBefore('?').trimEnd('/').lowercase() != want && (accept == null || !u.lowercase().contains(accept))) { android.util.Log.i("Loopa", "WebJson: не та страница " + u.substringBefore('?')); return }
                    android.util.Log.i("Loopa", "WebJson загружено: " + u.substringBefore('?'))
                    v.evaluateJavascript(js) { raw -> f.complete(str(raw)) }
                }
                // ошибка — только нашей страницы; обрыв прошлой (stopLoading → ERR_ABORTED) не в счёт
                override fun onReceivedError(v: WebView, r: WebResourceRequest, e: WebResourceError) {
                    if (r.isForMainFrame && e.errorCode != ERROR_UNKNOWN && r.url.toString().substringBefore('?').trimEnd('/').lowercase() == want) {
                        android.util.Log.w("Loopa", "WebJson ошибка ${e.errorCode} ${e.description}: $want"); lastWasError = true; f.complete(null)
                    }
                }
            }
            w.stopLoading()
            w.loadUrl(url)
        }
        val t0 = System.currentTimeMillis()
        runCatching { f.get(timeoutMs, TimeUnit.MILLISECONDS) }.getOrNull()
            .also { android.util.Log.i("Loopa", "WebJson ${url.substringBefore('?')}: ${if (it == null) "нет ответа" else "${it.length} байт"} за ${System.currentTimeMillis() - t0} мс") }
    }

    /** Асинхронный скрипт в уже открытой странице: он кладёт ответ в window.__lp; ждём его. */
    fun run(ctx: Context, js: String, timeoutMs: Long = 30_000): String? = synchronized(lock) {
        val f = CompletableFuture<String?>()
        val end = System.currentTimeMillis() + timeoutMs
        ui.post {
            val w = view(ctx)
            w.evaluateJavascript("window.__lp=null;$js") {}
            fun poll() {
                w.evaluateJavascript("window.__lp") { raw ->
                    val s = str(raw)
                    when {
                        s != null -> f.complete(s)
                        System.currentTimeMillis() > end -> f.complete(null)
                        else -> ui.postDelayed({ poll() }, 300)
                    }
                }
            }
            ui.postDelayed({ poll() }, 300)
        }
        runCatching { f.get(timeoutMs + 2000, TimeUnit.MILLISECONDS) }.getOrNull()
    }

    /** Ответ evaluateJavascript — JSON-литерал строки (или null). */
    private fun str(raw: String?) = raw?.takeIf { it != "null" }?.let { runCatching { JSONArray("[$it]").getString(0) }.getOrNull() }
}
