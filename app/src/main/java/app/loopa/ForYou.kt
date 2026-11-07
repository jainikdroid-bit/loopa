package app.loopa

import android.content.Context
import android.content.SharedPreferences
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import kotlin.math.ln

/**
 * Лента «Для вас»: RedGIFs + Reddit по профилю [Interests].
 *
 * Кандидаты:
 *  - RedGIFs: рекомендации самого RedGIFs (если вошли), сочетание тегов из лайков и досмотров, любимый тег, а по очереди —
 *    прошлый поиск или любимый автор (все параллельно, см. [rg]);
 *  - Reddit: «горячее» смеси из ~6 сабреддитов — сабреддиты из профиля, найденные по верхним тегам и словам
 *    (поиск сабреддитов Reddit, 18+ вперёд, кэш на сутки), подписки; плюс один случайный для разведки. Свои рекомендации
 *    Reddit 18+ не показывает, поэтому подбираем сами. Лента смеси кончилась — набираем новую смесь.
 * Ранжирование внутри каждого источника: сглаженный вес по профилю + немного популярности + шум (чтобы не застревать
 * в одном и том же). Просмотренные раньше и повторы (тот же ролик RedGIFs из поста Reddit) выкидываем.
 * Erome и Eporner ([ext]): личные ленты (если вошли) и поиск по интересам.
 * Смешивание: у каждого источника свой запас и доля ([weight]), порция — по долям равномерно ([weighted]).
 */
object ForYou {
    private val pool = Executors.newFixedThreadPool(3)
    private const val WAIT_RD = 6_000L
    private var prefs: SharedPreferences? = null
    private var rgPage = 0
    private val tagPage = HashMap<String, Int>()
    private var subs: List<String> = emptyList()
    private var after: String? = null
    private var redditDry = 0
    /** Чередование источников Reddit: смесь сабреддитов / поиск постов по всему Reddit по интересу. */
    private var turn = 0
    private val searchAfter = HashMap<String, String?>()
    private var rr = 0
    private val subCursor = HashMap<String, String?>()
    /** Адреса уже показанных в этой ленте — один ролик и с RedGIFs, и из Reddit не должен прийти дважды. */
    private val shown = HashSet<String>()


    fun init(ctx: Context) { if (prefs == null) prefs = ctx.applicationContext.getSharedPreferences("loopa", Context.MODE_PRIVATE) }
    var mixReddit: Boolean
        get() = prefs?.getBoolean("fy_reddit", true) ?: true
        set(v) { prefs?.edit()?.putBoolean("fy_reddit", v)?.apply() }
    val reddit get() = mixReddit && Reddit.hadUser
    /** Источники «Для вас» и общего поиска (галочки в меню 👤; вход при этом не снимается). */
    var useRg: Boolean
        get() = prefs?.getBoolean("fy_rg", true) ?: true
        set(v) { prefs?.edit()?.putBoolean("fy_rg", v)?.apply() }
    var useErome: Boolean
        get() = prefs?.getBoolean("fy_erome", true) ?: true
        set(v) { prefs?.edit()?.putBoolean("fy_erome", v)?.apply() }
    var useEporner: Boolean
        get() = prefs?.getBoolean("fy_eporner", true) ?: true
        set(v) { prefs?.edit()?.putBoolean("fy_eporner", v)?.apply() }
    /** Подписи источников для строки статуса. */
    val sources get() = listOfNotNull("RedGIFs".takeIf { useRg }, "Reddit".takeIf { reddit }, "Erome".takeIf { useErome }, "Eporner".takeIf { useEporner })
    /** Почему Reddit не подмешался (для строки статуса); null — всё в порядке. */
    @Volatile var redditNote: String? = null; private set

    @Synchronized fun reset() { rgBuf.clear(); rdBuf.clear(); erBuf.clear(); epBuf.clear(); dead.clear(); extra = 0; pendingRd = null; redditNote = null; turn = 0; rr = 0; subCursor.clear(); searchAfter.clear(); rgPage = 0; tagPage.clear(); subs = emptyList(); after = null; redditDry = 0; shown.clear(); extTurn.clear() }

