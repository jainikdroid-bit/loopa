package app.loopa

import android.os.Handler
import android.os.Looper
import kotlin.concurrent.thread

/**
 * Сортировка выдачи. api — порядок на сервере (search: score, trending, top7, top28, top, latest; у автора ещё oldest);
 * local — досортировка на телефоне (длительность, лайки, просмотры, рейтинг: сервер так не умеет).
 */
class Sort(val id: String, val label: String, val short: String, val api: String, val local: Comparator<Gif>? = null, val authorOnly: Boolean = false)

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
        Sort("latest", "Сначала новые", "Новые", "latest"),
        Sort("oldest", "Сначала старые", "Старые", "oldest", authorOnly = true),
        Sort("long", "Сначала длинные", "Длинные", "top", compareByDescending { it.duration }),
        Sort("short", "Сначала короткие", "Короткие", "top", compareBy { if (it.duration > 0) it.duration else Double.MAX_VALUE }),
        Sort("likes", "Больше лайков", "Лайки", "top", compareByDescending { it.likes }),
        Sort("views", "Больше просмотров", "Просмотры", "top", compareByDescending { it.views }),
        Sort("rating", "Выше рейтинг (лайки / просмотры)", "Рейтинг", "top", compareByDescending { it.likes.toDouble() / maxOf(it.views, 200L) }),
    )
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
    fun restore(order: String) { if (SORTS.any { it.id == order }) this.order = order }

    fun reset(text: String, order: String, author: String? = null, mode: String? = null, tag: Boolean = false) {
        this.text = text; this.order = order; this.author = author; this.mode = mode; query = ""; tagOnly = tag; byTag = tag; exact = 0
        items.clear(); page = 0; pages = 1; after = null; loading = false; error = null; fails = 0; chain = 0; stopRetry(); gen++
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
            author != null -> if (s.api == "score") "latest" else s.api
            s.authorOnly -> "latest"
            s.local != null && text.isNotBlank() -> "score"
            else -> s.api
        }
    }

    fun more() {
        if (!canMore) return
        loading = true
        val g = gen; val p = page + 1; val ord = apiOrder()
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
        val range = range
        val fresh = pg.gifs.filter { seen.add(it.id) }
            .filter { duration == "any" || it.duration in range }.filter { !audioOnly || it.hasAudio }
        val cmp = if (mode == null) sort.local else null
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
        val need = fresh.size < 8 && (pg.gifs.isEmpty() || filtered) || cmp != null && items.size < POOL
        // предел подряд: фильтр может отсеивать почти всё — не выкачивать сотни страниц
        chain = if (need) chain + 1 else 0
        if (need && page < pages && chain <= if (items.size < 20) 8 else 3) ui.post { more() }
    }
    private var chain = 0

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
