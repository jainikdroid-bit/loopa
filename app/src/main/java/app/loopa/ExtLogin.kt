package app.loopa

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import kotlin.math.roundToInt

/**
 * Вход в Erome и Eporner: у них нет токенов API — вход это куки сайта в WebView. Пользователь входит сам на странице сайта
 * (пароль приложение не видит); вошёл — на странице появилась ссылка «выйти» (logout). Тогда запоминаем вход и ссылки
 * личных лент со страницы (подписки, избранное — по словам follow/favorite в адресе): их берёт «Для вас» ([ForYou]).
 * Запросы к Erome идут с куками входа ([cookie]). Выход — стереть куки сайта.
 */
object Ext {
    class Site(val id: String, val title: String, val home: String, val login: String, val domain: String, val feedWords: List<String>) {
        /** Куда окну входа можно переходить: сам сайт и вход через Google/Reddit/X. Остальное — реклама (07.11 на Erome
         *  баннер уводил страницу на http://tsyndicate.com/… — «Не удалось открыть веб-страницу»). */
        fun allowed(host: String) = host == domain.trimStart('.') || host.endsWith(domain) || LOGIN_HOSTS.any { host == it || host.endsWith(".$it") }
    }
    private val LOGIN_HOSTS = listOf("google.com", "gstatic.com", "googleusercontent.com", "reddit.com", "redditstatic.com", "twitter.com", "x.com", "twimg.com")
    val EROME = Site("erome", "Erome", Erome.SITE + "/", Erome.SITE + "/user/login", ".erome.com", listOf("follow"))
    val EPORNER = Site("eporner", "Eporner", Eporner.SITE + "/", Eporner.SITE + "/login/", ".eporner.com", listOf("favorite", "favourite", "subscri"))
    val ALL = listOf(EROME, EPORNER)
    fun site(id: String?) = ALL.firstOrNull { it.id == id }

    private var prefs: SharedPreferences? = null
    fun init(ctx: Context) { if (prefs == null) prefs = ctx.applicationContext.getSharedPreferences("loopa", Context.MODE_PRIVATE) }

    fun loggedIn(s: Site) = prefs?.getBoolean("login_${s.id}", false) ?: false
    /** Личные ленты сайта (адреса страниц), найденные при входе. */
    fun feeds(s: Site): List<String> = runCatching { JSONArray(prefs?.getString("feeds_${s.id}", "[]")).let { a -> (0 until a.length()).map { a.getString(it) } } }.getOrDefault(emptyList())

    fun saved(s: Site, feeds: List<String>) { prefs?.edit()?.putBoolean("login_${s.id}", true)?.putString("feeds_${s.id}", JSONArray(feeds).toString())?.apply() }

    fun logout(s: Site) {
        prefs?.edit()?.remove("login_${s.id}")?.remove("feeds_${s.id}")?.apply()
        TokenCatcher.clearCookies(s.home, s.domain)
    }

    /** Куки сайта для запроса страницы (пусто — не входили). */
    fun cookie(url: String): String? = runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull()?.takeIf { it.isNotBlank() }
}

