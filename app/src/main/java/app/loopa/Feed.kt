package app.loopa

import android.os.Handler
import android.os.Looper
import kotlin.concurrent.thread

/**
 * Сортировка выдачи. api — порядок на сервере (search: score, trending, top7, top28, top, latest; у автора ещё oldest);
 * local — досортировка на телефоне (длительность, лайки, просмотры, рейтинг: сервер так не умеет), по убыванию;
 * dir — у сортировки есть направление ↑/↓ ([Feed.desc]).
 */
class Sort(val id: String, private val labelRu: String, private val shortRu: String, val api: String, val local: Comparator<Gif>? = null, val dir: Boolean = false) {
    val label get() = tr(labelRu)
    val short get() = tr(shortRu)
}

/**
 * Текущая выдача (общая для сетки и плеера): запрос, сортировка, фильтры, автор, загруженные ролики. Подгрузка страниц —
 * в фоне, слушатели получают уведомление на главном потоке. Сбой сети не тупик: повтор сам — через 2, 4, 8… 30 с и
 * сразу, как только вернулась связь ([Net.online]).
 */
object Feed {
    val SORTS = listOf(
        Sort("score", "По релевантности", "Релевантность", "score"),
        Sort("trending", "Популярное сейчас", "Популярное", "trending"),
        Sort("top7", "Лучшее за неделю", "Неделя", "top7"),
        Sort("top28", "Лучшее за месяц", "Месяц", "top28"),
        Sort("top", "Лучшее за всё время", "Всё время", "top"),
        Sort("date", "Дата", "Дата", "latest", compareByDescending { it.created }, dir = true),
        Sort("duration", "Длительность", "Длительность", "top", compareByDescending { it.duration }, dir = true),
        Sort("likes", "Лайки", "Лайки", "top", compareByDescending { it.likes }, dir = true),
        Sort("views", "Просмотры", "Просмотры", "top", compareByDescending { it.views }, dir = true),
        Sort("rating", "Рейтинг (лайки / просмотры)", "Рейтинг", "top", compareByDescending { it.likes.toDouble() / maxOf(it.views, 200L) }, dir = true),
    )
    /** Направление сортировки: true — по убыванию (больше, длиннее, новее — сначала). */
    var desc = true
    /** Подмешивать Reddit в поиск. */
    var searchReddit = true
    /** Фильтр длительности: id → подпись и границы, с. */
    val DURATIONS = listOf(Triple("any", "Любая длительность", 0.0..Double.MAX_VALUE), Triple("short", "До 15 с", 0.1..15.0),
        Triple("mid", "15–60 с", 15.0..60.0), Triple("long", "Дольше минуты", 60.0..Double.MAX_VALUE))
    /** Досортировка на телефоне идёт по первым [POOL] роликам — дальше страницы сортируются каждая в себе. */
    private const val POOL = 120

    var text = ""; private set          // запрос, как ввёл пользователь
    var query = ""; private set         // что ушло в API (теги или текст)
    /** Поиск по тегам (tags=) или по тексту (query=); tagOnly — нажали тег: строго по нему, без разбора. */
    var byTag = false; private set
    private var tagOnly = false
    /** Сколько роликов содержат все слова запроса (в описании, тегах, имени автора) — они идут первыми. */
    var exact = 0; private set
    var order = "trending"; private set
    var duration = "any"
    var audioOnly = false
    var author: String? = null; private set
    /** foryou / liked / reddit / erome / eporner / null (популярное или поиск). */
    var mode: String? = null; private set
    /** Reddit: сабреддиты через «+» (пусто — главная с подписками, «u/имя» — посты пользователя), сортировка, курсор. */
    var sub = ""; private set
    var rorder = "hot"
    private var after: String? = null
    val reddit get() = mode == "reddit"
    /** Лента стороннего сайта (Erome, Eporner): свой поиск, сортировка — сайта. */
    val ext get() = mode == "erome" || mode == "eporner"
    val items = ArrayList<Gif>()
    var tags: List<String> = emptyList(); private set
    var error: String? = null; private set
    /** Через сколько секунд следующий автоповтор (0 — не ждём). */
    var retryIn = 0; private set
    private var retryAt = 0L
    private var fails = 0
    private var page = 0
    private var pages = 1
    private var loading = false
    private var gen = 0
    private val ui = Handler(Looper.getMainLooper())
    val listeners = LinkedHashSet<() -> Unit>()

