package app.loopa

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.webkit.WebView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * Вход на сайт (RedGIFs или Reddit — extra «site»): страница сайта в WebView, пользователь входит сам, пароль приложение
 * не видит; токен ловит [TokenCatcher]. Куки входа остаются в WebView — по ним [Renewer] потом продлевает вход без окна.
 */
class LoginActivity : Activity() {
    private lateinit var web: WebView
    private lateinit var catcher: TokenCatcher

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val site = if (intent.getStringExtra("site") == "reddit") Reddit.SITE else RgSite
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(MainActivity.BG) }
        root.setOnApplyWindowInsetsListener { v, ins ->
            @Suppress("DEPRECATION") v.setPadding(ins.systemWindowInsetLeft, ins.systemWindowInsetTop, ins.systemWindowInsetRight, ins.systemWindowInsetBottom); ins
        }
        root.addView(TextView(this).apply {
            text = if (site === Reddit.SITE) "Войдите в Reddit. Чтобы видеть 18+, включите его в настройках аккаунта. Окно закроется само после входа."
                else "Войдите в RedGIFs (почта или Reddit). Окно закроется само после входа."
            setTextColor(Color.WHITE); textSize = 14f; setPadding(32, 20, 32, 20)
        })
        web = WebView(this)
        root.addView(web, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        catcher = TokenCatcher(web, site) { t, exp ->
            Thread { site.save(this, t, exp); runOnUiThread {
                Toast.makeText(this, "Вход в ${site.title} выполнен", Toast.LENGTH_SHORT).show()
                setResult(RESULT_OK); finish()
            } }.start()
        }
        catcher.start(site.login)
    }

    override fun onDestroy() { catcher.stop(); web.destroy(); super.onDestroy() }
}