/** Окно входа в Erome или Eporner (extra «site»: erome | eporner). */
class ExtLoginActivity : Activity() {
    private lateinit var web: WebView
    private lateinit var state: TextView
    private lateinit var site: Ext.Site
    private var done = false
    private lateinit var box: android.widget.FrameLayout
    /** Всплывающее окно (вход через Google открывается отдельным окном) — поверх страницы, в том же WebView-процессе. */
    private var popup: WebView? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        I18n.init(this); Theme.init(this); Ext.init(this)
        site = Ext.site(intent.getStringExtra("site")) ?: run { finish(); return }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Theme.bg) }
        root.setOnApplyWindowInsetsListener { v, ins ->
            @Suppress("DEPRECATION") v.setPadding(ins.systemWindowInsetLeft, ins.systemWindowInsetTop, ins.systemWindowInsetRight, ins.systemWindowInsetBottom); ins
        }
        root.addView(TextView(this).apply {
            text = tr("Войдите в %s. Окно закроется само после входа.", site.title)
            setTextColor(Color.WHITE); textSize = 14f; setPadding(dp(14), dp(10), dp(14), dp(6))
        })
        root.addView(TextView(this).apply {
            text = tr("✓ Я вошёл"); setTextColor(Color.WHITE); textSize = 15f; setPadding(dp(16), dp(9), dp(16), dp(9))
            background = GradientDrawable().apply { setColor(Theme.accent); cornerRadius = dp(20).toFloat() }
            setOnClickListener { check(manual = true) }
        }, LinearLayout.LayoutParams(-2, -2).apply { setMargins(dp(14), dp(2), dp(14), dp(8)) })
        state = TextView(this).apply { setTextColor(0xFFB0B5BD.toInt()); textSize = 12f; setPadding(dp(14), 0, dp(14), dp(4)) }
        root.addView(state)
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; progressTintList = Theme.tint() }
        root.addView(bar, LinearLayout.LayoutParams(-1, dp(3)))
        web = WebView(this).apply {
            settings.javaScriptEnabled = true; settings.domStorageEnabled = true
            settings.setSupportMultipleWindows(true); settings.javaScriptCanOpenWindowsAutomatically = true
            Disguise.apply(this)
            CookieManager.getInstance().setAcceptCookie(true); CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(v: WebView, r: android.webkit.WebResourceRequest) = blocked(r.url)
                override fun onPageFinished(v: WebView, url: String) { state.text = android.net.Uri.parse(url).host.orEmpty(); check(manual = false) }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(v: WebView, p: Int) { bar.progress = p; bar.visibility = if (p < 100) View.VISIBLE else View.INVISIBLE }
                override fun onCreateWindow(v: WebView, dialog: Boolean, user: Boolean, msg: android.os.Message): Boolean {
                    // окно без жеста пользователя — реклама; по нажатию (вход через Google) — поверх страницы
                    if (!user) return false
                    (msg.obj as WebView.WebViewTransport).webView = popupView(); msg.sendToTarget(); return true
                }
                override fun onCloseWindow(w: WebView) { closePopup() }
            }
        }
        box = android.widget.FrameLayout(this).apply { addView(web, android.widget.FrameLayout.LayoutParams(-1, -1)) }
        root.addView(box, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        web.loadUrl(site.login)
    }

    /** Вошёл ли: на странице есть ссылка выхода. Заодно — ссылки личных лент (подписки, избранное). */
    private fun check(manual: Boolean) {
        if (done) return
        val words = JSONArray(site.feedWords).toString()
        val js = "(function(){var a=[].slice.call(document.querySelectorAll('a[href]')).map(function(x){return x.href});" +
            "var out=a.some(function(h){return /logout|log-out|signout/i.test(h)});var w=$words;" +
            "var f=a.filter(function(h){return h.indexOf(location.host)>=0&&w.some(function(k){return h.toLowerCase().indexOf(k)>=0})});" +
            "return JSON.stringify({in:out,feeds:f.filter(function(h,i){return f.indexOf(h)==i}).slice(0,4)})})()"
        web.evaluateJavascript(js) { raw ->
            val r = runCatching { org.json.JSONObject(org.json.JSONTokener(raw).nextValue() as String) }.getOrNull()
            if (r?.optBoolean("in") == true) {
                done = true
                val f = r.optJSONArray("feeds")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
                Ext.saved(site, f)
                CookieManager.getInstance().flush()
                Toast.makeText(this, tr("Вход в %s выполнен", site.title), Toast.LENGTH_SHORT).show()
                setResult(RESULT_OK); finish()
            } else if (manual) Toast.makeText(this, tr("Вход ещё не виден — войдите на странице сайта"), Toast.LENGTH_LONG).show()
        }
    }

    /** Переход не на сайт и не на страницу входа (реклама) или не http(s) — не открывать. */
    private fun blocked(u: android.net.Uri) = !(u.scheme == "https" || u.scheme == "http") || !site.allowed(u.host.orEmpty())

    @SuppressLint("SetJavaScriptEnabled")
    private fun popupView(): WebView {
        closePopup()
        val p = WebView(this).apply {
            settings.javaScriptEnabled = true; settings.domStorageEnabled = true
            settings.setSupportMultipleWindows(true); settings.javaScriptCanOpenWindowsAutomatically = true
            Disguise.apply(this)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(v: WebView, r: android.webkit.WebResourceRequest) = blocked(r.url)
                // вход завершился и вернул на сайт — окно больше не нужно, проверяем главную страницу
                override fun onPageFinished(v: WebView, url: String) {
                    if (site.allowed(android.net.Uri.parse(url).host.orEmpty()) && android.net.Uri.parse(url).host.orEmpty().endsWith(site.domain.trimStart('.'))) { closePopup(); web.reload() }
                }
            }
            webChromeClient = object : WebChromeClient() { override fun onCloseWindow(w: WebView) { closePopup(); web.reload() } }
        }
        box.addView(p, android.widget.FrameLayout.LayoutParams(-1, -1)); popup = p
        return p
    }

    private fun closePopup() { popup?.let { box.removeView(it); it.destroy() }; popup = null }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when { popup != null -> closePopup(); web.canGoBack() -> web.goBack(); else -> @Suppress("DEPRECATION") super.onBackPressed() }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()
    override fun onDestroy() { closePopup(); if (::web.isInitialized) web.destroy(); super.onDestroy() }
}