    val canMore get() = !loading && page < pages
    val busy get() = loading
    val sort get() = SORTS.firstOrNull { it.id == order } ?: SORTS[1]
    val filtered get() = duration != "any" || audioOnly
    private val range get() = DURATIONS.first { it.first == duration }.third

    init { Net.online += { if (error != null) retry() } }

    /** Сортировка, выбранная в прошлый раз (без загрузки). */
    fun restore(order: String) {
        // старые id (до 1.5): длинные/короткие, новые/старые
        when (order) { "long" -> { this.order = "duration"; desc = true }; "short" -> { this.order = "duration"; desc = false }
            "latest" -> { this.order = "date"; desc = true }; "oldest" -> { this.order = "date"; desc = false }
            else -> if (SORTS.any { it.id == order }) this.order = order }
    }

    /** Досортировка на телефоне с учётом направления; «дата ↓» — порядок сервера, «дата ↑» у автора — сервер (oldest). */
    private fun localCmp(): Comparator<Gif>? {
        val s = sort
        // поиск собирается из многих источников — дату сортируем сами
        if (s.id == "date" && text.isNotBlank() && author == null && mode == null) return if (desc) compareByDescending { it.created } else compareBy { it.created }
        if (s.id == "date" && (desc || author != null)) return null
        val c = s.local ?: return null
        return if (desc) c else c.reversed()
    }

    fun reset(text: String, order: String, author: String? = null, mode: String? = null, tag: Boolean = false) {
        this.text = text; this.order = order; this.author = author; this.mode = mode; query = ""; tagOnly = tag; byTag = tag; exact = 0
        scoreOf.clear()
        items.clear(); page = 0; pages = 1; after = null; loading = false; error = null; fails = 0; chain = 0; stopRetry(); gen++
        if (mode == "foryou") ForYou.reset()
        // первый показ ленты после запуска — сразу прошлая лента с диска (без уже просмотренного), свежее допишется
        stale.clear()
        cacheKey()?.takeIf { restored.add(it) }?.let { k ->
            val old = FeedCache.load(k).filter { !Hide.hidden(it) && !(mode == "foryou" && Interests.isSeen(it.id)) }
            items += old; old.forEach { stale += it.id }
            if (old.isNotEmpty()) android.util.Log.i("Loopa", "лента $k: из кэша ${old.size}")
        }
        changed(); more()
    }

    /** Ленты, которые запоминаем между запусками: «Для вас» и «Популярное» (без поиска, автора и фильтров). */
    private fun cacheKey(): String? = when {
        text.isNotBlank() || author != null || filtered -> null
        mode == "foryou" -> "foryou"
        mode == null -> "home-$order"
        else -> null
    }
    /** Какие ленты уже показывали из кэша в этом запуске (обновление потянув — уже только свежее). */
    private val restored = HashSet<String>()
    /** Ролики из прошлой ленты, показанные до загрузки свежих. */
    private val stale = HashSet<String>()
    /** В ленте сейчас только прошлая выдача — свежее ещё грузится. */
    val fromCache get() = stale.isNotEmpty() && items.all { it.id in stale }

    /** Лента Reddit: сабреддит(ы), пользователь или главная; sort — id из [Reddit.SORTS]. */
    fun reddit(sub: String, sort: String = rorder) { this.sub = sub; rorder = sort; reset("", order, mode = "reddit") }

    /** Повторить сейчас (связь вернулась или нажали «повторить»). */
    fun retry() { stopRetry(); error = null; fails = 0; if (canMore) more() else changed() }

    private fun stopRetry() { ui.removeCallbacks(tick); retryIn = 0; retryAt = 0 }

