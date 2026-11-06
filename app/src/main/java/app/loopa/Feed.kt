package app.loopa

import android.os.Handler
import android.os.Looper
import kotlin.concurrent.thread

/**
 * Сортировка выдачи. api — порядок на сервере (search: score, trending, top7, top28, top, latest; у автора ещё oldest);
 * local — досортировка на телефоне (длительность, лайки, просмотры, рейтинг: сервер так не умеет), по убыванию;
 * dir — у сортировки есть направление ↑/↓ ([Feed.desc]).
 */
class Sort(val id: String, val label: String, val short: String, val api: String, val local: Comparator<Gif>? = null, val dir: Boolean = false)

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
    /** foryou / liked / reddit / null (популярное или поиск). */
    var mode: String? = null; private set
    /** Reddit: сабреддиты через «+» (пусто — главная с подписками, «u/имя» — посты пользователя), сортировка, курсор. */
    var sub = ""; private set
    var rorder = "hot"
    private var after: String? = null
    val reddit get() = mode == "reddit"
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
        if (s.id == "date" && (desc || author != null)) return null
        val c = s.local ?: return null
        return if (desc) c else c.reversed()
    }

    fun reset(text: String, order: String, author: String? = null, mode: String? = null, tag: Boolean = false) {
        this.text = text; this.order = order; this.author = author; this.mode = mode; query = ""; tagOnly = tag; byTag = tag; exact = 0
        rdPending = null
        items.clear(); page = 0; pages = 1; after = null; loading = false; error = null; fails = 0; chain = 0; stopRetry(); gen++
        if (mode == "foryou") ForYou.reset()
        changed(); more()
    }

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
            author != null -> if (s.api == "score") "latest" else s.api
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
                var pg = Api.page(q, ord, p, author, mode, bt)
                // похожие теги (варианты написания, подсказки, часто встречающиеся вместе) — по одному на порцию
                if (text.isNotBlank() && author == null && mode == null && !tagOnly && g == gen) runCatching {
                    if (p == 1) { exp = Api.expand(text); expPage.clear(); expI = 0 }
                    if (p == 1 || exp.size < 8) exp = (exp + Api.related(pg.gifs, text)).distinct().take(10)
                    android.util.Log.i("Loopa", "поиск «$text»: похожие ${exp}")
                    exp.getOrNull(expI++ % maxOf(exp.size, 1))?.let { t ->
                        val n = (expPage[t] ?: 0) + 1; expPage[t] = n
                        val more = Api.page(t, ord, n, byTag = true)
                        pg = Page(pg.gifs + more.gifs, maxOf(pg.pages, p + 1), pg.tags)
                    }
                }
                // поиск: подмешать Reddit — посты с сабреддитов, найденных по запросу (поиск постов Reddit режет)
                // Reddit не задерживает выдачу: ждём не больше 6 с, опоздавшее — в следующую порцию
                val rd = if (searchReddit && text.isNotBlank() && author == null && mode == null && Reddit.hadUser && g == gen) {
                    val f = rdPending ?: rdPool.submit<List<Gif>> { runCatching { redditSearch(text, p) }.onFailure { android.util.Log.w("Loopa", "поиск на Reddit", it) }.getOrDefault(emptyList()) }
                    try { f.get(6, java.util.concurrent.TimeUnit.SECONDS).also { rdPending = null } } catch (e: java.util.concurrent.TimeoutException) { rdPending = f; emptyList() } catch (e: Exception) { rdPending = null; emptyList() }
                } else emptyList()
                Triple(q, bt, if (rd.isEmpty()) pg else Page(interleave(pg.gifs, rd), maxOf(pg.pages, p + if (rdNext != null) 1 else 0), pg.tags))
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
        // мало нового (реклама, фильтры) или копим пул для досортировки — сразу следующая страница
        val need = fresh.size < 8 && (pg.gifs.isEmpty() || filtered || Hide.active) || cmp != null && items.size < POOL
        // предел подряд: фильтр может отсеивать почти всё — не выкачивать сотни страниц
        chain = if (need) chain + 1 else 0
        if (need && page < pages && chain <= if (items.size < 20) 8 else 3) ui.post { more() }
    }
    private var chain = 0

    // ---- похожие теги в поиске ----
    @Volatile private var exp: List<String> = emptyList()
    private val expPage = java.util.concurrent.ConcurrentHashMap<String, Int>()
    @Volatile private var expI = 0
    /** Похожие теги текущего поиска — для чипов и строки статуса. */
    val related get() = exp

    // ---- Reddit в поиске ----
    @Volatile private var rdSubs: List<String>? = null
    @Volatile private var rdPending: java.util.concurrent.Future<List<Gif>>? = null
    private val rdPool = java.util.concurrent.Executors.newSingleThreadExecutor()
    @Volatile private var rdNext: String? = null
    @Volatile private var rdFor = ""

    /** Сабреддиты по запросу (по фразе и по словам) → их посты; курсор — круг по сабреддитам ([Reddit.page]). */
    private fun redditSearch(text: String, p: Int): List<Gif> {
        if (rdFor != text || p == 1) { rdFor = text; rdSubs = null; rdNext = null }
        val subs = rdSubs ?: (Reddit.searchSubs(text, 3) + (Api.words(text).take(2) + exp.take(3)).flatMap { w -> runCatching { Reddit.searchSubs(w, 1) }.getOrDefault(emptyList()) })
            .distinct().take(5).also { rdSubs = it; android.util.Log.i("Loopa", "поиск «$text» на Reddit: $it") }
        if (subs.isEmpty() || p > 1 && rdNext == null) return emptyList()
        val rs = when (order) { "date" -> "new"; "top7" -> "top-week"; "top28" -> "top-month"; "top" -> "top-all"; else -> "hot" }
        val (g, next) = Reddit.page(subs.joinToString("+"), rs, if (p == 1) null else rdNext)
        rdNext = next
        return g
    }

    /** Вставить посты Reddit в выдачу RedGIFs: каждый третий. */
    private fun interleave(a: List<Gif>, b: List<Gif>): List<Gif> {
        val out = ArrayList<Gif>(a.size + b.size); var j = 0
        a.forEachIndexed { i, x -> out += x; if (i % 2 == 1 && j < b.size) out += b[j++] }
        while (j < b.size) out += b[j++]
        return out
    }

    /** Ключ перезалива: размер кадра + длительность до 0,01 с (у разных роликов совпадает редко); null — длительность неизвестна. */
    private fun twin(g: Gif) = if (g.duration > 0.5 && g.w > 16) "${g.w}x${g.h}@${Math.round(g.duration * 100)}" else null

    private fun fail(e: Throwable) {
        error = when {
            e.message == Api.LOGIN_EXPIRED || e.message == Reddit.LOGIN_EXPIRED || e.message == Reddit.LOGIN_NEEDED -> e.message
            e is HttpError -> e.message
            Net.transient(e) -> "нет связи"
            else -> e.message ?: e.javaClass.simpleName
        }
        if (!Net.transient(e)) return
        // нестабильная сеть: повторять самим, с растущей паузой
        val wait = minOf(30, 2 shl minOf(fails, 4)); fails++
        retryAt = System.currentTimeMillis() + wait * 1000L
        ui.removeCallbacks(tick); tick.run()
    }

    private fun changed() = listeners.toList().forEach { it() }
}
