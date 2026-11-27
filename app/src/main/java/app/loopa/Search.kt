package app.loopa

import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import kotlin.math.ln

/**
 * Поиск текстом с полным охватом и порядком по релевантности.
 *
 * Сбор (первая порция — широкая, параллельно):
 *  - основной запрос RedGIFs — 3 страницы (его текстовый поиск нечёткий, по теме бывает каждый пятый);
 *  - варианты написания: слитно/раздельно («bedpost» ↔ «bed post»);
 *  - синонимы — частые теги видео по запросу на Pornhub (открытое API для вебмастеров), только конкретные: без общих
 *    и возрастных, и не те, что на RedGIFs есть у сотен тысяч роликов (Ebony, Blowjob…);
 *  - близкие теги RedGIFs из подсказок (тоже без слишком общих);
 *  - Reddit: сабреддиты по запросу и синонимам (нишевые темы там есть), не дольше 6 с — опоздавшее в следующую порцию.
 * Дальше — следующие страницы основного запроса и по очереди источников.
 *
 * Оценка ролика ([score], уровни — в [Relevance.score]) одна для всех источников. Порядок в ленте — по уровням,
 * внутри уровня источники чередуются ([Relevance.order]); совсем мимо (0) в выдачу не идёт.
 */
object Search {
    private val pool = Executors.newFixedThreadPool(6)
    private const val GENERIC_COUNT = 100_000
    class Snap(val text: String, val alias: List<String>, val synonyms: List<String>, val tags: List<String>, val srcPage: Map<String, Int>,
               val turn: Int, val rdNext: String?, val rdSubs: List<String>?, val exact: Int, val strong: Int)

    /** Кэш «фраза → тег RedGIFs» живёт между поисками: сеть за подсказками не дёргаем заново. */
    private val tagCache = java.util.concurrent.ConcurrentHashMap<String, String>()
    @Volatile private var current = Session("")
    internal fun session() = current
    val exact get() = current.exact
    val synonyms get() = current.synonyms
    fun save() = current.save()
    fun score(g: Gif) = current.score(g)
    @Synchronized fun invalidate() { current.close(); current = Session("") }
    @Synchronized internal fun reset(q: String): Session {
        current.close()
        return Session(q).also { current = it }
    }
    @Synchronized fun load(s: Snap) { current.close(); current = Session(s.text).also { it.load(s) } }

    internal class Session(initial: String) {
    private val lifetime = RequestLifetime()
    fun close() = lifetime.cancel()
    private val text = initial
    /** Английский перевод русского запроса (null — запрос не русский): теги и названия на источниках английские. */
    private val en: String? = Thesaurus.translate(initial)
    @Volatile var synonyms: List<String> = emptyList(); private set
    private var tags: List<String> = emptyList()
    private val srcPage = HashMap<String, Int>()
    private var turn = 0
    @Volatile private var rdPending: java.util.concurrent.Future<List<Gif>>? = null
    @Volatile private var rdNext: String? = null
    private var rdSubs: List<String>? = null
    /** Сколько роликов прямо по теме (оценка ≥ 3) — для строки статуса. */
    @Volatile var exact = 0; private set
    @Volatile private var synReady = false
    /** Сколько роликов с точным совпадением (уровень ≥ 3) — после 20 совпадения только по синониму не показываем. */
    @Volatile private var strong = 0

    /** Полные фразы, если запрос — сокращение (atp → ass to pussy). */
    @Volatile private var alias: List<String> = Thesaurus.expandAbbr(initial)

    /** Состояние поиска для «Назад» ([Feed.back]): чтобы следующие порции той страницы шли с того же места. */
    @Synchronized fun save() = Snap(text, alias, synonyms, tags, HashMap(srcPage), turn, rdNext, rdSubs, exact, strong)
    @Synchronized fun load(s: Snap) {
        alias = s.alias; synonyms = s.synonyms; tags = s.tags; srcPage.clear(); srcPage += s.srcPage; turn = s.turn
        rdPending = null; rdNext = s.rdNext; rdSubs = s.rdSubs; exact = s.exact; strong = s.strong; synReady = true
    }


    /** Тег RedGIFs, совпадающий с фразой (без учёта регистра и пробелов), если он есть и не слишком общий. */
    private fun exactTag(phrase: String): String? = tagCache.getOrPut(norm(phrase)) {
        runCatching { Api.suggest(phrase) }.getOrNull()?.firstOrNull { norm(it.first) == norm(phrase) && it.second < GENERIC_COUNT * 5 }?.first.orEmpty()
    }.takeIf { it.isNotEmpty() }

    private fun norm(s: String) = Relevance.norm(s)
    private fun stem(w: String) = if (w.length > 4 && w.endsWith("s")) w.dropLast(1) else w