    /** Отсчёт до автоповтора — раз в секунду обновляет подпись. */
    private val tick: Runnable = object : Runnable {
        override fun run() {
            retryIn = ((retryAt - System.currentTimeMillis() + 999) / 1000).toInt()
            if (retryIn <= 0) { retryIn = 0; error = null; more(); return }
            changed(); ui.postDelayed(this, 1000)
        }
    }

    /** Сервер по сортировке: у автора нет релевантности, в общем поиске нет «сначала старые»; для поиска текстом основа — релевантность. */
    private fun apiOrder(): String {
        val s = sort
        return when {
            author != null && s.id == "date" -> if (desc) "latest" else "oldest"
            // у автора «популярное сейчас», «неделя» и «месяц» почти всегда пусты (сервер отдаёт 0, если за неделю/месяц
            // у него ничего не было) — тогда его лучшее за всё время
            author != null -> when (s.api) { "score" -> "latest"; "trending", "top7", "top28" -> "top"; else -> s.api }
            s.id == "date" -> "latest"
            s.local != null && text.isNotBlank() -> "score"
            else -> s.api
        }
    }

    fun more() {
        if (!canMore) return
        loading = true
        val g = gen; val p = page + 1; val ord = apiOrder()
        if (mode == "foryou") {
            // «Для вас» — RedGIFs + Reddit по профилю интересов; пусто — источники кончились
            thread {
                val r = runCatching { ForYou.page() }
                ui.post {
                    if (g != gen) return@post
                    loading = false
                    r.onSuccess { add("", false, p, Page(it, if (it.isEmpty()) p else p + 1, emptyList())) }.onFailure { fail(it) }
                    changed()
                }
            }
            return
        }
        if (reddit) {
            val s = sub; val o = rorder; val a = after
            thread {
                val r = runCatching { Reddit.page(s, o, a) }
                ui.post {
                    if (g != gen) return@post
                    loading = false
                    r.onSuccess { (gifs, next) -> after = next; add("", false, p, Page(gifs, if (next != null) p + 1 else p, emptyList())) }.onFailure { fail(it) }
                    changed()
                }
            }
            return
        }
        if (ext) {
            val m = mode; val t = text; val fresh = sort.id == "date" && desc
            thread {
                val r = runCatching { if (m == "erome") Erome.page(t, fresh, p) else Eporner.page(t, fresh, p) }
                ui.post {
                    if (g != gen) return@post
                    loading = false
                    r.onSuccess { add("", false, p, it) }.onFailure { fail(it) }
                    changed()
                }
            }
            return
        }
        if (text.isNotBlank() && author == null && mode == null && !tagOnly) {
            // поиск текстом — живьём: каждый источник, как ответил, сразу в выдачу на своё место по релевантности ([Search])
            val t = text
            thread {
                if (p == 1) Search.reset(t)
                val r = runCatching { Search.run(p, ord, searchReddit && Reddit.hadUser) { part -> ui.post { if (g == gen) { addLive(part); changed() } } } }
                ui.post {
                    if (g != gen) return@post
                    loading = false
                    r.onSuccess { query = t; byTag = false; page = p; pages = if (it > 0) p + 1 else p; error = null; fails = 0 }.onFailure { fail(it) }
                    changed()
                    // мало прошло фильтры — сразу следующая порция
                    if (r.isSuccess && items.size < 20 && page < pages && p < 4) more()
                }
            }
            return
        }
        thread {
            val r = runCatching {
                // запрос → теги (если он целиком из тегов) или текст, как на сайте
                var bt = byTag
                val q = when {
                    author != null || mode != null || text.isBlank() -> ""
                    p > 1 && query.isNotEmpty() -> query
                    tagOnly -> text
                    else -> text.trim().let { t -> Api.toTags(t)?.also { bt = true } ?: t.also { bt = false } }
                }
                Triple(q, bt, Api.page(q, ord, p, author, mode, bt))
            }
            ui.post {
                if (g != gen) return@post
                loading = false
                r.onSuccess { (q, bt, pg) -> add(q, bt, p, pg) }.onFailure { fail(it) }
                changed()
            }
        }
    }

