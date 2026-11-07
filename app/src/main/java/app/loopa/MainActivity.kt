package app.loopa

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import kotlin.concurrent.thread
import kotlin.math.roundToInt

/**
 * Главный экран: строка поиска, подсказки тегов (пока вводишь — из /v2/search/suggest,
 * иначе — популярные теги ленты), сортировка, сетка превью с бесконечной подгрузкой. Нажатие — плеер со свайпами,
 * долгое нажатие — скачать. Вкладка Reddit — та же сетка и плеер: поиск ищет сабреддиты, чипы — сохранённые
 * сабреддиты (долгое нажатие — убрать), сортировки Reddit.
 */
class MainActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var search: EditText
    private lateinit var chips: LinearLayout
    private lateinit var status: TextView
    private lateinit var sortBtn: TextView
    private lateinit var filterBtn: TextView
    private lateinit var grid: RecyclerView
    private lateinit var swipe: SwipeRefreshLayout
    private lateinit var tabs: LinearLayout
    private lateinit var userBtn: TextView
    private val adapter = GridAdapter()
    private var suggestGen = 0
    private val onFeed: () -> Unit = { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // первый запуск — приветствие (до того, как что-то запишется в настройки: по их пустоте узнаём новую установку)
        val welcome = savedInstanceState == null && WelcomeActivity.needed(prefs)
        I18n.init(this); Api.init(this); Net.init(this); Reddit.init(this); Ext.init(this); Interests.init(this); ForYou.init(this); Video.init(this); Hide.init(this)
        @Suppress("DEPRECATION") if (welcome) startActivityForResult(Intent(this, WelcomeActivity::class.java), 4)
        preview.enabled = prefs.getBoolean("live", true)
        if (savedInstanceState == null && Feed.items.isEmpty()) {
            Feed.duration = prefs.getString("duration", "any")!!; Feed.audioOnly = prefs.getBoolean("audio", false)
            Feed.desc = prefs.getBoolean("desc", true); Feed.searchReddit = prefs.getBoolean("search_reddit", true)
            prefs.getString("order", null)?.let { Feed.restore(it) }
            Feed.rorder = prefs.getString("rorder", "hot")!!
        }
        window.statusBarColor = BG; window.navigationBarColor = BG
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(BG) }
        root.setOnApplyWindowInsetsListener { v, ins ->
            @Suppress("DEPRECATION") v.setPadding(ins.systemWindowInsetLeft, ins.systemWindowInsetTop, ins.systemWindowInsetRight, ins.systemWindowInsetBottom); ins
        }
        // строка поиска + сортировка
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(10), dp(8), dp(10), dp(4)) }
        search = EditText(this).apply {
            hint = tr("Поиск по тегам: blonde, POV, car…"); setHintTextColor(0xFF8A8F98.toInt()); setTextColor(Color.WHITE); textSize = 16f
            isSingleLine = true; imeOptions = EditorInfo.IME_ACTION_SEARCH
            background = pill(0xFF1F232B.toInt()); setPadding(dp(14), dp(10), dp(14), dp(10))
            setOnEditorActionListener { _, a, e -> if (a == EditorInfo.IME_ACTION_SEARCH || e?.keyCode == KeyEvent.KEYCODE_ENTER) { go(text.toString()); true } else false }
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) { suggestLater(s.toString()) }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        }
        bar.addView(search, LinearLayout.LayoutParams(0, -2, 1f))
        sortBtn = chip("⇅ " + tr("Популярное"), ACCENT) { sortMenu(it) }
        bar.addView(sortBtn, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
        filterBtn = chip("⧩", CHIP) { filterMenu(it) }
        bar.addView(filterBtn, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(6) })
        userBtn = chip("👤", CHIP) { account() }
        bar.addView(userBtn, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(6) })
        root.addView(bar)
        // вкладки: рекомендации по лайкам (нужен вход), популярное, лайкнутое
        tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(10), dp(2), dp(10), dp(2)) }
        TABS.forEach { (t, m) -> tabs.addView(chip(tr(t), CHIP) { tab(m) }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(6) }) }
        // не влезают — строка прокручивается вбок, а не сжимает вкладки в столбик
        root.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(tabs) })
        chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(10), dp(4), dp(10), dp(4)) }
        root.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(chips) })
        status = TextView(this).apply { setTextColor(0xFFB0B5BD.toInt()); textSize = 13f; setPadding(dp(14), dp(2), dp(14), dp(6)) }
        root.addView(status)
        grid = RecyclerView(this).apply {
            layoutManager = GridLayoutManager(this@MainActivity, cols())
            adapter = this@MainActivity.adapter
            setPadding(dp(4), 0, dp(4), 0); clipToPadding = false
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                // живые превью играют и на ходу: при прокрутке набор играющих обновляется не чаще раза в [LIVE_SCROLL_MS]
                override fun onScrollStateChanged(rv: RecyclerView, st: Int) { if (st == RecyclerView.SCROLL_STATE_IDLE) live() }
                override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                    val now = android.os.SystemClock.uptimeMillis()
                    if (now - liveAt > LIVE_SCROLL_MS) { liveAt = now; live() }
                    val lm = rv.layoutManager as GridLayoutManager
                    if (lm.findLastVisibleItemPosition() > Feed.items.size - 12) Feed.more()
                }
            })
        }
        // потянуть вниз — обновить выдачу
        swipe = SwipeRefreshLayout(this).apply {
            setColorSchemeColors(ACCENT); setProgressBackgroundColorSchemeColor(0xFF1F232B.toInt())
            addView(grid)
            setOnRefreshListener { reload() }
        }
        root.addView(swipe, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        Feed.listeners += onFeed
        root0 = root
        // лента — сразу (без входа — популярное), вход продлевается в фоне: без VPN или на медленной сети это до 25 с
        if (Feed.items.isEmpty() && !Feed.busy) home() else refresh()
        if (Api.renewDue) renew(RgSite) else if (Reddit.renewDue) renew(Reddit.SITE)
    }
    private lateinit var root0: ViewGroup

    /** Продлить вход без окна; не вышло (нет сети или куки сайта тоже истекли) — следующий запуск попробует снова, а 👤 откроет вход. */
    private fun renew(site: Site) {
        if (Renewer.running) return
        Renewer.run(root0, site) { ok ->
            if (isDestroyed) return@run
            if (!ok) status.setOnClickListener { login(site) }
            val waiting = Feed.error == Api.LOGIN_EXPIRED || Feed.error == Reddit.LOGIN_EXPIRED
            // вход вернулся, пока на главной популярное, — переключить на «Для вас»
            val atHome = site === RgSite && Feed.mode == null && Feed.text.isEmpty() && Feed.author == null
            when {
                ok && waiting -> if (Feed.reddit) Feed.reddit(Feed.sub) else home()
                ok && atHome -> home()
                else -> refresh()
            }
            if (!ok && waiting) status.text = tr("Вход в %s истёк — нажмите здесь, чтобы войти снова", site.title)
            if (ok && site === RgSite && Reddit.renewDue) renew(Reddit.SITE)
        }
    }

    /** Домашняя лента: после входа — «Для вас», иначе популярное. */
    private fun home() {
        search.setText("")
        val fy = Api.loggedIn || ForYou.reddit || !Interests.empty
        Feed.reset("", homeOrder(), mode = if (fy) "foryou" else null); if (::grid.isInitialized) grid.scrollToPosition(0)
    }
    /** Без поиска релевантности нет — тогда популярное. */
    private fun homeOrder() = Feed.order.takeIf { it != "score" } ?: "trending"

    private fun tab(m: String?) {
        if (m == "reddit") {
            if (!Reddit.hadUser) { login(Reddit.SITE); return }
            nav(); search.setText(""); openSub(prefs.getString("last_sub", "")!!); return
        }
        if (m == "liked" && !Api.loggedIn) { pendingTab = m; login(RgSite); return }
        nav()
        search.setText(""); Feed.reset("", homeOrder(), mode = m); grid.scrollToPosition(0)
    }

    /** Переход на другую страницу: запомнить текущую (с прокруткой, а из плеера — с роликом, на котором закрыли) для «Назад». */
    private fun nav() {
        val at = Feed.lastSeen.takeIf { it >= 0 } ?: (grid.layoutManager as GridLayoutManager).findFirstVisibleItemPosition().coerceAtLeast(0)
        Feed.lastSeen = -1
        Feed.push(at)
    }

    /** «Назад» вернул прежнюю страницу — строку поиска и прокрутку как были. */
    private fun restored(pos: Int) {
        suggestGen++
        search.setText(if (Feed.reddit) "" else Feed.text); search.clearFocus(); suggestGen++
        refresh()
        (grid.layoutManager as GridLayoutManager).scrollToPositionWithOffset(pos.coerceIn(0, maxOf(0, Feed.items.size - 1)), 0)
    }
    private var pendingTab: String? = null

    private fun login(site: Site = RgSite) = @Suppress("DEPRECATION")
        startActivityForResult(Intent(this, LoginActivity::class.java).putExtra("site", if (site === Reddit.SITE) "reddit" else "rg"), if (site === Reddit.SITE) 2 else 1)

    /** Аккаунты: вход и выход отдельно для RedGIFs и Reddit (выход стирает куки только этого сайта). */
    private fun account() {
        val m = PopupMenu(this, userBtn)
        m.menu.add(0, 1, 0, if (Api.hadUser) tr("Выйти из RedGIFs") else tr("Войти в RedGIFs"))
        m.menu.add(0, 2, 1, if (Reddit.hadUser) tr("Выйти из Reddit") + Reddit.name.takeIf { it.isNotEmpty() }?.let { " (u/$it)" }.orEmpty() else tr("Войти в Reddit"))
        Ext.ALL.forEachIndexed { i, s -> m.menu.add(0, 4 + i, 1, if (Ext.loggedIn(s)) tr("Выйти из %s", s.title) else tr("Войти в %s", s.title)) }
        // галочки: какие сайты подмешивать в «Для вас» — вход на сайт при этом не снимается
        val fy = m.menu.addSubMenu(0, 9, 2, "♥ " + tr("Источники: «Для вас» и поиск"))
        listOf("RedGIFs" to ForYou.useRg, "Reddit" to ForYou.mixReddit, "Erome" to ForYou.useErome, "Eporner" to ForYou.useEporner)
            .forEachIndexed { i, (t, on) -> fy.add(14, i, i, t).apply { isCheckable = true; isChecked = on } }
        langMenu(m.menu)
        m.setOnMenuItemClickListener {
            if (it.groupId == 12) { I18n.choice = LANG_IDS[it.itemId]; recreate(); return@setOnMenuItemClickListener true }
            if (it.groupId == 14) {
                val on = !it.isChecked; it.isChecked = on
                when (it.itemId) { 0 -> ForYou.useRg = on; 1 -> ForYou.mixReddit = on; 2 -> ForYou.useErome = on; 3 -> ForYou.useEporner = on }
                if (Feed.mode == "foryou") reload() else refresh()
                return@setOnMenuItemClickListener true
            }
            if (it.groupId == 0 && it.itemId in 4..5) {
                val s = Ext.ALL[it.itemId - 4]
                if (Ext.loggedIn(s)) { Ext.logout(s); refresh() }
                else @Suppress("DEPRECATION") startActivityForResult(Intent(this, ExtLoginActivity::class.java).putExtra("site", s.id), 3)
                return@setOnMenuItemClickListener true
            }
            if (it.groupId != 0 || it.itemId == 3) return@setOnMenuItemClickListener false
            when (it.itemId) {
                1 -> if (Api.hadUser) { Api.logout(); TokenCatcher.clearCookies("https://www.redgifs.com", ".redgifs.com"); home() } else { pendingTab = "foryou"; login(RgSite) }
                2 -> if (Reddit.hadUser) { Reddit.logout(); if (Feed.reddit) home() else refresh() } else login(Reddit.SITE)
            }
            true
        }
        m.show()
    }

    /** Подменю «Язык»: авто (язык системы) или любой из поддерживаемых вручную. */
    private fun langMenu(menu: android.view.Menu) {
        val cur = I18n.choice
        val sub = menu.addSubMenu(0, 3, 2, "🌐 " + tr("Язык") + ": " + if (cur == "auto") tr("авто") else I18n.LANGS[cur])
        LANG_IDS.forEachIndexed { i, c ->
            val t = if (c == "auto") tr("Авто — как в системе (%s)", I18n.LANGS[I18n.system]) else I18n.LANGS[c]!!
            sub.add(12, i, i, t).isChecked = c == cur
        }
        sub.setGroupCheckable(12, true, true)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(rq: Int, rs: Int, data: Intent?) {
        @Suppress("DEPRECATION") super.onActivityResult(rq, rs, data)
        if (rs != RESULT_OK) return
        if (rq == 1) { val t = pendingTab ?: "foryou"; pendingTab = null; tab(t) }
        if (rq == 2) tab("reddit")
        if (rq == 3) { if (Feed.mode == "foryou") reload() else refresh() }
        if (rq == 4) home()
    }

    /** Reddit: сабреддит(ы) через «+», «u/имя» или пусто (главная с подписками); сабреддит запоминается в чипах. */
    private fun openSub(s: String) {
        val sub = s.trim().removePrefix("/").removePrefix("r/").removePrefix("R/").split(Regex("[\\s,+]+")).filter { it.isNotEmpty() }
            .joinToString("+").let { if (s.trim().startsWith("u/")) s.trim() else it }
        if (sub.isNotEmpty() && !sub.startsWith("u/")) {
            val saved = LinkedHashSet(savedSubs()); saved.add(sub); prefs.edit().putString("subs", saved.joinToString("\n")).apply()
        }
        prefs.edit().putString("last_sub", sub).apply()
        Interests.openSub(sub)
        Feed.reddit(sub); if (::grid.isInitialized) grid.scrollToPosition(0)
    }
    private fun savedSubs() = prefs.getString("subs", "")!!.split("\n").filter { it.isNotBlank() }

    override fun onDestroy() { Feed.listeners -= onFeed; preview.release(); super.onDestroy() }
    // превью — только пока главный экран на виду: иначе (лента догрузилась, пока открыт плеер) они запускались в фоне
    // и отбирали видеодекодеры у плеера — картинка вставала, а звук шёл дальше
    private var shown = false
    override fun onPause() { super.onPause(); shown = false; preview.stopAll() }
    override fun onResume() {
        super.onResume(); shown = true
        if (!::grid.isInitialized) return
        // из плеера: ролик, на котором закрыли, — на экране (листали дальше — сетка за ним)
        Feed.lastSeen.takeIf { it in Feed.items.indices }?.let { p ->
            val lm = grid.layoutManager as GridLayoutManager
            if (p < lm.findFirstCompletelyVisibleItemPosition() || p > lm.findLastCompletelyVisibleItemPosition()) lm.scrollToPositionWithOffset(p, 0)
        }
        Feed.lastSeen = -1
        grid.postDelayed({ live() }, 300)
    }

    private val preview by lazy { GridPreview(this) }
    private var waits = 0
    private val liveLater = Runnable { live() }
    private var liveAt = 0L

    /** Обновить живые превью: ячейки, видимые целиком. */
    private fun live() {
        if (!::grid.isInitialized || isFinishing || !shown) return
        val lm = grid.layoutManager as GridLayoutManager
        val a = lm.findFirstVisibleItemPosition(); val b = lm.findLastVisibleItemPosition()
        // порядок важности: видимые целиком, затем частично (чем больше видно — тем раньше), ближе к центру — раньше;
        // при нехватке превью отключаются уехавшие за край экрана
        val mid = grid.height / 2f
        val vis = if (a < 0) emptyList() else (a..b).mapNotNull { p ->
            val h = grid.findViewHolderForAdapterPosition(p) as? GridAdapter.H ?: return@mapNotNull null
            val v = h.itemView
            val shown = (minOf(v.bottom, grid.height) - maxOf(v.top, 0)).coerceAtLeast(0) / v.height.coerceAtLeast(1).toFloat()
            Feed.items.getOrNull(p)?.let { Triple(h.slot, it, -shown * 10_000 + kotlin.math.abs((v.top + v.bottom) / 2f - mid)) }
        }.sortedBy { it.third }.map { it.first to it.second }
        // сначала картинки: превью запускаем, только когда все видимые картинки догрузились (видео не отнимает у них канал)
        // (не дольше ~4 с: картинка могла не загрузиться вовсе)
        // (кроме ячейки, которой коснулись: её — сразу)
        if (vis.any { !Thumbs.has(it.second.thumb) } && waits++ < 6) {
            vis.firstOrNull { it.first === preview.touched }?.let { preview.update(listOf(it)) }
            grid.removeCallbacks(liveLater); grid.postDelayed(liveLater, 700); return
        }
        waits = 0
        preview.update(vis)
    }

    override fun onConfigurationChanged(c: Configuration) {
        super.onConfigurationChanged(c)
        (grid.layoutManager as GridLayoutManager).spanCount = cols()
    }

    /** Из плеера: нажали тег или автора. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra("tag")?.let { search.setText(it); go(it, tag = true) }
        intent.getStringExtra("author")?.let { nav(); search.setText(""); Feed.reset("", Feed.order, it); grid.scrollToPosition(0) }
        intent.getStringExtra("sub")?.let { nav(); search.setText(""); openSub(it) }
        // автор или тег ролика Erome/Eporner — поиск по тому же сайту
        for (m in listOf("erome", "eporner")) intent.getStringExtra(m)?.let { nav(); search.setText(it); Feed.reset(it, Feed.order, mode = m); grid.scrollToPosition(0) }
        if (intent.getBooleanExtra("login", false)) login(RgSite)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // назад — на прежнюю страницу (поиск, тег, автор…), как в браузере; истории нет — как раньше
        Feed.back()?.let { restored(it); return }
        if (Feed.reddit && Feed.sub.isNotEmpty()) { prefs.edit().putString("last_sub", "").apply(); Feed.reddit("") }
        else if (Feed.author != null || Feed.text.isNotEmpty() || Feed.reddit) home()
        else @Suppress("DEPRECATION") super.onBackPressed()
    }

    private fun cols() = if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) 4 else 2

    private fun go(q: String, tag: Boolean = false) {
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(search.windowToken, 0)
        search.clearFocus()
        nav()
        if (Feed.reddit) { openSub(q); return }
        // Erome/Eporner: поиск по тому же сайту (пусто — его лента)
        if (Feed.ext) { Feed.reset(q.trim(), Feed.order, mode = Feed.mode); grid.scrollToPosition(0); return }
        if (q.isBlank()) { home(); return }
        if (tag) Interests.openTag(q.trim()) else Interests.search(q)
        Feed.reset(q.trim(), Feed.order, tag = tag)
        grid.scrollToPosition(0)
    }

    /**
     * Кнопка сортировки ↑↓ — только сортировки. Повторное нажатие на выбранную (у которой есть направление: дата,
     * длительность, лайки, просмотры, рейтинг) меняет ↓ на ↑. Выбор запоминается.
     */
    private fun sortMenu(v: View) {
        val m = PopupMenu(this, v)
        if (Feed.reddit) {
            Reddit.SORTS.forEachIndexed { i, s -> m.menu.add(5, i, i, s.label).isChecked = s.id == Feed.rorder }
            m.menu.setGroupCheckable(5, true, true)
        }
        val sorts = when {
            Feed.reddit -> emptyList()
            Feed.mode == "foryou" -> listOf(Sort("trending", "По интересам", "Интересы", "trending")) + Feed.SORTS.filter { it.dir }
            else -> Feed.SORTS.filter { it.id != "score" || Feed.text.isNotEmpty() }
        }
        sorts.forEachIndexed { i, s ->
            val on = s.id == Feed.order
            m.menu.add(1, i, i, s.label + if (s.dir) (if (on) (if (Feed.desc) "  ↓" else "  ↑") else "  ↓↑") else "").isChecked = on
        }
        m.menu.setGroupCheckable(1, true, true)
        m.setOnMenuItemClickListener {
            when (it.groupId) {
                5 -> { Feed.reddit(Feed.sub, Reddit.SORTS[it.itemId].id); prefs.edit().putString("rorder", Feed.rorder).apply() }
                1 -> {
                    val s = sorts[it.itemId]
                    // та же сортировка ещё раз — сменить направление; новая — с убывания
                    Feed.desc = if (s.id == Feed.order && s.dir) !Feed.desc else true
                    Feed.reset(Feed.text, s.id, Feed.author, Feed.mode, Feed.byTag && Feed.query == Feed.text)
                    prefs.edit().putString("order", Feed.order).putBoolean("desc", Feed.desc).apply()
                }
                else -> return@setOnMenuItemClickListener false
            }
            grid.scrollToPosition(0); true
        }
        m.show()
    }

    /** Кнопка ⧩ — фильтры и настройки: «Для вас», длительность, звук, Reddit в поиске, превью, скрываемые темы. */
    private fun filterMenu(v: View) {
        val m = PopupMenu(this, v)
        if (Feed.mode == "foryou") {
            // доли включённых источников (галочки — в меню 👤): мало / средне / много
            FY_SRC.filter { it.third() }.forEachIndexed { n, (key, name, _) ->
                val w = ForYou.weight(key)
                val sh = m.menu.addSubMenu(6, 10 + n, n, tr("Доля %s: %s", name, I18n.lower(tr(ForYou.WEIGHTS.first { it.first == w }.second))))
                ForYou.WEIGHTS.forEachIndexed { i, (v, t) -> sh.add(20 + FY_SRC.indexOfFirst { it.first == key }, v, i, tr(t)).isChecked = v == w }
                sh.setGroupCheckable(20 + FY_SRC.indexOfFirst { it.first == key }, true, true)
            }
            m.menu.add(6, 2, 2, tr("Мои интересы…"))
            m.menu.add(6, 3, 3, tr("Сбросить интересы"))
        }
        val dur = m.menu.addSubMenu(2, 0, 100, tr("Длительность: %s", if (Feed.duration == "any") tr("любая") else tr(Feed.DURATIONS.first { it.first == Feed.duration }.second).let(I18n::lower)))
        Feed.DURATIONS.forEachIndexed { i, d -> dur.add(3, i, i, tr(d.second)).isChecked = d.first == Feed.duration }
        dur.setGroupCheckable(3, true, true)
        m.menu.add(4, 0, 101, tr("Только со звуком")).apply { isCheckable = true; isChecked = Feed.audioOnly }
        m.menu.add(11, 0, 102, tr("Искать и на Reddit")).apply { isCheckable = true; isChecked = Feed.searchReddit; isEnabled = Reddit.hadUser }
        m.menu.add(8, 0, 103, tr("Живые превью в сетке")).apply { isCheckable = true; isChecked = preview.enabled }
        val hide = m.menu.addSubMenu(9, 0, 104, tr("Скрывать…") + if (Hide.active) " " + tr("(вкл.)") else "")
        Hide.PRESETS.forEachIndexed { i, (id, t, _) -> hide.add(10, i, i, tr(t)).apply { isCheckable = true; isChecked = Hide.on(id) } }
        hide.add(10, 99, 99, tr("Свои слова…") + Hide.custom.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty())
        m.setOnMenuItemClickListener {
            when (it.groupId) {
                6 -> when (it.itemId) {
                        2 -> { android.app.AlertDialog.Builder(this).setTitle(tr("Мои интересы")).setMessage(Interests.summary()).setPositiveButton("OK", null).show(); return@setOnMenuItemClickListener true }
                    3 -> { android.app.AlertDialog.Builder(this).setMessage(tr("Забыть лайки, просмотры и поиски для «Для вас»?"))
                        .setPositiveButton(tr("Сбросить")) { _, _ -> Interests.reset(); reload() }.setNegativeButton(tr("Отмена"), null).show(); return@setOnMenuItemClickListener true }
                    else -> return@setOnMenuItemClickListener false
                }
                10 -> {
                    if (it.itemId == 99) { hideWords(); return@setOnMenuItemClickListener true }
                    val id = Hide.PRESETS[it.itemId].first; Hide.set(id, !Hide.on(id)); reload()
                }
                8 -> { preview.enabled = !preview.enabled; prefs.edit().putBoolean("live", preview.enabled).apply(); if (preview.enabled) live() else preview.stopAll(); return@setOnMenuItemClickListener true }
                11 -> { Feed.searchReddit = !Feed.searchReddit; prefs.edit().putBoolean("search_reddit", Feed.searchReddit).apply(); reload() }
                in 20..23 -> { ForYou.setWeight(FY_SRC[it.groupId - 20].first, it.itemId); reload() }
                3 -> { Feed.duration = Feed.DURATIONS[it.itemId].first; prefs.edit().putString("duration", Feed.duration).apply(); reload() }
                4 -> { Feed.audioOnly = !Feed.audioOnly; prefs.edit().putBoolean("audio", Feed.audioOnly).apply(); reload() }
                else -> return@setOnMenuItemClickListener false
            }
            grid.scrollToPosition(0); true
        }
        m.show()
    }

    /** Свои слова для скрытия — через запятую. */
    private fun hideWords() {
        val e = EditText(this).apply { setText(Hide.custom); hint = tr("например: cock, bbc"); setSingleLine() }
        android.app.AlertDialog.Builder(this).setTitle(tr("Скрывать ролики со словами")).setView(e)
            .setPositiveButton(tr("Сохранить")) { _, _ -> Hide.custom = e.text.toString().trim(); reload() }.setNegativeButton(tr("Отмена"), null).show()
    }

    private fun reload() = if (Feed.reddit) Feed.reddit(Feed.sub) else Feed.reset(Feed.text, Feed.order, Feed.author, Feed.mode, Feed.byTag && Feed.query == Feed.text)
    private val prefs by lazy { getSharedPreferences("loopa", MODE_PRIVATE) }

    /** Подсказки при вводе: через 350 мс после последней буквы. */
    private fun suggestLater(q: String) {
        val g = ++suggestGen
        if (q.isBlank()) { idleChips(); return }
        ui.postDelayed({
            if (g != suggestGen) return@postDelayed
            val rd = Feed.reddit
            thread {
                val r = runCatching { if (rd) Reddit.suggest(q.trim().removePrefix("r/")).map { (n, c) -> "r/$n" to c } else Api.suggest(q.trim()) }.getOrDefault(emptyList())
                ui.post { if (g == suggestGen) showChips(r) }
            }
        }, 350)
    }

    private fun showChips(list: List<Pair<String, Int>>) {
        chips.removeAllViews()
        list.take(30).forEach { (t, n) ->
            chips.addView(chip(if (n > 0) "$t · ${count(n.toLong())}" else t, CHIP) { if (Feed.reddit) { nav(); search.setText(""); openSub(t) } else { search.setText(t); search.setSelection(t.length); go(t, tag = true) } },
                LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(6) })
        }
    }

    /** Чипы без ввода: теги ленты, а на Reddit — «Подписки» и сохранённые сабреддиты (долгое нажатие — убрать). */
    private fun idleChips() {
        if (!Feed.reddit) { showChips(Feed.tags.map { it to 0 }); return }
        chips.removeAllViews()
        val all = listOf("") + savedSubs()
        all.forEach { s ->
            val on = s == Feed.sub
            chips.addView(chip(if (s.isEmpty()) "🏠 " + tr("Подписки") else "r/$s", if (on) ACCENT else CHIP) { nav(); openSub(s) }.apply {
                if (s.isNotEmpty()) setOnLongClickListener {
                    prefs.edit().putString("subs", savedSubs().filter { it != s }.joinToString("\n")).apply()
                    android.widget.Toast.makeText(this@MainActivity, tr("r/%s убран из списка", s), android.widget.Toast.LENGTH_SHORT).show(); idleChips(); true
                }
            }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(6) })
        }
    }

    private fun refresh() {
        userBtn.text = when { Api.loggedIn || Reddit.loggedIn || Ext.ALL.any(Ext::loggedIn) -> "👤✓"; Api.hadUser || Reddit.hadUser -> "👤!"; else -> "👤" }
        search.hint = when { Feed.reddit -> tr("Сабреддит: r/… (через пробел — несколько)"); Feed.ext -> tr("Поиск на %s", if (Feed.mode == "erome") "Erome" else "Eporner"); else -> tr("Поиск по тегам: blonde, POV, car…") }
        for (i in 0 until tabs.childCount) tabs.getChildAt(i).background = pill(if (TABS[i].second == Feed.mode && Feed.author == null && Feed.text.isEmpty()) ACCENT else CHIP)
        sortBtn.visibility = if (Feed.mode == null || Feed.reddit || Feed.mode == "foryou") View.VISIBLE else View.GONE
        filterBtn.visibility = View.VISIBLE
        when (Feed.error) {
            Api.LOGIN_EXPIRED -> if (!Renewer.running) renew(RgSite)
            Reddit.LOGIN_EXPIRED -> if (!Renewer.running) renew(Reddit.SITE)
            Reddit.LOGIN_NEEDED -> status.setOnClickListener { login(Reddit.SITE) }
            null -> {}
            else -> status.setOnClickListener { Feed.retry() }
        }
        val st = Feed.sort
        sortBtn.text = when {
            Feed.reddit -> "⇅ " + Reddit.sort(Feed.rorder).short
            Feed.mode == "foryou" && !st.dir -> "⇅ " + tr("Интересы")
            st.dir -> (if (Feed.desc) "↓ " else "↑ ") + st.short
            else -> "⇅ " + st.short
        }
        filterBtn.text = if (Feed.filtered || Hide.active) "⧩ •" else "⧩"
        filterBtn.background = pill(if (Feed.filtered || Hide.active) ACCENT else CHIP)
        status.text = buildString {
            when {
                Feed.mode == "foryou" -> append(tr("Для вас") + " — " + ForYou.sources.ifEmpty { listOf(tr("источники выключены")) }.joinToString(" + ") +
                    " · " + (if (Interests.empty) tr("лайкайте и досматривайте — подстроится") else tr("по вашим интересам")) +
                    ForYou.redditNote?.let { " · $it" }.orEmpty())
                Feed.mode == "liked" -> append(tr("Ваши лайки"))
                Feed.ext -> append((if (Feed.mode == "erome") "Erome" else "Eporner") + " · " + if (Feed.text.isNotEmpty()) tr("Поиск: «%s»", Feed.text) else tr("популярное"))
                Feed.reddit -> append("Reddit · " + when {
                    Feed.sub.isEmpty() -> tr("подписки") + Reddit.name.takeIf { it.isNotEmpty() }?.let { " u/$it" }.orEmpty()
                    Feed.sub.startsWith("u/") -> tr("посты %s", Feed.sub)
                    else -> Feed.sub.split("+").joinToString(" + ") { "r/$it" }
                } + " · " + Reddit.sort(Feed.rorder).label)
                Feed.author != null -> append(tr("Автор: %s  (назад — к ленте)", Feed.author))
                Feed.text.isNotEmpty() && !Feed.byTag -> append(tr("Поиск: «%s»", Feed.text) + (if (Feed.busy) " · " + tr("идёт поиск…") else "") + " · " + tr("по теме %s", Search.exact) +
                    Search.synonyms.takeIf { it.isNotEmpty() }?.let { " · " + tr("+ похожее:") + " " + it.take(4).joinToString(", ") }.orEmpty())
                Feed.text.isNotEmpty() && !Feed.byTag -> append(tr("Поиск: «%s»", Feed.query.ifEmpty { Feed.text }) + (if (Feed.query.isNotEmpty() && !Feed.query.equals(Feed.text, true)) " («${Feed.text}»)" else "") +
                    (if (Feed.query.isNotEmpty()) " · " + tr("точных совпадений: %s", Feed.exact) + (if (Feed.exact > 0) " " + tr("(они первыми)") else "") else ""))
                Feed.text.isNotEmpty() -> append(tr("Поиск по тегам:") + " " + Feed.query.ifEmpty { Feed.text }.split(",").joinToString(" + ") { "#$it" } + if (Feed.query.isNotEmpty() && !Feed.query.equals(Feed.text, true)) "  («${Feed.text}»)" else "")
                else -> append(Feed.sort.label)
            }
            if (Feed.filtered) append(" · " + tr("фильтр:") + " " + listOfNotNull(tr(Feed.DURATIONS.first { it.first == Feed.duration }.second).takeIf { Feed.duration != "any" }?.let(I18n::lower), tr("со звуком").takeIf { Feed.audioOnly }).joinToString(", "))
            if (Feed.items.isNotEmpty()) append(" · ${Feed.items.size}")
            if (Feed.busy && !(Feed.text.isNotEmpty() && !Feed.byTag)) append(" · " + tr("загружаю…"))
            Feed.error?.let { append(" · " + tr(it)) }
            if (Feed.retryIn > 0) append(" · " + tr("повтор через %s с (нажмите — сейчас)", Feed.retryIn))
            if (!Feed.busy && Feed.error == null && Feed.items.isEmpty() && (Feed.text.isNotEmpty() || Feed.reddit || Feed.ext || Feed.mode == "foryou"))
                append(" · " + if (Feed.reddit) tr("видео не нашлось — попробуйте другой сабреддит") else tr("ничего не нашлось"))
        }
        if (!Feed.busy) swipe.isRefreshing = false
        if (search.text.isBlank()) idleChips()
        adapter.notifyDataSetChanged()
        grid.post { live() }
    }

    private inner class GridAdapter : RecyclerView.Adapter<GridAdapter.H>() {
        inner class H(val box: FrameLayout, val img: ImageView, val dur: TextView, val src: TextView, val slot: GridPreview.Slot) : RecyclerView.ViewHolder(box)
        override fun onViewRecycled(h: H) { preview.stop(h.slot) }
        override fun getItemCount() = Feed.items.size
        override fun onCreateViewHolder(p: ViewGroup, t: Int): H {
            val img = ImageView(p.context).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(0xFF1A1D23.toInt()) }
            val dur = TextView(p.context).apply {
                setTextColor(Color.WHITE); textSize = 12f; setPadding(dp(6), dp(2), dp(6), dp(2)); background = pill(0xAA000000.toInt())
            }
            // откуда ролик: RG — RedGIFs, RD — Reddit
            val tex = android.view.TextureView(p.context).apply { alpha = 0f }   // живое превью поверх картинки
            val src = TextView(p.context).apply { setTextColor(Color.WHITE); textSize = 10f; typeface = android.graphics.Typeface.DEFAULT_BOLD; setPadding(dp(6), dp(1), dp(6), dp(1)) }
            val box = object : FrameLayout(p.context) {
                // ячейка 3:4 — видно больше роликов, чем при 9:16
                override fun onMeasure(w: Int, h: Int) { super.onMeasure(w, MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(w) * 4 / 3, MeasureSpec.EXACTLY)) }
            }.apply {
                addView(img, FrameLayout.LayoutParams(-1, -1))
                addView(tex, FrameLayout.LayoutParams(-1, -1))
                addView(dur, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply { setMargins(dp(6), dp(6), dp(6), dp(6)) })
                addView(src, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply { setMargins(dp(6), dp(6), dp(6), dp(6)) })
                layoutParams = RecyclerView.LayoutParams(-1, -2).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) }
                clipToOutline = true; background = pill(0xFF1A1D23.toInt(), 10f)
            }
            return H(box, img, dur, src, GridPreview.Slot(tex))
        }
        override fun onBindViewHolder(h: H, pos: Int) {
            val g = Feed.items[pos]
            preview.stop(h.slot); h.slot.pos = pos
            Thumbs.load(h.img, g.thumb)
            h.dur.text = (if (g.hasAudio) "🔊 " else "") + time(g.duration)
            h.src.text = mark(g); h.src.background = pill(markColor(g))
            // касание — приоритет загрузки превью этой ячейки
            h.box.setOnTouchListener { _, e -> if (e.actionMasked == android.view.MotionEvent.ACTION_DOWN) { preview.touched = h.slot; grid.post { live() } }; false }
            h.box.setOnClickListener { startActivity(Intent(this@MainActivity, PlayerActivity::class.java).putExtra("pos", h.bindingAdapterPosition)) }
            h.box.setOnLongClickListener { Downloads.get(this@MainActivity, g); Interests.download(g); true }
        }
    }

    private fun chip(t: String, color: Int, click: (View) -> Unit) = TextView(this).apply {
        text = t; setTextColor(Color.WHITE); textSize = 14f; setPadding(dp(12), dp(7), dp(12), dp(7)); background = pill(color); isSingleLine = true
        setOnClickListener(click)
    }

    private fun pill(c: Int, r: Float = 40f) = GradientDrawable().apply { setColor(c); cornerRadius = r * resources.displayMetrics.density }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()

    companion object {
        /** Источники «Для вас»: ключ доли, название, включён ли. */
        private val FY_SRC = listOf<Triple<String, String, () -> Boolean>>(
            Triple("rg", "RedGIFs") { ForYou.useRg }, Triple("rd", "Reddit") { ForYou.reddit },
            Triple("er", "Erome") { ForYou.useErome }, Triple("ep", "Eporner") { ForYou.useEporner })
        val TABS = listOf("♥ Для вас" to "foryou", "🔥 Популярное" to null, "Мои лайки" to "liked", "Reddit" to "reddit", "Erome" to "erome", "Eporner" to "eporner")
        /** Пункты меню языка: «auto», затем коды. */
        val LANG_IDS = listOf("auto") + I18n.LANGS.keys
        /** Как часто при прокрутке пересчитывать, какие превью играют, мс. */
        const val LIVE_SCROLL_MS = 350L
        val BG = 0xFF0E1015.toInt(); val ACCENT = 0xFF7C3AED.toInt(); val CHIP = 0xFF262B34.toInt()
        /** Метки источника: RedGIFs — малиновый, Reddit — фирменный оранжевый. */
        val RG_COLOR = 0xE0D6246E.toInt(); val RD_COLOR = 0xE0FF4500.toInt()
        private const val ER_COLOR = 0xE01E88E5.toInt(); private const val EP_COLOR = 0xE043A047.toInt()
        /** Метка источника на карточке и в плеере. */
        fun mark(g: Gif) = when { g.reddit -> "RD"; g.site == "erome" -> "ER"; g.site == "eporner" -> "EP"; else -> "RG" }
        fun markColor(g: Gif) = when { g.reddit -> RD_COLOR; g.site == "erome" -> ER_COLOR; g.site == "eporner" -> EP_COLOR; else -> RG_COLOR }
        fun time(s: Double) = "%d:%02d".format(s.toInt() / 60, s.toInt() % 60)
        fun count(n: Long) = when { n >= 1_000_000 -> "%.1fM".format(n / 1e6); n >= 1000 -> "%.0fK".format(n / 1e3); else -> "$n" }
    }
}
