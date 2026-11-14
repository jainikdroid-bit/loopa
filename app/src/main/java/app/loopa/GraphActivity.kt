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
 * страница берёт у мостика `Loopa`; «Открыть в ленте» закрывает экран и открывает тег в ленте — так же, как касание
 * тега в плеере (extra «tag» в [MainActivity]).
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

        /** Ползунок веса тега: сохранить поправку и вернуть граф с новыми весами. */
        @JavascriptInterface fun setBoost(tag: String, m: Double): String { Interests.setBoost(tag, m); return Interests.graphJson() }

        @JavascriptInterface fun texts(): String = JSONObject()
            .put("search", tr("Поиск тега…"))
            .put("empty", tr("Граф пуст: лайкайте, досматривайте и ищите — связи между тегами появятся здесь."))
            .put("own", tr("Свой вес"))
            .put("net", tr("Вес в сети"))
            .put("community", tr("Сообщество"))
            .put("neighbours", tr("Соседи"))
            .put("open", tr("Открыть в ленте"))
            .put("noLinks", tr("Нет связей"))
            .put("notFound", tr("Тег не найден"))
            .put("suggested", tr("Рядом с любимыми"))
            .put("stats", tr("Тегов: %s, связей: %s", "%s", "%s"))
            .put("error", tr("Не удалось загрузить граф"))
            .put("weight", tr("Мой вес тега"))
            .put("weightHint", tr("Меньше — реже в «Для вас», больше — чаще"))
            .put("reset", tr("Сбросить"))
            .put("fit", tr("Весь граф"))
            .put("min", Interests.BOOST_MIN).put("max", Interests.BOOST_MAX)
            .toString()

        @JavascriptInterface fun open(tag: String) {
            runOnUiThread {
                startActivity(Intent(this@GraphActivity, MainActivity::class.java).putExtra("tag", tag)
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
