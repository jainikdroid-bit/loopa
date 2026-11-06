package app.loopa

import android.app.Activity
import android.content.ClipboardManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import kotlin.math.roundToInt

/**
 * Вход на сайт (RedGIFs или Reddit — extra «site»): страница сайта в WebView, пользователь входит сам, пароль приложение
 * не видит; токен ловит [TokenCatcher]. Куки входа остаются в WebView — по ним [Renewer] потом продлевает вход без окна.
 * Google во встроенных окнах вход запрещает — для Reddit есть вход по одноразовой ссылке из письма: ссылку открываем
 * здесь же, в этом WebView, чтобы сессия досталась приложению.
 */
class LoginActivity : Activity() {
    private lateinit var web: WebView
    private lateinit var catcher: TokenCatcher
    private lateinit var state: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val site = if (intent.getStringExtra("site") == "reddit") Reddit.SITE else RgSite
        val reddit = site === Reddit.SITE
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(MainActivity.BG) }
        root.setOnApplyWindowInsetsListener { v, ins ->
            @Suppress("DEPRECATION") v.setPadding(ins.systemWindowInsetLeft, ins.systemWindowInsetTop, ins.systemWindowInsetRight, ins.systemWindowInsetBottom); ins
        }
        root.addView(TextView(this).apply {
            text = if (reddit) "Войдите в Reddit именем и паролем или по одноразовой ссылке («Email me a one-time link»): скопируйте ссылку из письма и нажмите кнопку ниже. Вход через Google во встроенном окне Google запрещает. Чтобы видеть 18+, включите его в настройках аккаунта."
                else "Войдите в RedGIFs (почта или Reddit). Окно закроется само после входа."
            setTextColor(Color.WHITE); textSize = 14f; setPadding(dp(14), dp(10), dp(14), dp(6))
        })
        if (reddit) root.addView(TextView(this).apply {
            text = "📋 Вставить ссылку из письма"; setTextColor(Color.WHITE); textSize = 15f; setPadding(dp(16), dp(9), dp(16), dp(9))
            background = GradientDrawable().apply { setColor(MainActivity.ACCENT); cornerRadius = dp(20).toFloat() }
            setOnClickListener { pasteLink() }
        }, LinearLayout.LayoutParams(-2, -2).apply { setMargins(dp(14), dp(2), dp(14), dp(8)) })
        state = TextView(this).apply { setTextColor(0xFFB0B5BD.toInt()); textSize = 12f; setPadding(dp(14), 0, dp(14), dp(4)) }
        root.addView(state)
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        root.addView(bar, LinearLayout.LayoutParams(-1, dp(3)))
        web = WebView(this)
        root.addView(web, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        catcher = TokenCatcher(web, site, { url, err -> state.text = if (err != null) "Не открылось: $err — проверьте VPN/сеть и нажмите здесь, чтобы повторить" else Uri.parse(url).host.orEmpty() }) { t, exp ->
            Thread { site.save(this, t, exp); runOnUiThread {
                Toast.makeText(this, "Вход в ${site.title} выполнен", Toast.LENGTH_SHORT).show()
                setResult(RESULT_OK); finish()
            } }.start()
        }
        catcher.start(site.login)
        // прогресс загрузки — чтобы долгая страница не выглядела белым пустым экраном
        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(v: WebView, p: Int) { bar.progress = p; bar.visibility = if (p < 100) View.VISIBLE else View.INVISIBLE }
        }
        state.setOnClickListener { web.reload() }
    }

    /** Одноразовая ссылка Reddit из буфера обмена — открыть здесь же. */
    private fun pasteLink() {
        val t = (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()?.trim().orEmpty()
        val link = Regex("https://\\S+").find(t)?.value
        val host = link?.let { Uri.parse(it).host.orEmpty() }.orEmpty()
        if (link == null || !(host == "reddit.com" || host.endsWith(".reddit.com") || host.endsWith(".redditmail.com"))) {
            Toast.makeText(this, "В буфере нет ссылки Reddit — скопируйте ссылку из письма целиком", Toast.LENGTH_LONG).show(); return
        }
        web.loadUrl(link)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()

    override fun onDestroy() { catcher.stop(); web.destroy(); super.onDestroy() }
}