    /** Следующая порция (в фоне). Пусто — источники кончились. Ошибка — только если не ответил ни один. */
    /** Порция Reddit, не успевшая к прошлой выдаче, — войдёт в следующую. */
    @Volatile private var pendingRd: java.util.concurrent.Future<List<Gif>>? = null

    /** Запасы кандидатов по источникам: RedGIFs приносит ~160 за раз, Reddit ~10–20 — выдаём по долям, остальное ждёт. */
    private val rgBuf = ArrayList<Gif>()
    private val rdBuf = ArrayList<Gif>()
    private val erBuf = ArrayList<Gif>()
    private val epBuf = ArrayList<Gif>()
    /** Запас источника, больше которого не догружаем (новое ждёт, пока выдадим старое). */
    private const val STOCK = 40
    /** Роликов в порции. */
    private const val PORTION = 30

    /** Доли источников (меню сортировки «Для вас»): вес 1 — мало, 2 — средне, 3 — много; относительные. */
    val WEIGHTS = listOf(1 to "Мало", 2 to "Средне", 3 to "Много")
    fun weight(src: String): Int = prefs?.getInt("w_$src", 2) ?: 2
    fun setWeight(src: String, w: Int) { prefs?.edit()?.putInt("w_$src", w)?.apply() }

    private fun page1(): List<Gif> {
        val rdOn = reddit && redditDry < 3
        fun low(b: ArrayList<Gif>) = synchronized(this) { b.size } < STOCK
        val rgF = if (useRg && low(rgBuf)) pool.submit<List<Gif>> { rg() } else null
        val erF = if (useErome && low(erBuf)) pool.submit<List<Gif>> { ext(Ext.EROME) } else null
        val epF = if (useEporner && low(epBuf)) pool.submit<List<Gif>> { ext(Ext.EPORNER) } else null
        // Reddit (страница сайта) бывает медленным — не успел за WAIT_RD — войдёт в следующую порцию
        val rdF = pendingRd ?: if (rdOn && low(rdBuf)) pool.submit<List<Gif>> { redditBatch() } else null
        val a = rgF?.let { runCatching { it.get() } }
        val e1 = erF?.let { runCatching { it.get() } }
        val e2 = epF?.let { runCatching { it.get() } }
        val b = rdF?.let { f ->
            try { runCatching { f.get(WAIT_RD, java.util.concurrent.TimeUnit.MILLISECONDS) }.also { pendingRd = null } }
            catch (e: Exception) { null }
        }?.let { r -> if (r.exceptionOrNull() is java.util.concurrent.TimeoutException) { pendingRd = rdF; null } else r }
        b?.exceptionOrNull()?.let { e -> val c = unwrap(e); redditNote = "Reddit: " + (c.message ?: c.javaClass.simpleName); android.util.Log.w("Loopa", "Для вас: Reddit не ответил", c) }
            ?: run { if (b != null) redditNote = if (b.getOrNull().isNullOrEmpty()) redditNote ?: tr("Reddit: пусто") else null }
        return synchronized(this) {
            a?.getOrNull()?.let { rgBuf += rank(it) }
            b?.getOrNull()?.let { rdBuf += rank(it) }
            e1?.getOrNull()?.let { erBuf += rank(it) }
            e2?.getOrNull()?.let { epBuf += rank(it) }
            val tried = listOfNotNull(a, b, e1, e2)
            if (rgBuf.isEmpty() && rdBuf.isEmpty() && erBuf.isEmpty() && epBuf.isEmpty() && tried.isNotEmpty() && tried.all { it.isFailure })
                throw unwrap(tried.first().exceptionOrNull()!!)
            val out = weighted(listOf(rgBuf to weight("rg"), rdBuf to weight("rd"), erBuf to weight("er"), epBuf to weight("ep")))
            android.util.Log.i("Loopa", "Для вас: порция ${out.size}, в запасе RG ${rgBuf.size} RD ${rdBuf.size} ER ${erBuf.size} EP ${epBuf.size}")
            out
        }
    }

