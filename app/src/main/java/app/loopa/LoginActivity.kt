package app.loopa

import android.app.Activity
import android.content.ClipboardManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Message
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import kotlin.math.roundToInt

/**
 * Вход на сайт (RedGIFs или Reddit — extra «site»): страница сайта в WebView, пользователь входит сам, пароль приложение
 * не видит; токен ловит [TokenCatcher]. Куки входа остаются в WebView — по ним [Renewer] потом продлевает вход без окна.
 * Reddit: окно входа под видом Chrome ([Disguise]) и всплывающие окна (вход через Google открывается отдельным окном) —
 * поверх страницы, в этом же WebView-процессе, чтобы сессия досталась приложению. Запасной путь — одноразовая ссылка из
 * письма: открываем её здесь же.
 */
class LoginActivity : Activity() {
    private lateinit var web: WebView
    private lateinit var catcher: TokenCatcher
    private lateinit var state: TextView
    private lateinit var box: FrameLayout
    /** Всплывающее окно страницы (вход через Google) — поверх основной. */
    private var popup: WebView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        I18n.init(this)
        val site = if (intent.getStringExtra("site") == "reddit") Reddit.SITE else RgSite
        val reddit = site === Reddit.SITE
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(MainActivity.BG) }
        root.setOnApplyWindowInsetsListener { v, ins ->
            @Suppress("DEPRECATION") v.setPadding(ins.systemWindowInsetLeft, ins.systemWindowInsetTop, ins.systemWindowInsetRight, ins.systemWindowInsetBottom); ins
        }
        root.addView(TextView(this).apply {
            text = if (reddit) tr("Войдите в Reddit — можно через Google. Не пускает — одноразовая ссылка: «Email me a one-time link», скопируйте ссылку из письма и нажмите кнопку ниже. Чтобы видеть 18+, включите его в настройках аккаунта.")
                else tr("Войдите в RedGIFs (почта или Reddit). Окно закроется само после входа.")
            setTextColor(Color.WHITE); textSize = 14f; setPadding(dp(14), dp(10), dp(14), dp(6))
        })
        if (reddit) root.addView(TextView(this).apply {
            text = tr("📋 Вставить ссылку из письма"); setTextColor(Color.WHITE); textSize = 15f; setPadding(dp(16), dp(9), dp(16), dp(9))
            background = GradientDrawable().apply { setColor(MainActivity.ACCENT); cornerRadius = dp(20).toFloat() }
            setOnClickListener { pasteLink() }
        }, LinearLayout.LayoutParams(-2, -2).apply { setMargins(dp(14), dp(2), dp(14), dp(8)) })
        state = TextView(this).apply { setTextColor(0xFFB0B5BD.toInt()); textSize = 12f; setPadding(dp(14), 0, dp(14), dp(4)) }
        root.addView(state)
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        root.addView(bar, LinearLayout.LayoutParams(-1, dp(3)))
        web = WebView(this)
        box = FrameLayout(this).apply { addView(web, FrameLayout.LayoutParams(-1, -1)) }
        root.addView(box, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        if (reddit) {
            Disguise.apply(web)
            web.settings.setSupportMultipleWindows(true); web.settings.javaScriptCanOpenWindowsAutomatically = true
        }
        catcher = TokenCatcher(web, site, { url, err -> state.text = if (err != null) tr("Не открылось: %s — проверьте VPN/сеть и нажмите здесь, чтобы повторить", err) else Uri.parse(url).host.orEmpty() }) { t, exp ->
            Thread { site.save(this, t, exp); runOnUiThread {
                Toast.makeText(this, tr("Вход в %s выполнен", site.title), Toast.LENGTH_SHORT).show()
                setResult(RESULT_OK); finish()
            } }.start()
        }
        catcher.start(site.login)
        // прогресс загрузки — чтобы долгая страница не выглядела белым пустым экраном
        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(v: WebView, p: Int) { bar.progress = p; bar.visibility = if (p < 100) View.VISIBLE else View.INVISIBLE }
            override fun onCreateWindow(v: WebView, dialog: Boolean, user: Boolean, msg: Message): Boolean {
                val p = popupView()
                (msg.obj as WebView.WebViewTransport).webView = p; msg.sendToTarget(); return true
            }
            override fun onCloseWindow(w: WebView) { closePopup() }
        }
        state.setOnClickListener { web.reload() }
    }

    @android.annotation.SuppressLint("SetJavaScriptEnabled")
    private fun popupView(): WebView {
        closePopup()
        val p = WebView(this).apply {
            settings.javaScriptEnabled = true; settings.domStorageEnabled = true
            settings.setSupportMultipleWindows(true); settings.javaScriptCanOpenWindowsAutomatically = true
            Disguise.apply(this)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest) = !(r.url.scheme == "https" || r.url.scheme == "http")
            }
            webChromeClient = object : WebChromeClient() { override fun onCloseWindow(w: WebView) { closePopup() } }
        }
        box.addView(p, FrameLayout.LayoutParams(-1, -1)); popup = p
        return p
    }

    private fun closePopup() { popup?.let { box.removeView(it); it.destroy() }; popup = null }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            popup != null -> closePopup()
            web.canGoBack() -> web.goBack()
            else -> @Suppress("DEPRECATION") super.onBackPressed()
        }
    }

    /** Одноразовая ссылка Reddit из буфера обмена — открыть здесь же. */
    private fun pasteLink() {
        val t = (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()?.trim().orEmpty()
        val link = Regex("https://\\S+").find(t)?.value
        val host = link?.let { Uri.parse(it).host.orEmpty() }.orEmpty()
        if (link == null || !(host == "reddit.com" || host.endsWith(".reddit.com") || host.endsWith(".redditmail.com"))) {
            Toast.makeText(this, tr("В буфере нет ссылки Reddit — скопируйте ссылку из письма целиком"), Toast.LENGTH_LONG).show(); return
        }
        web.loadUrl(link)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()

    override fun onDestroy() { catcher.stop(); closePopup(); web.destroy(); super.onDestroy() }
}