    private fun add(q: String, bt: Boolean, p: Int, pg: Page) {
        query = q; byTag = bt; page = p; pages = pg.pages; error = null; fails = 0
        if (pg.tags.isNotEmpty() && p == 1) tags = pg.tags
        val seen = items.mapTo(HashSet()) { it.id }
        val same = items.mapNotNullTo(HashSet()) { twin(it) }
        val range = range
        // одинаковые ролики: тот же id, тот же файл или перезалив (тот же размер кадра и длительность до сотых секунды)
        val fresh = pg.gifs.filter { seen.add(it.id) && seen.add(it.hd) && twin(it)?.let { k -> same.add(k) } != false }
            .filter { duration == "any" || it.duration in range }.filter { !audioOnly || it.hasAudio }.filter { !Hide.hidden(it) }
        val cmp = if (mode == null || mode == "foryou") localCmp() else null
        if (cmp != null) {
            // досортировка: первые POOL роликов — целиком, дальше — каждая страница в себе (сетка выше не прыгает)
            if (items.size < POOL) { items += fresh; items.sortWith(cmp) } else items += fresh.sortedWith(cmp)
        } else {
            // поиск текстом: ролики со всеми словами запроса — первыми (сайт добивает выдачу похожим)
            val w = if (!bt && q.isNotEmpty()) Api.words(q) else emptyList()
            fun hit(g: Gif) = w.isNotEmpty() && (g.description + " " + g.tags.joinToString(" ") + " " + g.user).lowercase().let { t -> w.all { it in t } }
            val hits = fresh.filter(::hit)
            exact += hits.size
            val at = items.indexOfFirst { !hit(it) }.let { if (it < 0) items.size else it }
            items.addAll(at, hits); items += fresh.filterNot(::hit)
        }
        // запомнить ленту к следующему запуску: свежее — первым, за ним остаток прошлой
        if (p <= 3) cacheKey()?.let { k -> FeedCache.save(k, items.filter { it.id !in stale } + items.filter { it.id in stale }) }
        // мало нового (реклама, фильтры) или копим пул для досортировки — сразу следующая страница
        val need = fresh.size < 8 && (pg.gifs.isEmpty() || filtered || Hide.active) || cmp != null && items.size < POOL
        // предел подряд: фильтр может отсеивать почти всё — не выкачивать сотни страниц
        chain = if (need) chain + 1 else 0
        if (need && page < pages && chain <= if (items.size < 20) 8 else 3) ui.post { more() }
    }
    private var chain = 0

    /** Оценка релевантности уже показанных роликов поиска — новые встают на своё место. */
    private val scoreOf = HashMap<String, Double>()

    /** Часть живого поиска: без дублей и скрытого, по фильтрам; по релевантности — на место, иначе — по выбранной сортировке. */
    private fun addLive(part: List<Pair<Gif, Double>>) {
        val seen = HashSet<String>(); items.forEach { seen += it.id; seen += it.hd }
        val same = items.mapNotNullTo(HashSet()) { twin(it) }
        val range = range
        val fresh = part.filter { (x, _) -> seen.add(x.id) && seen.add(x.hd) && twin(x)?.let { k -> same.add(k) } != false }
            .filter { (x, _) -> (duration == "any" || x.duration in range) && (!audioOnly || x.hasAudio) && !Hide.hidden(x) }
        if (fresh.isEmpty()) return
        val cmp = localCmp()
        // по релевантности: из одинаково подходящих выше тот, что во вкусе и качественнее ([Ranker.searchBonus])
        val ranked = if (cmp != null) fresh else fresh.map { (x, sc) -> x to sc + Ranker.searchBonus(Interests.score(x), x.tags.size, x.likes, x.views) }
        ranked.forEach { scoreOf[it.first.id] = it.second }
        if (cmp != null) { items += fresh.map { it.first }; items.sortWith(cmp) }
        else ranked.sortedByDescending { it.second }.forEach { (x, sc) ->
            val at = items.indexOfFirst { (scoreOf[it.id] ?: 0.0) < sc }.let { if (it < 0) items.size else it }
            items.add(at, x)
        }
        exact = Search.exact
    }