    /**
     * Порция по долям: на каждое место — источник, у которого выдано меньше всего относительно его веса (равномерно по
     * ленте). Источник пуст (не успел, кончился) — его место занимают остальные. Выданное убирается из запасов.
     */
    private fun weighted(src: List<Pair<ArrayList<Gif>, Int>>): List<Gif> {
        val taken = IntArray(src.size)
        val out = ArrayList<Gif>(PORTION)
        while (out.size < PORTION) {
            var best = -1; var bestR = Double.MAX_VALUE
            src.forEachIndexed { i, (buf, w) -> if (buf.isNotEmpty() && w > 0) { val r = (taken[i] + 1.0) / w; if (r < bestR) { bestR = r; best = i } } }
            if (best < 0) break
            out += src[best].first.removeAt(0); taken[best]++
        }
        return out
    }

    /** Порция; пустая (всё уже видели, источники исчерпаны) — ещё пара попыток с другими источниками, прежде чем сказать «кончилось». */
    fun page(): List<Gif> {
        repeat(3) { val l = page1(); if (l.isNotEmpty()) return l }
        return emptyList()
    }

    private fun unwrap(e: Throwable) = (e as? ExecutionException)?.cause ?: e

    /**
     * RedGIFs за порцию — несколько источников сразу (параллельно), чтобы лента была и точной, и разнообразной:
     *  - рекомендации самого RedGIFs (если вошли);
     *  - сочетание тегов «a,b» из лайков и досмотров ([Interests.pickPair]) — самое точное;
     *  - любимый тег;
     *  - по очереди: прошлый поиск (запрос целиком, только ролики со всеми его словами) или любимый автор.
     * Источник, что вернул пусто, на эту ленту исчерпан — дальше берём другие.
     */
    private fun rg(): List<Gif> {
        val jobs = ArrayList<java.util.concurrent.Future<List<Gif>>>()
        fun src(f: () -> List<Gif>) { jobs += rgPool.submit<List<Gif>> { f() } }
        if (Api.loggedIn) src { Api.page("", "trending", synchronized(this) { ++rgPage }, mode = "foryou").gifs }
        // шире выборка: два разных сочетания и два разных тега за порцию (по весу, не только самые верхние)
        val pr1 = Interests.pickPair(dead); pr1?.let { pr -> src { byTag(pr) } }
        Interests.pickPair(dead + listOfNotNull(pr1))?.let { pr -> src { byTag(pr) } }
        val t1 = Interests.pickTag(dead); t1?.let { t -> src { byTag(t) } }
        Interests.pickTag(dead + listOfNotNull(t1))?.let { t -> src { byTag(t) } }
        if (synchronized(this) { extra++ } % 2 == 0) Interests.pickQuery(dead)?.let { q -> src { byQuery(q) } }
        else Interests.pickUser(dead)?.let { u -> src { byUser(u) } }
        if (jobs.isEmpty()) return Api.page("", "trending", synchronized(this) { ++rgPage }).gifs
        val res = jobs.map { runCatching { it.get() } }
        if (res.all { it.isFailure }) throw unwrap(res.first().exceptionOrNull()!!)
        return res.flatMap { it.getOrDefault(emptyList()) }
    }
    private val rgPool = Executors.newFixedThreadPool(6)

