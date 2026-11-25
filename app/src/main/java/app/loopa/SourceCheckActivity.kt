package app.loopa

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlin.concurrent.thread
import kotlin.math.roundToInt

/**
 * «Источники требуют внимания»: сайты, которые отдали капчу, возрастную заглушку или закрыли доступ ([SourceHealth]).
 * Список — только источники в NEEDS_ACTION (кнопкой можно показать все). «Открыть» — встроенный браузер на главной странице
 * сайта: пользователь проходит капчу и диалоговые окна сам (возрастные окна [AgeGate] нажимает сам). «Готово» — сайт
 * запоминается как проверенный ([WebAccess]: с этого момента запросы приложения идут с куками и User-Agent браузера),
 * источник проверяется заново, состояние обновляется.
 */
class SourceCheckActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var root: LinearLayout
    private lateinit var list: LinearLayout
    private lateinit var toggleAll: TextView
    private var showAll = false
    /** Открытый в браузере источник; null — виден список. */
    private var browsing: Source? = null
    private var web: WebView? = null
    private val onHealth: () -> Unit = { if (browsing == null) render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        I18n.init(this); Theme.init(this); Net.init(this); Stats.init(application); ForYou.init(this)
        Theme.bars(this)
        // из Настроек — сразу все источники с состоянием; из «!» — только требующие внимания
        showAll = savedInstanceState?.getBoolean("all") ?: intent.getBooleanExtra("all", false)
        root = makeRoot()
        setContentView(root)
        buildList()
        SourceHealth.listeners += onHealth
    }

    private fun makeRoot() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setBackgroundColor(Theme.bg)
        setOnApplyWindowInsetsListener { v, ins ->
            @Suppress("DEPRECATION") v.setPadding(ins.systemWindowInsetLeft, ins.systemWindowInsetTop, ins.systemWindowInsetRight, ins.systemWindowInsetBottom); ins
        }
    }

    override fun onSaveInstanceState(out: Bundle) { super.onSaveInstanceState(out); out.putBoolean("all", showAll) }

    // ---- список ----

    private fun buildList() {
        root.removeAllViews()
        val bar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), dp(8), dp(16), dp(8)) }
        bar.addView(TextView(this).apply {
            text = "‹"; setTextColor(Theme.accent); textSize = 30f; gravity = Gravity.CENTER; setPadding(dp(12), 0, dp(12), dp(4))
            setOnClickListener { finish() }
        })
        bar.addView(TextView(this).apply { text = tr("Источники требуют внимания"); setTextColor(Color.WHITE); textSize = 18f; typeface = android.graphics.Typeface.DEFAULT_BOLD })
        root.addView(bar)
        root.addView(TextView(this).apply {
            text = tr("Некоторые сайты просят пройти проверку. Откройте сайт, пройдите капчу и диалоговые окна — источник снова появится в выдаче.")
            setTextColor(0xFFB0B5BD.toInt()); textSize = 14f; setPadding(dp(16), dp(2), dp(16), dp(10))
        })
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, dp(12), dp(8)) }
        root.addView(ScrollView(this).apply { addView(list) }, LinearLayout.LayoutParams(-1, 0, 1f))
        toggleAll = TextView(this).apply {
            textSize = 14f; setTextColor(Theme.soft); gravity = Gravity.CENTER; setPadding(dp(16), dp(12), dp(16), dp(12))
            setOnClickListener { showAll = !showAll; render() }
        }
        root.addView(toggleAll, LinearLayout.LayoutParams(-1, -2))
        render()
    }

    private fun render() {
        if (!::list.isInitialized) return
        list.removeAllViews()
        val rows = Sources.ALL.filter { if (showAll) true else SourceHealth.status(it.id).state == SourceState.NEEDS_ACTION }
        if (rows.isEmpty()) list.addView(TextView(this).apply {
            text = tr("Все источники отвечают, проверка не нужна."); setTextColor(0xFFB0B5BD.toInt()); textSize = 15f; setPadding(dp(4), dp(12), dp(4), dp(12))
        })
        rows.forEach { list.addView(row(it), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) }) }
        toggleAll.text = if (showAll) tr("Показать только требующие внимания") else tr("Показать все источники")
    }

    private fun row(s: Source): View {
        val st = SourceHealth.status(s.id)
        val row = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14), dp(10), dp(10), dp(10))
            background = GradientDrawable().apply { setColor(Theme.field); cornerRadius = dp(14).toFloat() }
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(TextView(this).apply { text = s.title; setTextColor(Color.WHITE); textSize = 16f })
        col.addView(TextView(this).apply {
            text = stateText(st); textSize = 13f
            setTextColor(if (st.state == SourceState.NEEDS_ACTION) Theme.warnText else 0xFFB0B5BD.toInt())
        })
        row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(TextView(this).apply {
            text = tr("Открыть"); setTextColor(Color.WHITE); textSize = 14f; setPadding(dp(14), dp(8), dp(14), dp(8))
            background = GradientDrawable().apply { setColor(Theme.accent); cornerRadius = dp(20).toFloat() }
            setOnClickListener { openBrowser(s) }
        })
        return row
    }

    // ---- встроенный браузер ----

    @SuppressLint("SetJavaScriptEnabled")
    private fun openBrowser(s: Source) {
        browsing = s
        root.removeAllViews()
        root.addView(TextView(this).apply {
            text = tr("%s: пройдите проверку на странице, затем нажмите «Готово».", s.title)
            setTextColor(Color.WHITE); textSize = 14f; setPadding(dp(14), dp(10), dp(14), dp(6))
        })
        root.addView(TextView(this).apply {
            text = "✓ " + tr("Готово"); setTextColor(Color.WHITE); textSize = 15f; setPadding(dp(16), dp(9), dp(16), dp(9))
            background = GradientDrawable().apply { setColor(Theme.accent); cornerRadius = dp(20).toFloat() }
            setOnClickListener { done(s) }
        }, LinearLayout.LayoutParams(-2, -2).apply { setMargins(dp(14), dp(2), dp(14), dp(8)) })
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; progressTintList = Theme.tint() }
        root.addView(bar, LinearLayout.LayoutParams(-1, dp(3)))
        WebAccess.noteOpened()   // с этого момента куки встроенного браузера идут и в запросы приложения
        val w = WebView(this).apply {
            settings.javaScriptEnabled = true; settings.domStorageEnabled = true
            Disguise.apply(this)
            CookieManager.getInstance().setAcceptCookie(true); CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            webViewClient = object : WebViewClient() {
                // Переходы внутри наших сайтов (все из Sources.ALL: вход, возрастное окно и проверка часто ведут на соседний
                // домен или поддомен) и переадресации разрешены. Блокируем явные внешние схемы (intent:, market:, tel: …) и
                // переход верхнего окна на чужой сайт по ссылке рекламы. Подкадры (проверка Cloudflare) сюда не попадают.
                override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                    if (r.url.scheme != "https" && r.url.scheme != "http") return true
                    if (!r.isForMainFrame || r.isRedirect) return false
                    return !WebAccess.isSite(r.url.host.orEmpty())
                }
                override fun onPageFinished(v: WebView, url: String) { AgeGate.pass(v) }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(v: WebView, p: Int) { bar.progress = p; bar.visibility = if (p < 100) View.VISIBLE else View.INVISIBLE }
            }
        }
        web = w
        val box = FrameLayout(this).apply { addView(w, FrameLayout.LayoutParams(-1, -1)) }
        root.addView(box, LinearLayout.LayoutParams(-1, 0, 1f))
        AgeGate.cookies()
        w.loadUrl(s.home)
    }

    private fun closeBrowser() {
        releaseWeb()
        browsing = null
        buildList()
    }

    /** Убрать WebView с экрана и освободить: сначала из родителя, потом destroy (иначе утечка окна и ошибки при повороте). */
    private fun releaseWeb() {
        val w = web ?: return
        web = null
        (w.parent as? android.view.ViewGroup)?.removeView(w)
        runCatching { w.stopLoading(); w.onPause(); w.destroy() }
    }

    /** «Готово»: сайт — проверенный (куки и User-Agent браузера в запросах), источник проверяется заново. */
    private fun done(s: Source) {
        CookieManager.getInstance().flush()
        WebAccess.markVerified(s)
        Toast.makeText(this, tr("Проверяю %s…", s.title), Toast.LENGTH_SHORT).show()
        val app = applicationContext
        closeBrowser()
        thread {
            runCatching { s.tracked("", false, 1) }
            ui.post {
                if (SourceHealth.status(s.id).state == SourceState.OK) Toast.makeText(app, tr("Источник снова в выдаче"), Toast.LENGTH_LONG).show()
                else Toast.makeText(app, tr("%s пока не отдаёт ролики — откройте сайт ещё раз", s.title), Toast.LENGTH_LONG).show()
                render()
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val w = web
        when {
            w != null && w.canGoBack() -> w.goBack()
            browsing != null -> closeBrowser()
            else -> @Suppress("DEPRECATION") super.onBackPressed()
        }
    }

    override fun onResume() { super.onResume(); web?.onResume(); if (browsing == null) render() }
    override fun onPause() { web?.onPause(); super.onPause() }
    override fun onDestroy() { SourceHealth.listeners -= onHealth; releaseWeb(); super.onDestroy() }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()

    companion object {
        /** Короткая подпись состояния: «капча», «возрастное окно», «доступ закрыт», «в порядке», «временная ошибка». */
        fun stateText(st: SourceStatus): String = when {
            st.at == 0L && st.state == SourceState.OK -> tr("ещё не проверялся")
            st.state == SourceState.OK -> "✓ " + tr("работает") + ago(st.at)
            st.state == SourceState.ERROR -> "✕ " + tr("временная ошибка") + ago(st.at)
            else -> stateNeeds(st)
        }

        /** «· 5 мин назад» — когда источник последний раз отвечал (или сломался). */
        private fun ago(at: Long): String {
            val m = ((System.currentTimeMillis() - at) / 60_000).coerceAtLeast(0)
            return " · " + when { m < 1 -> tr("только что"); m < 60 -> tr("%d мин назад", m.toInt()); else -> tr("%d ч назад", (m / 60).toInt()) }
        }

        private fun stateNeeds(st: SourceStatus): String = when (st.state) {
            SourceState.OK, SourceState.ERROR -> "" 
            SourceState.NEEDS_ACTION -> "⚠ " + when (st.reason) {
                BlockReason.CAPTCHA -> tr("капча")
                BlockReason.AGE_GATE -> tr("возрастное окно")
                else -> tr("доступ закрыт")
            }
        }
    }
}
