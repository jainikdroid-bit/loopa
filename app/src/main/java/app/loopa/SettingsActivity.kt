package app.loopa

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import kotlin.math.roundToInt

/**
 * Настройки: список разделов, по нажатию — подэкран раздела (одна Activity, «назад» возвращает к разделам). Всё, что
 * раньше было в длинном меню ⧩ и в меню 👤, — здесь. Что поменялось, возвращается в [MainActivity] через результат:
 * «reload» — перезагрузить ленту, «home» — на домашнюю, «tab» — открыть вкладку после входа; иначе лента только перерисуется.
 */
class SettingsActivity : Activity() {
    private val prefs by lazy { getSharedPreferences("loopa", MODE_PRIVATE) }
    private lateinit var body: LinearLayout
    private lateinit var head: TextView
    /** Открытый раздел; null — список разделов. */
    private var page: String? = null
    private val result = Intent()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        I18n.init(this); Theme.init(this); Api.init(this); Reddit.init(this); Ext.init(this); ForYou.init(this); Interests.init(this); Hide.init(this); Net.init(this); Video.init(this)
        page = savedInstanceState?.getString("page")
        Theme.bars(this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Theme.bg) }
        root.setOnApplyWindowInsetsListener { v, ins ->
            @Suppress("DEPRECATION") v.setPadding(ins.systemWindowInsetLeft, ins.systemWindowInsetTop, ins.systemWindowInsetRight, ins.systemWindowInsetBottom); ins
        }
        val bar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), dp(8), dp(16), dp(8)) }
        bar.addView(TextView(this).apply {
            text = "‹"; setTextColor(Theme.accent); textSize = 30f; gravity = Gravity.CENTER; setPadding(dp(12), 0, dp(12), dp(4))
            setOnClickListener { onBackPressed() }
        })
        head = TextView(this).apply { setTextColor(Color.WHITE); textSize = 22f; typeface = android.graphics.Typeface.DEFAULT_BOLD }
        bar.addView(head)
        root.addView(bar)
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(4), dp(14), dp(24)) }
        root.addView(ScrollView(this).apply { addView(body) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        render()
    }

    override fun onSaveInstanceState(out: Bundle) { super.onSaveInstanceState(out); out.putString("page", page) }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (page != null) { page = null; render() } else @Suppress("DEPRECATION") super.onBackPressed()
    }

    private class Section(val id: String, val title: String, val build: () -> Unit)

    private fun sections() = listOf(
        Section("feed", tr("Лента"), ::feed), Section("sources", tr("Источники"), ::sources), Section("interests", tr("Интересы"), ::interests),
        Section("video", tr("Видео и кэш"), ::video), Section("accounts", tr("Аккаунты"), ::accounts), Section("look", tr("Оформление"), ::look),
        Section("updates", tr("Обновления"), ::updates), Section("about", tr("О приложении"), ::about),
    )

    private fun render() {
        body.removeAllViews()
        val s = sections().firstOrNull { it.id == page }
        head.text = s?.title ?: tr("Настройки")
        if (s != null) s.build() else sections().forEach { sec -> nav(sec.title) { page = sec.id; render() } }
    }

    // --- разделы ---

    private fun feed() {
        toggle(tr("Живые превью в сетке"), null, prefs.getBoolean("live", true)) { prefs.edit().putBoolean("live", it).apply(); touch() }
        val hide = if (Hide.active) tr("(вкл.)") + Hide.custom.takeIf { it.isNotBlank() }?.let { " $it" }.orEmpty() else null
        action(tr("Скрывать…"), hide) { hideDialog() }
        val rd = Reddit.hadUser
        toggle(tr("Искать и на Reddit"), if (rd) null else tr("сначала войдите в Reddit"), prefs.getBoolean("search_reddit", true), rd) {
            Feed.searchReddit = it; prefs.edit().putBoolean("search_reddit", it).apply(); reload()
        }
    }

    private fun sources() {
        caption(tr("Источники: «Для вас» и поиск"))
        toggle("RedGIFs", null, ForYou.useRg) { ForYou.useRg = it; sourcesChanged() }
        toggle("Reddit", null, ForYou.mixReddit) { ForYou.mixReddit = it; sourcesChanged() }
        Sources.ALL.forEach { s -> toggle(s.title, null, ForYou.uses(s)) { ForYou.setUses(s, it); sourcesChanged() } }
        caption(tr("Доля источника в «Для вас»"))
        val shares = (listOf(Triple("rg", "RedGIFs", ForYou.useRg), Triple("rd", "Reddit", ForYou.reddit)) +
            Sources.ALL.map { Triple(it.badge.lowercase(), it.title, ForYou.uses(it)) }).filter { it.third }
        shares.forEach { (key, name, _) ->
            choice(name, ForYou.WEIGHTS.map { tr(it.second) }, ForYou.WEIGHTS.indexOfFirst { it.first == ForYou.weight(key) }) { ForYou.setWeight(key, ForYou.WEIGHTS[it].first); reload() }
        }
    }

    private fun sourcesChanged() { if (Feed.mode == "foryou") reload() else touch(); render() }

    private fun interests() {
        action(tr("Мои интересы…"), null) {
            AlertDialog.Builder(this).setTitle(tr("Мои интересы")).setMessage(Interests.summary()).setPositiveButton("OK", null)
                .setNeutralButton(tr("3D-граф")) { _, _ -> startActivity(Intent(this, GraphActivity::class.java)) }.show()
        }
        action(tr("Граф интересов…"), null) { startActivity(Intent(this, GraphActivity::class.java)) }
        val rd = Reddit.hadUser
        action(tr("Взять лайки с Reddit"), if (rd) null else tr("сначала войдите в Reddit"), rd) { importRedditLikes() }
        action(tr("Сбросить интересы"), null) {
            AlertDialog.Builder(this).setMessage(tr("Забыть лайки, просмотры и поиски для «Для вас»?"))
                .setPositiveButton(tr("Сбросить")) { _, _ -> Interests.reset(); reload() }.setNegativeButton(tr("Отмена"), null).show()
        }
    }

    /** Лайки с Reddit → вкусы; с сообщением о результате. */
    private fun importRedditLikes() {
        val app = applicationContext
        Toast.makeText(this, tr("Читаю лайки Reddit…"), Toast.LENGTH_SHORT).show()
        Thread {
            val r = runCatching { RedditImport.run(app) }
            runOnUiThread {
                Toast.makeText(this, r.fold({ tr("Учтено лайков Reddit: %s", it.toString()) }, { tr("Лайки Reddit не загрузились: %s", it.message ?: "?") }), Toast.LENGTH_LONG).show()
                if (r.getOrDefault(0) > 0) reload()
            }
        }.start()
    }

    private fun video() {
        // тот же выбор, что кнопка качества в плеере
        val q = listOf("auto", "hd", "sd")
        choice(tr("Качество по умолчанию"), listOf(tr("Авто"), "HD", "SD"), q.indexOf(prefs.getString("quality", "auto")).coerceAtLeast(0), tr("Авто: HD на быстрой сети, SD на медленной")) {
            prefs.edit().putString("quality", q[it]).apply()
        }
        val used = tr("Занято: %s", size(Video.cacheUsed + Net.imgCacheUsed))
        choice(tr("Кэш видео"), PreloadPolicy.CACHE_CHOICES.map { if (it == 0) tr("Авто — по свободному месту") else size(it * PreloadPolicy.MB) },
            PreloadPolicy.CACHE_CHOICES.indexOf(Video.cacheChoice).coerceAtLeast(0), used) { Video.cacheChoice = PreloadPolicy.CACHE_CHOICES[it] }
    }

    /** Размер кэша: «512 МБ», «1 ГБ», «1,5 ГБ». */
    private fun size(b: Long): String {
        val mb = b / PreloadPolicy.MB
        if (mb < 1024) return tr("%s МБ", mb)
        val gb = mb / 1024.0
        return tr("%s ГБ", if (gb % 1.0 == 0.0) gb.toInt().toString() else String.format(java.util.Locale.getDefault(), "%.1f", gb))
    }

    private fun accounts() {
        val rg = Api.hadUser
        action("RedGIFs", if (rg) tr("вход выполнен — нажмите, чтобы выйти") else tr("не выполнен — нажмите, чтобы войти")) {
            if (rg) { Api.logout(); TokenCatcher.clearCookies("https://www.redgifs.com", ".redgifs.com"); result.putExtra("home", true); touch(); render() }
            else login("rg", RQ_RG)
        }
        val rd = Reddit.hadUser
        action("Reddit", if (rd) tr("вход выполнен — нажмите, чтобы выйти") + Reddit.name.takeIf { it.isNotEmpty() }?.let { " (u/$it)" }.orEmpty() else tr("не выполнен — нажмите, чтобы войти")) {
            if (rd) { Reddit.logout(); if (Feed.reddit) result.putExtra("home", true); touch(); render() } else login("reddit", RQ_RD)
        }
        Ext.ALL.forEach { s ->
            val on = Ext.loggedIn(s)
            action(s.title, if (on) tr("вход выполнен — нажмите, чтобы выйти") else tr("не выполнен — нажмите, чтобы войти")) {
                if (on) { Ext.logout(s); reload(); render() }
                else @Suppress("DEPRECATION") startActivityForResult(Intent(this, ExtLoginActivity::class.java).putExtra("site", s.id), RQ_EXT)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun login(site: String, rq: Int) = startActivityForResult(Intent(this, LoginActivity::class.java).putExtra("site", site), rq)

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(rq: Int, rs: Int, data: Intent?) {
        @Suppress("DEPRECATION") super.onActivityResult(rq, rs, data)
        if (rs == RESULT_OK) when (rq) {
            RQ_RG -> { result.putExtra("tab", "foryou"); touch() }
            RQ_RD -> { result.putExtra("tab", "reddit"); touch() }
            RQ_EXT -> reload()
        }
        if (page == "accounts") render()
    }

    private fun look() {
        caption(tr("Тема"))
        Theme.ALL.forEach { t ->
            val dot = View(this).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(t.accent) } }
            row(tr(t.title), null, if (t.id == Theme.id) "✓" else null, dot) {
                if (t.id != Theme.id) { Theme.set(this, t.id); touch(); recreate() }
            }
        }
        val ids = listOf("auto") + I18n.LANGS.keys
        caption("🌐 " + tr("Язык"))
        choice(tr("Язык"), ids.map { if (it == "auto") tr("Авто — как в системе (%s)", I18n.LANGS[I18n.system]) else I18n.LANGS[it]!! }, ids.indexOf(I18n.choice).coerceAtLeast(0)) {
            I18n.choice = ids[it]; touch(); recreate()
        }
    }

    private fun updates() {
        val v = Updater.ready(this)
        note(tr("Установлена версия %s", Updater.current(this)) + (v?.let { "\n" + tr("Доступна версия %s", it) } ?: ""))
        val modes = listOf(Updater.MANUAL, Updater.ASK, Updater.AUTO)
        choice(tr("Режим обновления"), listOf(tr("Вручную — только по кнопке"), tr("Предлагать — скачать и спросить"), tr("Автоматически — скачать и установить")),
            modes.indexOf(Updater.mode(this))) { Updater.setMode(this, modes[it]) }
        if (v != null) action(tr("Установить"), v) { install() }
        action(tr("Проверить сейчас"), null) { checkNow() }
    }

    private fun install() { Updater.install(applicationContext)?.let { Toast.makeText(this, it, Toast.LENGTH_LONG).show() } }

    /** «Проверить сейчас»: с сообщением; нашлась новая версия — вопрос «Установить?». */
    private fun checkNow() {
        val app = applicationContext
        Toast.makeText(this, tr("Проверяю…"), Toast.LENGTH_SHORT).show()
        Thread {
            val r = Updater.check(app, true)
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                val v = r.ready
                if (v == null) { if (r.message.isNotEmpty()) Toast.makeText(this, r.message, Toast.LENGTH_LONG).show(); return@runOnUiThread }
                render()
                AlertDialog.Builder(this).setTitle(tr("Доступна версия %s", v)).setMessage(r.notes.take(700).ifBlank { r.message })
                    .setPositiveButton(tr("Установить")) { _, _ -> install() }.setNegativeButton(tr("Позже"), null).show()
            }
        }.start()
    }

    private fun about() {
        note("Adult Space\n" + tr("Версия %s", Updater.current(this)))
        action("GitHub", "github.com/jainikdroid-bit/loopa") { open("https://github.com/jainikdroid-bit/loopa") }
        action(tr("Сайт"), "jainikdroid-bit.github.io/loopa") { open("https://jainikdroid-bit.github.io/loopa/") }
        note(tr("Лицензия: MIT — свободное открытое ПО."))
        note(tr("Неофициальное приложение: к сервисам, ролики которых показывает, отношения не имеет. Только для совершеннолетних, 18+."))
    }

    private fun open(url: String) { runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } }

    // --- диалоги ---

    private fun hideDialog() {
        val on = BooleanArray(Hide.PRESETS.size) { Hide.on(Hide.PRESETS[it].first) }
        AlertDialog.Builder(this).setTitle(tr("Скрывать…"))
            .setMultiChoiceItems(Hide.PRESETS.map { tr(it.second) }.toTypedArray(), on) { _, i, c -> Hide.set(Hide.PRESETS[i].first, c); reload() }
            .setNeutralButton(tr("Свои слова…")) { _, _ -> hideWords() }
            .setPositiveButton("OK", null).setOnDismissListener { render() }.show()
    }

    /** Свои слова для скрытия — через запятую. */
    private fun hideWords() {
        val e = EditText(this).apply { setText(Hide.custom); hint = tr("например: cock, bbc"); setSingleLine() }
        AlertDialog.Builder(this).setTitle(tr("Скрывать ролики со словами")).setView(e)
            .setPositiveButton(tr("Сохранить")) { _, _ -> Hide.custom = e.text.toString().trim(); reload(); render() }.setNegativeButton(tr("Отмена"), null).show()
    }

    // --- результат для ленты ---

    /** Лента только перерисуется (вкладки, метки, превью). */
    private fun touch() = setResult(RESULT_OK, result)
    /** Лента перезагрузится. */
    private fun reload() { result.putExtra("reload", true); touch() }

    // --- строки ---

    private fun row(title: String, sub: String?, value: String? = null, lead: View? = null, enabled: Boolean = true, extra: View? = null, click: (() -> Unit)? = null): LinearLayout {
        val r = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), dp(14), dp(16), dp(14)); alpha = if (enabled) 1f else 0.45f
            background = GradientDrawable().apply { setColor(Theme.field); cornerRadius = dp(14).toFloat() }
        }
        lead?.let { r.addView(it, LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginEnd = dp(14) }) }
        val text = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        text.addView(TextView(this).apply { this.text = title; setTextColor(Color.WHITE); textSize = 16f })
        sub?.let { text.addView(TextView(this).apply { this.text = it; setTextColor(0xFFB0B5BD.toInt()); textSize = 13f; setPadding(0, dp(2), 0, 0) }) }
        r.addView(text, LinearLayout.LayoutParams(0, -2, 1f))
        value?.let { r.addView(TextView(this).apply { this.text = it; setTextColor(Theme.soft); textSize = 14f; gravity = Gravity.END; setPadding(dp(10), 0, 0, 0) }, LinearLayout.LayoutParams(-2, -2)) }
        extra?.let { r.addView(it) }
        if (click != null && enabled) r.setOnClickListener { click() }
        body.addView(r, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        return r
    }

    private fun nav(title: String, to: () -> Unit) { row(title, null, "›", click = to) }

    private fun action(title: String, sub: String?, enabled: Boolean = true, click: () -> Unit) { row(title, sub, enabled = enabled, click = click) }

    private fun toggle(title: String, sub: String?, on: Boolean, enabled: Boolean = true, change: (Boolean) -> Unit) {
        val sw = Switch(this).apply {
            isChecked = on && enabled; isEnabled = enabled
            val (thumb, track) = Theme.switchTints(); thumbTintList = thumb; trackTintList = track
            setOnCheckedChangeListener { _, c -> change(c) }
        }
        val r = row(title, sub, enabled = enabled, extra = sw)
        // нажатие по строке — то же, что по самому переключателю
        if (enabled) r.setOnClickListener { sw.toggle() }
    }

    /** Выбор из списка: справа текущее значение, нажатие — окно со списком. */
    private fun choice(title: String, options: List<String>, current: Int, sub: String? = null, pick: (Int) -> Unit) {
        row(title, sub, options.getOrNull(current)) {
            AlertDialog.Builder(this).setTitle(title).setSingleChoiceItems(options.toTypedArray(), current) { d, i -> d.dismiss(); pick(i); render() }
                .setNegativeButton(tr("Отмена"), null).show()
        }
    }

    private fun caption(t: String) {
        body.addView(TextView(this).apply { text = t; setTextColor(Theme.accent); textSize = 13f; typeface = android.graphics.Typeface.DEFAULT_BOLD; setPadding(dp(4), dp(14), 0, dp(8)) })
    }

    private fun note(t: String) {
        body.addView(TextView(this).apply { text = t; setTextColor(0xFFC9CDD4.toInt()); textSize = 14f; setPadding(dp(4), dp(8), dp(4), dp(8)); setLineSpacing(0f, 1.1f) })
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()

    private companion object { const val RQ_RG = 1; const val RQ_RD = 2; const val RQ_EXT = 3 }
}