    /** Сторонний сайт: по очереди личная лента (если вошли) и поиск по интересу; пусто — источник на эту ленту исчерпан. */
    private fun ext(s: Ext.Site): List<Gif> {
        val feeds = if (Ext.loggedIn(s)) Ext.feeds(s).filter { "${s.id}:$it" !in dead } else emptyList()
        val n = synchronized(this) { extTurn.merge(s.id, 1, Int::plus)!! }
        val feed = feeds.takeIf { it.isNotEmpty() && n % 2 == 1 }?.random()
        if (feed != null && s === Ext.EROME) {
            val k = "${s.id}:$feed"
            val g = runCatching { Erome.feed(feed, next(k)).gifs }.getOrDefault(emptyList())
            if (g.isEmpty()) dead += k
            android.util.Log.i("Loopa", "Для вас: ${s.title} лента $feed — ${g.size}")
            if (g.isNotEmpty()) return g
        }
        val term = (if (n % 3 == 0) Interests.pickQuery(dead) else null) ?: Interests.pickTag(dead) ?: Interests.top("kw", 5).randomOrNull()?.first ?: ""
        val k = "${s.id}:q:$term"
        if (k in dead) return emptyList()
        val g = if (s === Ext.EROME) Erome.page(term, false, next(k)).gifs else Eporner.page(term, false, next(k)).gifs
        if (g.isEmpty()) dead += k
        android.util.Log.i("Loopa", "Для вас: ${s.title} «$term» — ${g.size}")
        return g
    }
    private val extTurn = HashMap<String, Int>()
    /** Источники (тег, сочетание, запрос, автор), что кончились или пусты, — на эту ленту. */
    private val dead: MutableSet<String> = java.util.Collections.synchronizedSet(HashSet<String>())
    private var extra = 0

    private fun next(key: String) = synchronized(this) { ((tagPage[key] ?: 0) + 1).also { tagPage[key] = it } }

    private fun byTag(t: String) = Api.page(t, "trending", next(t), byTag = true).gifs.also {
        if (it.isEmpty()) dead += t
        android.util.Log.i("Loopa", "Для вас: ${if (',' in t) "сочетание" else "тег"} «$t» — ${it.size}")
    }

    /** Прошлый поиск: текстовый поиск RedGIFs нечёткий — берём только ролики со всеми словами запроса. */
    private fun byQuery(q: String): List<Gif> {
        val ws = Api.words(q).map { it.trimEnd('s') }.filter { it.length >= 2 }
        val all = Api.page(q, "trending", next("q:$q"), byTag = false).gifs
        if (all.isEmpty()) dead += q
        return all.filter { g -> val t = (g.tags.joinToString(" ") + " " + g.description).lowercase().replace(" ", ""); ws.all { it in t } }
            .also { android.util.Log.i("Loopa", "Для вас: поиск «$q» — ${it.size} из ${all.size}") }
    }

    private fun byUser(u: String) = runCatching { Api.page("", "latest", next("u:$u"), author = u).gifs }.getOrDefault(emptyList())
        .also { if (it.isEmpty()) dead += u; android.util.Log.i("Loopa", "Для вас: автор $u — ${it.size}") }

    /** Каждый второй раз — поиск по всему Reddit по любимому тегу/слову (новые сабреддиты сами попадают в профиль). */
    private fun redditBatch(): List<Gif> {
        if (turn++ % 2 == 1) {
            val term = Interests.pickTag() ?: Interests.top("kw", 5).randomOrNull()?.first
            if (term != null && !(searchAfter.containsKey(term) && searchAfter[term] == null)) {
                // поиск постов API может резать (403) — тогда просто смесь сабреддитов
                runCatching { Reddit.searchPosts(term, searchAfter[term]) }.onSuccess { (g, next) ->
                    synchronized(this) { searchAfter[term] = next }
                    android.util.Log.i("Loopa", "Для вас: поиск по Reddit «$term» — ${g.size}")
                    if (g.isNotEmpty()) return g
                }.onFailure { synchronized(this) { searchAfter[term] = null } }
            }
        }
        return mixBatchMany()
    }

