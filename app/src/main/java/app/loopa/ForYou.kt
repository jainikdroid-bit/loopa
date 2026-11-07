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
 * Смешивание: доля Reddit [share] равномерно по ленте.
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

    val SHARES = listOf(0.25 to "Мало (1 из 4)", 0.34 to "Треть", 0.5 to "Поровну", 0.67 to "Много (2 из 3)")

    fun init(ctx: Context) { if (prefs == null) prefs = ctx.applicationContext.getSharedPreferences("loopa", Context.MODE_PRIVATE) }
    var mixReddit: Boolean
        get() = prefs?.getBoolean("fy_reddit", true) ?: true
        set(v) { prefs?.edit()?.putBoolean("fy_reddit", v)?.apply() }
    var share: Double
        get() = prefs?.getFloat("fy_share", 0.5f)?.toDouble() ?: 0.5
        set(v) { prefs?.edit()?.putFloat("fy_share", v.toFloat())?.apply() }
    val reddit get() = mixReddit && Reddit.hadUser
    /** Почему Reddit не подмешался (для строки статуса); null — всё в порядке. */
    @Volatile var redditNote: String? = null; private set

    @Synchronized fun reset() { rgBuf.clear(); rdBuf.clear(); dead.clear(); extra = 0; pendingRd = null; redditNote = null; turn = 0; rr = 0; subCursor.clear(); searchAfter.clear(); rgPage = 0; tagPage.clear(); subs = emptyList(); after = null; redditDry = 0; shown.clear() }

    /** Следующая порция (в фоне). Пусто — источники кончились. Ошибка — только если не ответил ни один. */
    /** Порция Reddit, не успевшая к прошлой выдаче, — войдёт в следующую. */
    @Volatile private var pendingRd: java.util.concurrent.Future<List<Gif>>? = null

    /**
     * Запас кандидатов: RedGIFs за раз приносит до ~160 роликов (несколько источников по 40), Reddit — ~10–20 и медленнее.
     * Если выдавать всё сразу, доля Reddit тонет, поэтому RedGIFs выдаём столько, сколько положено по доле [share] к
     * пришедшему Reddit, остальное ждёт в запасе следующих порций (новый RedGIFs не грузим, пока запас большой).
     */
    private val rgBuf = ArrayList<Gif>()
    private val rdBuf = ArrayList<Gif>()
    private const val RG_STOCK = 40
    /** RedGIFs в порции, даже если Reddit не успел, — лента не должна вставать. */
    private const val RG_MIN = 6

    private fun page1(): List<Gif> {
        val rdOn = reddit && redditDry < 3
        val rgF = if (synchronized(this) { rgBuf.size } < RG_STOCK || !rdOn) pool.submit<List<Gif>> { rg() } else null
        // Reddit (страница сайта) бывает медленным — не успел за WAIT_RD — войдёт в следующую порцию
        val rdF = pendingRd ?: if (rdOn) pool.submit<List<Gif>> { redditBatch() } else null
        val a = rgF?.let { runCatching { it.get() } }
        val b = rdF?.let { f ->
            try { runCatching { f.get(WAIT_RD, java.util.concurrent.TimeUnit.MILLISECONDS) }.also { pendingRd = null } }
            catch (e: Exception) { null }
        }?.let { r -> if (r.exceptionOrNull() is java.util.concurrent.TimeoutException) { pendingRd = rdF; null } else r }
        b?.exceptionOrNull()?.let { e -> val c = unwrap(e); redditNote = "Reddit: " + (c.message ?: c.javaClass.simpleName); android.util.Log.w("Loopa", "Для вас: Reddit не ответил", c) }
            ?: run { if (b != null) redditNote = if (b.getOrNull().isNullOrEmpty()) redditNote ?: tr("Reddit: пусто") else null }
        return synchronized(this) {
            a?.getOrNull()?.let { rgBuf += rank(it) }
            b?.getOrNull()?.let { rdBuf += rank(it) }
            if (rgBuf.isEmpty() && rdBuf.isEmpty() && a?.isFailure == true && (b == null || b.isFailure)) throw unwrap(a.exceptionOrNull()!!)
            // Reddit выключен или кончился — весь запас RedGIFs; иначе RedGIFs по доле к пришедшему Reddit
            val stillRd = reddit && redditDry < 3
            val nRg = if (!stillRd || share <= 0.0) rgBuf.size
                else maxOf(RG_MIN, kotlin.math.ceil(rdBuf.size * (1 - share) / share).toInt()).coerceAtMost(rgBuf.size)
            val g = rgBuf.take(nRg); val d = ArrayList(rdBuf)
            repeat(nRg) { rgBuf.removeAt(0) }; rdBuf.clear()
            android.util.Log.i("Loopa", "Для вас: порция RG ${g.size} + RD ${d.size}, в запасе RG ${rgBuf.size}")
            mix(g, d, if (d.isEmpty()) 0.0 else d.size.toDouble() / (g.size + d.size))
        }
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
        Interests.pickPair(dead)?.let { pr -> src { byTag(pr) } }
        Interests.pickTag(dead)?.let { t -> src { byTag(t) } }
        if (synchronized(this) { extra++ } % 2 == 0) Interests.pickQuery(dead)?.let { q -> src { byQuery(q) } }
        else Interests.pickUser(dead)?.let { u -> src { byUser(u) } }
        if (jobs.isEmpty()) return Api.page("", "trending", synchronized(this) { ++rgPage }).gifs
        val res = jobs.map { runCatching { it.get() } }
        if (res.all { it.isFailure }) throw unwrap(res.first().exceptionOrNull()!!)
        return res.flatMap { it.getOrDefault(emptyList()) }
    }
    private val rgPool = Executors.newFixedThreadPool(4)
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

    /** Чередование: на каждой позиции берём Reddit, если его пока меньше доли [share]; остатки — в конец. */
    private fun mix(a: List<Gif>, b: List<Gif>, share: Double): List<Gif> {
        val out = ArrayList<Gif>(a.size + b.size)
        var i = 0; var j = 0
        while (i < a.size || j < b.size) {
            val wantB = j < b.size && (i >= a.size || j < (out.size + 1) * share)
            if (wantB) out += b[j++] else out += a[i++]
        }
        return out
    }
}
