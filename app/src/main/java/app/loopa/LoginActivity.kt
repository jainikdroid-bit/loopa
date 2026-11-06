package app.loopa

import android.app.Activity
import android.os.Bundle
import android.graphics.Color
import android.webkit.WebView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * Вход: redgifs.com в WebView — пользователь сам входит (почта, Reddit; Google в WebView не пускает), пароль
 * приложение не видит; токен ловит [TokenCatcher]. Куки входа остаются в WebView — по ним [Renewer] потом продлевает
 * вход без окна.
 */
class LoginActivity : Activity() {
    private lateinit var web: WebView
    private lateinit var catcher: TokenCatcher

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(MainActivity.BG) }
        root.setOnApplyWindowInsetsListener { v, ins ->
            @Suppress("DEPRECATION") v.setPadding(ins.systemWindowInsetLeft, ins.systemWindowInsetTop, ins.systemWindowInsetRight, ins.systemWindowInsetBottom); ins
        }
        root.addView(TextView(this).apply {
            text = "Войдите в RedGIFs (почта или Reddit). Окно закроется само после входа."
            setTextColor(Color.WHITE); textSize = 14f; setPadding(32, 20, 32, 20)
        })
        web = WebView(this)
        root.addView(web, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        catcher = TokenCatcher(web) { t, exp ->
            Api.setUser(this, t, exp)
            Toast.makeText(this, "Вход выполнен", Toast.LENGTH_SHORT).show()
            setResult(RESULT_OK); finish()
        }
        catcher.start("https://www.redgifs.com/login")
    }

    override fun onDestroy() { catcher.stop(); web.destroy(); super.onDestroy() }
}
