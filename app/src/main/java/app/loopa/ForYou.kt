package app.loopa

import android.content.Context
import android.content.SharedPreferences
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors

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
    /** Потоков столько, чтобы RedGIFs, Reddit и все сайты шли разом: ждать очереди за медленным сайтом незачем. */
    private val pool = Executors.newCachedThreadPool()
    private const val WAIT_RD = 6_000L
    private const val WAIT_EXT = 15_000L
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
    /** Ключи галочек Erome и Eporner остались прежними (у пользователей не слетают); у остальных сайтов — use_{id}. */
    private fun useKey(s: Source) = when (s.id) { "erome" -> "fy_erome"; "eporner" -> "fy_eporner"; else -> "use_${s.id}" }
    /** Включён ли сторонний сайт (галочка в меню 👤): ключ старый — по умолчанию включён, как было до реестра. */
    fun uses(s: Source): Boolean = prefs?.getBoolean(useKey(s), if (s.id == "erome" || s.id == "eporner") true else s.default) ?: s.default
    fun setUses(s: Source, on: Boolean) { prefs?.edit()?.putBoolean(useKey(s), on)?.apply() }
    /** Включённые сторонние сайты по порядку вкладок. */
    val onSources get() = Sources.ALL.filter { uses(it) }
    /** Подписи источников для строки статуса. */
    val sources get() = listOfNotNull("RedGIFs".takeIf { useRg }, "Reddit".takeIf { reddit }) + onSources.map { it.title }
    /** Почему Reddit не подмешался (для строки статуса); null — всё в порядке. */
    @Volatile var redditNote: String? = null; private set

    @Synchronized fun reset() { rgBuf.clear(); rdBuf.clear(); extBuf.clear(); dead.clear(); extra = 0; pendingRd = null; redditNote = null; turn = 0; rr = 0; subCursor.clear(); searchAfter.clear(); rgPage = 0; tagPage.clear(); subs = emptyList(); after = null; redditDry = 0; shown.clear(); extTurn.clear() }

    /** Следующая порция (в фоне). Пусто — источники кончились. Ошибка — только если не ответил ни один. */
    /** Порция Reddit, не успевшая к прошлой выдаче, — войдёт в следующую. */
    @Volatile private var pendingRd: java.util.concurrent.Future<List<Gif>>? = null

    /** Запасы кандидатов по источникам: RedGIFs приносит ~160 за раз, Reddit ~10–20 — выдаём по долям, остальное ждёт. */
    private val rgBuf = ArrayList<Gif>()
    private val rdBuf = ArrayList<Gif>()
    /** Запасы сторонних сайтов по id. */
    private val extBuf = HashMap<String, ArrayList<Gif>>()
    private fun bufOf(id: String) = extBuf.getOrPut(id) { ArrayList() }
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
        val extF = onSources.mapNotNull { s -> if (low(synchronized(this) { bufOf(s.id) })) s to pool.submit<List<Gif>> { ext(s) } else null }
        // Reddit (страница сайта) бывает медленным — не успел за WAIT_RD — войдёт в следующую порцию
        val rdF = pendingRd ?: if (rdOn && low(rdBuf)) pool.submit<List<Gif>> { redditBatch() } else null
        val a = rgF?.let { runCatching { it.get() } }
        // сторонний сайт не ответил за WAIT_EXT (блок, капча, медленная сеть) — порция без него, ответ отменяем
        val until = System.currentTimeMillis() + WAIT_EXT
        val extR = extF.map { (s, f) -> s to runCatching { f.get(maxOf(1L, until - System.currentTimeMillis()), java.util.concurrent.TimeUnit.MILLISECONDS) }.onFailure { f.cancel(true) } }
        val b = rdF?.let { f ->
            try { runCatching { f.get(WAIT_RD, java.util.concurrent.TimeUnit.MILLISECONDS) }.also { pendingRd = null } }
            catch (e: Exception) { null }
        }?.let { r -> if (r.exceptionOrNull() is java.util.concurrent.TimeoutException) { pendingRd = rdF; null } else r }
        b?.exceptionOrNull()?.let { e -> val c = unwrap(e); redditNote = "Reddit: " + (c.message ?: c.javaClass.simpleName); android.util.Log.w("Loopa", "Для вас: Reddit не ответил", c) }
            ?: run { if (b != null) redditNote = if (b.getOrNull().isNullOrEmpty()) redditNote ?: tr("Reddit: пусто") else null }
        return synchronized(this) {
            a?.getOrNull()?.let { rgBuf += rank(it) }
            b?.getOrNull()?.let { rdBuf += rank(it) }
            extR.forEach { (s, r) -> r.getOrNull()?.let { bufOf(s.id) += rank(it) } }
            val tried = listOfNotNull(a, b) + extR.map { it.second }
            if (rgBuf.isEmpty() && rdBuf.isEmpty() && extBuf.values.all { it.isEmpty() } && tried.isNotEmpty() && tried.all { it.isFailure })
                throw unwrap(tried.first().exceptionOrNull()!!)
            val out = weighted(listOf(rgBuf to weight("rg"), rdBuf to weight("rd")) + onSources.map { bufOf(it.id) to weight(it.badge.lowercase()) })
            android.util.Log.i("Loopa", "Для вас: порция ${out.size}, в запасе RG ${rgBuf.size} RD ${rdBuf.size} " + extBuf.entries.joinToString(" ") { "${it.key} ${it.value.size}" })
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
        val got = res.flatMap { it.getOrDefault(emptyList()) }
        // узкие интересы дали мало (редкие теги, сочетания) — добрать популярным, чтобы лента не пустела
        if (got.size >= MIN_RG) return got
        android.util.Log.i("Loopa", "Для вас: RedGIFs по интересам мало (${got.size}) — добираю популярным")
        return got + runCatching { Api.page("", "trending", synchronized(this) { ++rgPage }).gifs }.getOrDefault(emptyList())
    }
    private val rgPool = Executors.newFixedThreadPool(6)
    /** Меньше стольких роликов RedGIFs за порцию — добор популярным. */
    private const val MIN_RG = 20

    /** Сторонний сайт: по очереди личная лента (если вошли) и поиск по интересу; пусто — источник на эту ленту исчерпан. */
    private fun ext(src: Source): List<Gif> {
        val s = Ext.site(src.id)
        val feeds = if (s != null && Ext.loggedIn(s)) Ext.feeds(s).filter { "${s.id}:$it" !in dead } else emptyList()
        val n = synchronized(this) { extTurn.merge(src.id, 1, Int::plus)!! }
        val feed = feeds.takeIf { it.isNotEmpty() && n % 2 == 1 }?.random()
        if (feed != null && src === Erome) {
            val k = "${src.id}:$feed"
            val g = runCatching { Erome.feed(feed, next(k)).gifs }.getOrDefault(emptyList())
            if (g.isEmpty()) dead += k
            android.util.Log.i("Loopa", "Для вас: ${src.title} лента $feed — ${g.size}")
            if (g.isNotEmpty()) return g
        }
        val term = (if (n % 3 == 0) Interests.pickQuery(dead) else null) ?: Interests.pickTag(dead) ?: Interests.top("kw", 5).randomOrNull()?.first ?: ""
        val k = "${src.id}:q:$term"
        if (k in dead) return emptyList()
        val g = src.page(term, false, next(k)).gifs
        if (g.isEmpty()) dead += k
        android.util.Log.i("Loopa", "Для вас: ${src.title} «$term» — ${g.size}")
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

    /**
     * Порядок внутри источника ([Ranker]): вкус с поправкой на число тегов, качество (лайки и их доля от просмотров),
     * шум; без просмотренного, повторов и явно не во вкусе; один автор и одна тема — не подряд.
     */
    private fun rank(l: List<Gif>): List<Gif> {
        val taste = HashMap<String, Double>()
        val scored = l.filter { !Interests.isSeen(it.id) && shown.add(it.hd) }
            .map { g -> val t = Interests.score(g); taste[g.id] = Interests.damp(t * Ranker.norm(g.tags.size)); g to Ranker.value(t, g.tags.size, g.likes, g.views, Math.random()) }
        return Ranker.diversify(Ranker.dropDisliked(scored) { taste[it.id] ?: 0.0 })
    }
}