    private fun mixBatch(): List<Gif> {
        if (subs.isEmpty()) subs = chooseSubs()
        if (subs.isEmpty()) { redditDry = 3; redditNote = tr("Reddit: не нашлось сабреддитов по интересам"); android.util.Log.w("Loopa", "Для вас: нет сабреддитов"); return emptyList() }
        // по одному сабреддиту за раз, по кругу (страница сайта смеси через «+» не открывает); у каждого — свой курсор
        val sub = synchronized(this) { subs[rr++ % subs.size] }
        if (subCursor.containsKey(sub) && subCursor[sub] == null) {             // этот кончился
            synchronized(this) { subs = subs - sub; if (subs.isEmpty()) subCursor.clear() }
            return if (subs.isEmpty()) emptyList() else mixBatch()
        }
        android.util.Log.i("Loopa", "Для вас: Reddit r/$sub (смесь ${subs.joinToString("+")})")
        val (gifs, next) = Reddit.page(sub, "hot", subCursor[sub])
        synchronized(this) {
            subCursor[sub] = next
            redditDry = if (gifs.isEmpty()) redditDry + 1 else 0
            // почти одни картинки (меньше 3 видео со страницы) — этот сабреддит больше не берём, место — следующему
            if (gifs.size < 3) { lowYield += sub.lowercase(); subs = subs - sub }
        }
        android.util.Log.i("Loopa", "Для вас: r/$sub дал ${gifs.size} видео")
        return gifs
    }

    /** Сабреддиты, где видео почти нет, — на эту сессию. */
    private val lowYield = HashSet<String>()

    /** До 16 видео за заход: несколько сабреддитов подряд (каждый — своя страница). */
    private fun mixBatchMany(): List<Gif> {
        val out = ArrayList<Gif>()
        var tries = 0
        while (out.size < 16 && tries++ < 4) {
            val g = runCatching { mixBatch() }.getOrElse { if (out.isEmpty()) throw it else emptyList() }
            out += g
            if (subs.isEmpty()) break
        }
        return out
    }

    /** Смесь сабреддитов: профиль + найденные по тегам и словам + подписки; верхние 5 по весу и один случайный. */
    private fun chooseSubs(): List<String> {
        val w = HashMap<String, Double>()
        fun add(s: String, x: Double) { val k = s.lowercase(); w[k] = (w[k] ?: 0.0) + x }
        Interests.top("sub", 10).forEach { (s, x) -> add(s, x) }
        var lookups = 0
        (Interests.top("tag", 5) + Interests.top("kw", 3)).forEach { (term, x) ->
            val found = Interests.mapped(term) ?: if (lookups++ < 3) runCatching { Reddit.searchSubs(term) }
                .onFailure { android.util.Log.w("Loopa", "поиск сабреддитов «$term»", it) }.getOrNull()?.also { Interests.map(term, it) } else null
            found?.forEach { add(it, x * 0.6) }
        }
        runCatching { Reddit.subscribedNsfw() }.onFailure { android.util.Log.w("Loopa", "подписки Reddit", it) }.getOrDefault(emptyList()).forEach { add(it, 1.0) }
        // аудио- и текстовые сабреддиты видео не дают; закрытые/карантин (403) — не брать
        // только сабреддиты 18+: обычные (из старых поисков, подписок) видео 18+ не дают
        val ranked = w.entries.sortedByDescending { it.value }.map { it.key }.filter { !Reddit.isBad(it) && it !in lowYield && !it.contains("audio") && !it.contains("writing") }
            .take(12).filter { Reddit.isNsfw(it) }
        val pick = ranked.take(5).toMutableList()
        ranked.drop(5).randomOrNull()?.let { pick += it }   // разведка: вдруг понравится
        return pick
    }

    /** Порядок внутри источника: вкус, популярность, шум; без просмотренного и повторов. */
    private fun rank(l: List<Gif>): List<Gif> = l
        .filter { !Interests.isSeen(it.id) && shown.add(it.hd) }
        .map { it to (Interests.damp(Interests.score(it)) + 0.08 * ln(1.0 + it.likes.coerceAtLeast(0)) + Math.random() * 0.5) }
        .sortedByDescending { it.second }.map { it.first }
}
