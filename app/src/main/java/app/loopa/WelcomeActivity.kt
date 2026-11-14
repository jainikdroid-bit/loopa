package app.loopa

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * Приветствие при первом запуске: коротко, как работает Loopa; галочки источников ленты «Для вас»; затем по очереди вход
 * в выбранные сайты (у каждого можно пропустить; советуем Google — проще всего). В конце — лента. «Пропустить» на первом
 * экране — RedGIFs и Reddit, без входа. Галочки потом меняются в меню 👤 → «Источники».
 * Тем, у кого Loopa уже стояла (в настройках что-то есть), приветствие не показывается.
 */
class WelcomeActivity : Activity() {
    companion object {
        private const val DONE = "welcomed"

        /** Показать приветствие: первый запуск (настроек ещё нет). Старым установкам — отметить и не показывать. */
        fun needed(p: SharedPreferences): Boolean {
            if (p.getBoolean(DONE, false)) return false
            if (p.all.keys.any { it != DONE }) { p.edit().putBoolean(DONE, true).apply(); return false }
            return true
        }
        private const val RQ = 7
    }

    private class Src(val key: String, val title: String, val note: String, var on: Boolean)
    /** RedGIFs и Reddit, затем все сторонние сайты из [Sources.ALL] (ключ — id сайта). */
    private val src = listOf(
        Src("rg", "RedGIFs", "короткие ролики по тегам", true),
        Src("rd", "Reddit", "сабреддиты и подписки", true),
    ) + Sources.ALL.map { Src(it.id, it.title, it.about, it.default) }
    /** Очередь входов после выбора галочек. */
    private val queue = ArrayDeque<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        I18n.init(this); ForYou.init(this); Ext.init(this)
        window.statusBarColor = MainActivity.BG; window.navigationBarColor = MainActivity.BG
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(22), dp(28), dp(22), dp(22)) }
        box.addView(TextView(this).apply { text = tr("Добро пожаловать в Loopa"); setTextColor(Color.WHITE); textSize = 24f; typeface = android.graphics.Typeface.DEFAULT_BOLD })
        box.addView(TextView(this).apply {
            text = tr("Loopa собирает короткие видео с нескольких сайтов в одну ленту. «Для вас» учится на ваших лайках, досмотрах и поисках: что нравится раз за разом — показывается чаще, что пролистываете — реже. Всё хранится только на телефоне.")
            setTextColor(0xFFC9CDD4.toInt()); textSize = 15f; setPadding(0, dp(12), 0, dp(18)); setLineSpacing(0f, 1.15f)
        })
        box.addView(TextView(this).apply { text = tr("Откуда брать видео для ленты:"); setTextColor(Color.WHITE); textSize = 16f; typeface = android.graphics.Typeface.DEFAULT_BOLD; setPadding(0, 0, 0, dp(6)) })
        src.forEach { s ->
            box.addView(CheckBox(this).apply {
                text = s.title + " — " + tr(s.note); isChecked = s.on; setTextColor(Color.WHITE); textSize = 15f
                buttonTintList = android.content.res.ColorStateList.valueOf(MainActivity.ACCENT); setPadding(dp(6), dp(8), 0, dp(8))
                setOnCheckedChangeListener { _, c -> s.on = c }
            })
        }
        box.addView(TextView(this).apply {
            text = tr("Дальше — вход в выбранные сайты, по очереди. Проще всего входить через Google. Любой вход можно пропустить и сделать потом в меню 👤.")
            setTextColor(0xFF9AA0AA.toInt()); textSize = 13f; setPadding(0, dp(14), 0, dp(20))
        })
        box.addView(button(tr("Далее"), MainActivity.ACCENT) { next() })
        box.addView(button(tr("Пропустить"), MainActivity.CHIP) { skip() }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        setContentView(ScrollView(this).apply { setBackgroundColor(MainActivity.BG); addView(box)
            setOnApplyWindowInsetsListener { v, ins -> @Suppress("DEPRECATION") v.setPadding(0, ins.systemWindowInsetTop, 0, ins.systemWindowInsetBottom); ins } })
    }

    /** Галочки выбраны: сохранить источники и пройти входы по порядку (RedGIFs → Reddit → сайты со входом: Erome, Eporner). */
    private fun next() {
        val on = src.associate { it.key to it.on }
        if (on.values.none { it }) { android.widget.Toast.makeText(this, tr("Выберите хотя бы один источник"), android.widget.Toast.LENGTH_SHORT).show(); return }
        save(on)
        queue.clear(); src.filter { it.on && (it.key == "rg" || it.key == "rd" || Ext.site(it.key) != null) }.forEach { queue.addLast(it.key) }
        login()
    }

    private fun skip() { save(mapOf("rg" to true, "rd" to true)); finishWelcome() }

    /** Сохранить галочки: ключ rg, rd или id стороннего сайта (нет в карте — выключен). */
    private fun save(on: Map<String, Boolean>) {
        ForYou.useRg = on["rg"] == true; ForYou.mixReddit = on["rd"] == true
        Sources.ALL.forEach { ForYou.setUses(it, on[it.id] == true) }
    }

    /** Следующий вход из очереди; очередь пуста — в ленту. Вход отменили («Назад») — просто дальше. */
    @Suppress("DEPRECATION")
    private fun login() {
        val k = queue.removeFirstOrNull() ?: return finishWelcome()
        val i = when (k) {
            "rg" -> Intent(this, LoginActivity::class.java).putExtra("site", "rg")
            "rd" -> Intent(this, LoginActivity::class.java).putExtra("site", "reddit")
            else -> Intent(this, ExtLoginActivity::class.java).putExtra("site", k)
        }
        android.widget.Toast.makeText(this, tr("Совет: войдите через Google — так проще. «Назад» — пропустить этот вход."), android.widget.Toast.LENGTH_LONG).show()
        startActivityForResult(i, RQ)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(rq: Int, rs: Int, data: Intent?) {
        @Suppress("DEPRECATION") super.onActivityResult(rq, rs, data)
        if (rq == RQ) login()
    }

    private fun finishWelcome() {
        getSharedPreferences("loopa", Context.MODE_PRIVATE).edit().putBoolean(DONE, true).apply()
        setResult(RESULT_OK); finish()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() = skip()

    private fun button(t: String, color: Int, click: () -> Unit) = TextView(this).apply {
        text = t; setTextColor(Color.WHITE); textSize = 16f; gravity = Gravity.CENTER; setPadding(dp(16), dp(14), dp(16), dp(14))
        background = GradientDrawable().apply { setColor(color); cornerRadius = dp(26).toFloat() }
        setOnClickListener { click() }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()
}
