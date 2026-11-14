package app.loopa

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import org.json.JSONObject
import java.io.ByteArrayInputStream

/**
 * «Граф интересов»: 3D-граф сети вкуса ([InterestGraph]) в WebView. Страница лежит в assets/graph и открывается только
 * через [WebViewAssetLoader] (виртуальный https-адрес): доступа к файлам телефона и в сеть у неё нет. Данные и подписи
 * страница берёт у мостика `Loopa`; «Открыть в ленте» закрывает экран и открывает узел в ленте — тег, автор, сабреддит,
 * поиск или вкладку сайта (extras «tag», «author», «sub», «query», «site» в [MainActivity]).
 */
class GraphActivity : Activity() {
    private lateinit var web: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        I18n.init(this); Interests.init(this)
        val bg = Color.parseColor("#0f0f1a")
        window.statusBarColor = bg; window.navigationBarColor = bg
        val loader = WebViewAssetLoader.Builder().addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this)).build()
        web = WebView(this).apply {
            setBackgroundColor(bg)
            settings.javaScriptEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.blockNetworkLoads = true
            settings.setGeolocationEnabled(false)
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse =
                    // чужие адреса не грузим вообще: пустой ответ
                    if (request.url.host == WebViewAssetLoader.DEFAULT_DOMAIN) loader.shouldInterceptRequest(request.url) ?: blank()
                    else blank()
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
            }
            addJavascriptInterface(Bridge(), "Loopa")
        }
        // отступы от строки состояния и жестов — у рамки вокруг WebView: position:fixed на странице паддинг WebView не учитывает
        val box = android.widget.FrameLayout(this).apply { setBackgroundColor(0xFF0F0F1A.toInt()); addView(web) }
        box.setOnApplyWindowInsetsListener { v, ins ->
            @Suppress("DEPRECATION") v.setPadding(ins.systemWindowInsetLeft, ins.systemWindowInsetTop, ins.systemWindowInsetRight, ins.systemWindowInsetBottom); ins
        }
        setContentView(box)
        web.loadUrl("https://${WebViewAssetLoader.DEFAULT_DOMAIN}/assets/graph/index.html")
    }

    private fun blank() = WebResourceResponse("text/plain", "utf-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(ByteArray(0)))

    /** Мостик для страницы: вызовы приходят из фонового потока WebView. */
    private inner class Bridge {
        @JavascriptInterface fun data(): String = Interests.graphJson()

        /** Ползунок веса узла: сохранить поправку и вернуть граф с новыми весами. id — «тип:термин». */
        @JavascriptInterface fun setBoost(id: String, m: Double): String { Interests.setBoost(id, m); return Interests.graphJson() }

        @JavascriptInterface fun texts(): String = JSONObject()
            .put("search", tr("Поиск по сети…"))
            .put("empty", tr("Граф пуст: лайкайте, досматривайте и ищите — сеть вкуса появится здесь."))
            .put("own", tr("Свой вес"))
            .put("net", tr("Вес в сети"))
            .put("community", tr("Сообщество"))
            .put("neighbours", tr("Соседи"))
            .put("open", tr("Открыть в ленте"))
            .put("noLinks", tr("Нет связей"))
            .put("notFound", tr("Ничего не найдено"))
            .put("suggested", tr("Рядом с любимыми"))
            .put("stats", tr("Узлов: %s, связей: %s", "%s", "%s"))
            .put("error", tr("Не удалось загрузить граф"))
            .put("weight", tr("Мой вес"))
            .put("weightHint", tr("Меньше — реже в «Для вас», больше — чаще"))
            .put("reset", tr("Сбросить"))
            .put("fit", tr("Весь граф"))
            .put("types", JSONObject().put("tag", tr("Теги")).put("user", tr("Авторы")).put("sub", tr("Сабреддиты"))
                .put("kw", tr("Слова")).put("q", tr("Запросы")).put("site", tr("Сайты")))
            .put("type1", JSONObject().put("tag", tr("Тег")).put("user", tr("Автор")).put("sub", tr("Сабреддит"))
                .put("kw", tr("Слово")).put("q", tr("Запрос")).put("site", tr("Сайт")))
            .put("sites", JSONObject().apply { Sources.ALL.forEach { put(it.id, it.title) }; put("redgifs", "RedGIFs"); put("reddit", "Reddit") })
            .put("min", Interests.BOOST_MIN).put("max", Interests.BOOST_MAX)
            .toString()

        /** «Открыть в ленте»: тип узла решает, что открыть — тег, автора, сабреддит, поиск по тексту или вкладку сайта. */
        @JavascriptInterface fun open(id: String) {
            val type = id.substringBefore(':'); val name = id.substringAfter(':')
            val extra = when (type) { "user" -> "author"; "sub" -> "sub"; "q", "kw" -> "query"; "site" -> "site"; else -> "tag" }
            runOnUiThread {
                startActivity(Intent(this@GraphActivity, MainActivity::class.java).putExtra(extra, name)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                finish()
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // сначала страница снимает выбор тега; нечего снимать — закрыть экран
        web.evaluateJavascript("window.backHandled ? window.backHandled() : false") {
            if (it != "true") @Suppress("DEPRECATION") super.onBackPressed()
        }
    }

    override fun onDestroy() {
        web.removeJavascriptInterface("Loopa")
        web.destroy()
        super.onDestroy()
    }
}