    /** Ключ перезалива: размер кадра + длительность до 0,01 с (у разных роликов совпадает редко); null — длительность неизвестна. */
    private fun twin(g: Gif) = if (g.duration > 0.5 && g.w > 16) "${g.w}x${g.h}@${Math.round(g.duration * 100)}" else null

    private fun fail(e: Throwable) {
        error = when {
            e.message == Api.LOGIN_EXPIRED || e.message == Reddit.LOGIN_EXPIRED || e.message == Reddit.LOGIN_NEEDED -> e.message
            e is HttpError -> e.message
            Net.transient(e) -> NO_NET
            else -> e.message ?: e.javaClass.simpleName
        }
        if (!Net.transient(e)) return
        // нестабильная сеть: повторять самим, с растущей паузой
        val wait = minOf(30, 2 shl minOf(fails, 4)); fails++
        retryAt = System.currentTimeMillis() + wait * 1000L
        ui.removeCallbacks(tick); tick.run()
    }

    private fun changed() = listeners.toList().forEach { it() }

    /** Ролик из «Похожих» — сразу за pos; уже есть в ленте — его место. */
    fun insertAfter(pos: Int, g: Gif): Int {
        items.indexOfFirst { it.id == g.id }.takeIf { it >= 0 }?.let { return it }
        val at = (pos + 1).coerceAtMost(items.size); items.add(at, g); changed(); return at
    }

    /** Номер ролика, на котором закрыли плеер, — сетка прокручивается к нему; -1 — нет. */
    var lastSeen = -1

    /** Снимок выдачи для «Назад»: всё нужное, чтобы вернуть ту же страницу без перезагрузки, и где была прокрутка. */
    class Snap(
        val text: String, val query: String, val byTag: Boolean, val tagOnly: Boolean, val exact: Int, val order: String, val desc: Boolean,
        val author: String?, val mode: String?, val sub: String, val rorder: String, val after: String?, val items: List<Gif>,
        val tags: List<String>, val page: Int, val pages: Int, val scoreOf: Map<String, Double>, val search: Search.Snap?, val pos: Int,
    )

    /** Предыдущие страницы (последняя — сверху); не больше [HISTORY]. */
    private val history = ArrayDeque<Snap>()
    private const val HISTORY = 15
    val canBack get() = history.isNotEmpty()

    /** Запомнить текущую выдачу перед переходом; pos — первый видимый ролик. Пустую или ту же страницу не запоминаем. */
    fun push(pos: Int) {
        if (items.isEmpty() && !loading) return
        val textSearch = text.isNotBlank() && author == null && mode == null && !tagOnly
        history.addLast(Snap(text, query, byTag, tagOnly, exact, order, desc, author, mode, sub, rorder, after, ArrayList(items), tags,
            // первая порция ещё грузилась, но что-то уже показано — считать её загруженной (дальше — следующие)
            // (порцию, что грузилась, — потом догрузить)
            maxOf(page, if (items.isNotEmpty()) 1 else 0), if (loading) maxOf(pages, page + 2) else pages, HashMap(scoreOf), if (textSearch) Search.save() else null, pos))
        while (history.size > HISTORY) history.removeFirst()
    }

    /** Вернуть предыдущую страницу; null — истории нет. Возвращает позицию прокрутки. */
    fun back(): Int? {
        val s = history.removeLastOrNull() ?: return null
        stopRetry(); gen++
        text = s.text; query = s.query; byTag = s.byTag; tagOnly = s.tagOnly; exact = s.exact; order = s.order; desc = s.desc
        author = s.author; mode = s.mode; sub = s.sub; rorder = s.rorder; after = s.after; tags = s.tags
        items.clear(); items += s.items; page = s.page; pages = maxOf(s.pages, s.page); loading = false; error = null; fails = 0; chain = 0
        scoreOf.clear(); scoreOf += s.scoreOf
        s.search?.let(Search::load)
        // пусто (ушли, пока грузилось) — загрузить заново
        if (items.isEmpty()) { page = 0; pages = 1; if (mode == "foryou") ForYou.reset() }
        changed(); if (items.isEmpty()) more()
        return s.pos
    }
}