    /**
     * Порция поиска, живьём: каждый источник, как только ответил, отдаётся в [part] (ролики с оценкой) — выдача растёт
     * по ходу, не дожидаясь медленных. p = 1 — широкий сбор; дальше — следующие страницы. order — порядок RedGIFs.
     * Возвращает, сколько роликов пришло всего.
     */
    fun run(p: Int, order: String, reddit: Boolean, part: (List<Pair<Gif, Double>>) -> Unit): Int {
        lifetime.check()
        val q = text
        val cs = java.util.concurrent.ExecutorCompletionService<List<Gif>>(pool)
        val batch = ArrayList<Future<List<Gif>>>()
        var n = 0
        val successes = java.util.concurrent.atomic.AtomicInteger()
        val failures = java.util.concurrent.atomic.AtomicInteger()
        fun provider(task: () -> List<Gif>): List<Gif> {
            return try {
                lifetime.check()
                task().also { successes.incrementAndGet() }
            } catch (e: java.util.concurrent.CancellationException) { throw e }
            catch (e: InterruptedException) { Thread.currentThread().interrupt(); throw e }
            catch (e: Exception) {
                failures.incrementAndGet()
                android.util.Log.w("Loopa", "Search provider failed: ${e.javaClass.simpleName}")
                emptyList()
            }
        }
        fun submit(task: () -> List<Gif>) {
            lifetime.check()
            batch += lifetime.track(cs.submit { lifetime.check(); task() })
            n++
        }
        fun rg(query: String, byTag: Boolean, k: Int) { submit { provider { Api.page(query, order, k, byTag = byTag).gifs } } }
        try {
        if (p == 1) {
            synReady = false
            val nq = norm(q)
            (1..3).forEach { rg(q, false, it) }
            en?.let { e -> (1..3).forEach { rg(e, false, it) } }
            // сокращение: по полным фразам; если фраза — готовый тег RedGIFs («Ass to Pussy»), то по тегу (текстом его не найти)
            alias.forEach { a -> val t = exactTag(a); if (t != null) { rg(t, true, 1); rg(t, true, 2); rg(t, true, 3) } else { rg(a, false, 1); rg(a, false, 2) } }
            if (' ' in q.trim()) rg(nq, false, 1)                        // «bed post» → «bedpost»
            // синонимы и близкие теги ищутся, пока идёт основной запрос; Reddit — тоже параллельно
            submit { val syn = runCatching { synonymsFor(en ?: q) }.getOrDefault(emptyList<String>() to emptyList()); lifetime.check(); synchronized(this) { synonyms = syn.first; tags = syn.second }; synReady = true; emptyList() }
        } else {
            rg(q, false, p + 2)
            en?.let { rg(it, false, p + 2) }
            val src = synchronized(this) { (synonyms.take(4).map { it to false } + tags.take(3).map { it to true }).let { l -> l.getOrNull(turn++ % maxOf(1, l.size)) } }
            src?.let { (s, byTag) -> val k = synchronized(this) { ((srcPage[s] ?: 1) + 1).also { srcPage[s] = it } }; rg(s, byTag, k) }
        }
        if (reddit) submit { provider { redditPart(en ?: q, p) } }
        // сторонние сайты: английский перевод, если он есть, иначе исходный запрос
        for (s in ForYou.onSources) submit { provider { s.tracked(en ?: q, false, p).gifs } }
        var total = 0
        var synDone = p != 1
        if (p == 1) strong = 0
        val relCnt = HashMap<String, Int>()
        var relDone = false
        var i = 0
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45)
        while (i < n) {
            lifetime.check()
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) break
            val done = cs.poll(minOf(remaining, TimeUnit.MILLISECONDS.toNanos(200)), TimeUnit.NANOSECONDS) ?: continue
            val got = runCatching { done.get() }.getOrDefault(emptyList())
            lifetime.check()
            i++
            // синонимы готовы — сразу отправить поиски по ним (первая порция)
            if (!synDone && synReady) {
                synDone = true
                // синоним — готовый тег RedGIFs: по тегу, иначе текстом
                synonyms.take(10).forEach { syn -> val t = exactTag(syn); if (t != null) rg(t, true, 1) else rg(syn, false, 1) }; tags.take(3).forEach { rg(it, true, 1) }
            }
            if (got.isEmpty()) continue
            total += got.size
            // оценка одна для всех источников (и сторонних сайтов тоже): по тегам, названию и сабреддиту
            val scored = got.map { it to score(it) }
            // теги, частые у роликов прямо по теме (оценка ≥ 8), — тоже искать (у «midget» — «Little People»)
            if (p == 1 && !relDone) {
                scored.filter { Relevance.level(it.second) >= 5 }.forEach { (g, _) -> g.tags.forEach { t -> relCnt[t] = (relCnt[t] ?: 0) + 1 } }
                val rel = relCnt.filter { (t, c) -> c >= 2 && t !in tags && !Api.isGeneric(t) && !Hide.hidden(Gif("", "", "", "", 0, 0, 0.0, "", listOf(t), false, 0, 0)) }
                    .entries.sortedByDescending { it.value }.map { it.key }.take(3)
                if (rel.size >= 2 || i >= 3 && rel.isNotEmpty()) {
                    relDone = true
                    synchronized(this) { tags = (tags + rel).distinct() }
                    rel.forEach { rg(it, true, 1) }
                    // Do not write query-derived metadata to system logs.
                }
            }
            exact += scored.count { it.second >= 6 }
            strong += scored.count { Relevance.exact(it.second) }
            // мимо (0) не показываем; похожее (синонимы, корень, часть слов) — показываем, но ниже точного
            part(scored.filter { Relevance.visible(it.second) })
        }
        lifetime.check()
        if (successes.get() == 0 && (failures.get() > 0 || i < n))
            throw java.io.IOException("Search providers unavailable")
        android.util.Log.i("Loopa", "Search page finished: $total rows; provider failures: ${failures.get()}")
        return total
        } finally { batch.forEach { if (!it.isDone) it.cancel(true) } }
    }

    /** Оценка ролика запросу — уровни в [Relevance.score]. */
    fun score(g: Gif) = Relevance.score(g, text, alias, synonyms)

    /**
     * Синонимы (фразы для текстового поиска) и близкие теги RedGIFs: из связей Pornhub и подсказок; общие (у тега на
     * RedGIFs больше [GENERIC_COUNT] роликов) отбрасываем — они тянут посторонние ролики.
     */
    private fun synonymsFor(q: String): Pair<List<String>, List<String>> {
        val qn = norm(q)
        val qWords = Api.words(q).map(::stem)
        val count = HashMap<String, Int>()
        fun rgCount(t: String): Int? = count[t] ?: runCatching { Api.suggest(t) }.getOrNull()?.firstOrNull { norm(it.first) == norm(t) }?.second?.also { count[t] = it }
        val (ph, total) = Api.phRelatedCounts(q)
        val roots = qWords.map { it.replace(Regex("(ers|er|ing|es)$"), "").take(5) }.filter { it.length >= 3 } + qn.take(5)
        // синоним — пересекается с запросом по слову («stick shift», «fucking bedpost») или встречается хотя бы в
        // четверти найденных видео («dwarf» у «midget»); и у него на RedGIFs нет тега или тег не слишком общий
        // свой словарь — первым: у нишевых тем на сайтах ни тегов, ни внятных связей нет
        val thes = Thesaurus.of(q)
        val syn = (thes + ph.filter { (t, c) -> norm(t) != qn && (roots.any { r -> norm(t).contains(r) } || total > 0 && c * 10 >= total * 4) }
            .map { it.first }.filter { t -> (rgCount(t) ?: 0) < GENERIC_COUNT }).distinctBy { norm(it) }.filter { norm(it) != qn }.take(10)
        // близкие теги RedGIFs: подсказки по запросу и словам, похожие по написанию, не общие
        val own = Api.expand(q).filter { t -> (rgCount(t) ?: 0) in 1 until GENERIC_COUNT && qWords.any { w -> norm(t).contains(w.take(4)) } }
        return syn to own.take(4)
    }

    /** Reddit: сабреддиты по запросу, словам и синонимам; не ждём дольше 6 с — опоздавшее в следующую порцию. */
    private fun redditPart(q: String, p: Int): List<Gif> {
        val f = rdPending ?: lifetime.track(java.util.concurrent.CompletableFuture.supplyAsync<List<Gif>> {
            lifetime.check()
            runCatching {
                val subs = rdSubs ?: (Reddit.searchSubs(q, 3) + (Api.words(q).take(2) + synonyms.take(2)).flatMap { runCatching { Reddit.searchSubs(it, 1) }.getOrDefault(emptyList()) })
                    .distinct().filter { !it.lowercase().contains("audio") && !it.lowercase().contains("writing") }.take(5).also { rdSubs = it }
                if (subs.isEmpty() || p > 1 && rdNext == null) emptyList()
                else Reddit.page(subs.joinToString("+"), "top-month", rdNext).let { (g, next) -> rdNext = next; g }
            }.getOrThrow()
        })
        // живьём ждать можно дольше: остальные источники уже показаны
        return try { f.get(25, TimeUnit.SECONDS).also { rdPending = null } }
        catch (e: java.util.concurrent.TimeoutException) { rdPending = f; throw e }
        catch (e: Exception) { rdPending = null; throw e }
    }
    }
}
