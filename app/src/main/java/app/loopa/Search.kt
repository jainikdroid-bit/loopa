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
 * Оценка ролика ([score]): вся фраза — 8, каждое слово запроса в тегах — 4, в названии/описании/сабреддите — 3, все
 * слова многословного запроса вместе — ещё 5, синоним — 2 (не больше 4). Порядок — по оценке, при равной — популярнее;
 * совсем мимо (0) — в конце.
 */
object Search {
    private val pool = Executors.newFixedThreadPool(6)
    private const val GENERIC_COUNT = 100_000
    @Volatile private var text = ""
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
    /** Сколько роликов со всеми словами запроса — после 20 частичные совпадения уже не показываем. */
    @Volatile private var full = 0

    /** Полные фразы, если запрос — сокращение (atp → ass to pussy). */
    @Volatile private var alias: List<String> = emptyList()

    @Synchronized fun reset(q: String) { text = q; alias = Thesaurus.expandAbbr(q); synonyms = emptyList(); tags = emptyList(); srcPage.clear(); turn = 0; rdPending = null; rdNext = null; rdSubs = null; exact = 0 }

    /** Окончания, с которыми слово ещё «то же самое». */
    private val ENDINGS = setOf("s", "es", "ed", "er", "ers", "ing", "ings", "y", "ies", "d")

    /** Тег RedGIFs, совпадающий с фразой (без учёта регистра и пробелов), если он есть и не слишком общий. */
    private val tagCache = java.util.concurrent.ConcurrentHashMap<String, String>()
    private fun exactTag(phrase: String): String? = tagCache.getOrPut(norm(phrase)) {
        runCatching { Api.suggest(phrase) }.getOrNull()?.firstOrNull { norm(it.first) == norm(phrase) && it.second < GENERIC_COUNT * 5 }?.first.orEmpty()
    }.takeIf { it.isNotEmpty() }

    private fun norm(s: String) = s.lowercase().replace(Regex("[^\\p{L}0-9]"), "")
    private fun stem(w: String) = if (w.length > 4 && w.endsWith("s")) w.dropLast(1) else w

    /**
     * Порция поиска, живьём: каждый источник, как только ответил, отдаётся в [part] (ролики с оценкой) — выдача растёт
     * по ходу, не дожидаясь медленных. p = 1 — широкий сбор; дальше — следующие страницы. order — порядок RedGIFs.
     * Возвращает, сколько роликов пришло всего.
     */
    fun run(p: Int, order: String, reddit: Boolean, part: (List<Pair<Gif, Double>>) -> Unit): Int {
        val q = text
        val cs = java.util.concurrent.ExecutorCompletionService<List<Gif>>(pool)
        var n = 0
        fun rg(query: String, byTag: Boolean, k: Int) { cs.submit { runCatching { Api.page(query, order, k, byTag = byTag).gifs }.getOrDefault(emptyList()) }; n++ }
        if (p == 1) {
            synReady = false
            val nq = norm(q)
            (1..3).forEach { rg(q, false, it) }
            // сокращение: по полным фразам; если фраза — готовый тег RedGIFs («Ass to Pussy»), то по тегу (текстом его не найти)
            alias.forEach { a -> val t = exactTag(a); if (t != null) { rg(t, true, 1); rg(t, true, 2); rg(t, true, 3) } else { rg(a, false, 1); rg(a, false, 2) } }
            if (' ' in q.trim()) rg(nq, false, 1)                        // «bed post» → «bedpost»
            // синонимы и близкие теги ищутся, пока идёт основной запрос; Reddit — тоже параллельно
            cs.submit { val syn = runCatching { synonymsFor(q) }.getOrDefault(emptyList<String>() to emptyList()); synchronized(this) { synonyms = syn.first; tags = syn.second }; synReady = true; emptyList() }; n++
        } else {
            rg(q, false, p + 2)
            val src = synchronized(this) { (synonyms.take(4).map { it to false } + tags.take(3).map { it to true }).let { l -> l.getOrNull(turn++ % maxOf(1, l.size)) } }
            src?.let { (s, byTag) -> val k = synchronized(this) { ((srcPage[s] ?: 1) + 1).also { srcPage[s] = it } }; rg(s, byTag, k) }
        }
        if (reddit) { cs.submit { redditPart(q, p) }; n++ }
        var total = 0
        var synDone = p != 1
        if (p == 1) full = 0
        val relCnt = HashMap<String, Int>()
        var relDone = false
        var i = 0
        while (i < n) {
            val got = runCatching { cs.poll(45, TimeUnit.SECONDS)?.get() }.getOrNull() ?: emptyList(); i++
            // синонимы готовы — сразу отправить поиски по ним (первая порция)
            if (!synDone && synReady) {
                synDone = true
                // синоним — готовый тег RedGIFs: по тегу, иначе текстом
                synonyms.take(10).forEach { syn -> val t = exactTag(syn); if (t != null) rg(t, true, 1) else rg(syn, false, 1) }; tags.take(3).forEach { rg(it, true, 1) }
            }
            if (got.isEmpty()) continue
            total += got.size
            val scored = got.map { it to score(it) }
            // теги, частые у роликов прямо по теме (оценка ≥ 8), — тоже искать (у «midget» — «Little People»)
            if (p == 1 && !relDone) {
                scored.filter { it.second >= 8 }.forEach { (g, _) -> g.tags.forEach { t -> relCnt[t] = (relCnt[t] ?: 0) + 1 } }
                val rel = relCnt.filter { (t, c) -> c >= 2 && t !in tags && !Api.isGeneric(t) && !Hide.hidden(Gif("", "", "", "", 0, 0, 0.0, "", listOf(t), false, 0, 0)) }
                    .entries.sortedByDescending { it.value }.map { it.key }.take(3)
                if (rel.size >= 2 || i >= 3 && rel.isNotEmpty()) {
                    relDone = true
                    synchronized(this) { tags = (tags + rel).distinct() }
                    rel.forEach { rg(it, true, 1) }
                    android.util.Log.i("Loopa", "поиск «$q»: теги из найденного по теме $rel")
                }
            }
            exact += scored.count { it.second >= 6 || Api.words(q).size < 2 && it.second >= 3 }
            // мимо — не показываем; у запроса из 2–3 слов, когда со всеми словами набралось 20+, частичные — тоже нет
            val multi = Api.words(q).size >= 2
            full += scored.count { it.second >= 6 }
            // у запроса из 2+ слов ролик RedGIFs с одним совпавшим словом — мимо («gear» из «bondage gear» на «gear knob»);
            // синоним (≥ 7) и все слова (≥ 6) — по теме; посты Reddit (сабреддит найден по запросу) — ниже, но остаются
            part(scored.filter { it.second > 0 && (!multi || it.second >= 6 || it.first.reddit) })
        }
        android.util.Log.i("Loopa", "поиск «$q» p$p: $total роликов, по теме $exact, синонимы $synonyms, теги $tags")
        return total
    }

    /**
     * Оценка релевантности, строгая — по уровням:
     *  - фраза целиком, слова подряд («standing dildo» в названии или теге) — 20 (без окончания — 16);
     *  - все слова в одном теге — 12;
     *  - все слова, но в разных тегах/местах («Standing Doggy» + «Dildo» — уже другое) — 6;
     *  - часть слов: в теге 3, в тексте 2, по корню 1;
     *  - синоним — +1 (не больше 2). 0 — мимо, в выдачу не идёт.
     */
    fun score(g: Gif): Double {
        val q = text
        val words = Api.words(q).map(::stem).filter { it.length >= 2 }
        val tagsN = g.tags.map { norm(it) }
        val textN = norm(g.description + " " + g.sub)
        val all = tagsN.joinToString(" ") + " " + textN
        // короткие слова («dp», «atp») — только целым словом: иначе ловят что попало внутри других слов
        val split = Regex("[^\\p{L}0-9]+")
        val tagTok = g.tags.map { t -> t.lowercase().split(split).filter { it.isNotEmpty() } }
        val textTok = (g.description + " " + g.sub).lowercase().split(split).filter { it.isNotEmpty() }
        // слово совпадает целым словом с обычным окончанием (twin, twins, twinned) или как начало слитного имени
        // (TwinGirls, 69er) — но не как кусок другого слова («twin» в «twink»)
        fun tokMatch(tok: String, w: String): Boolean {
            if (tok == w) return true
            if (!tok.startsWith(w)) return false
            val rest = tok.substring(w.length)
            return rest in ENDINGS || rest.length >= 4
        }
        fun inTags(w: String) = tagTok.any { ts -> ts.any { tokMatch(it, w) } }
        fun inText(w: String) = textTok.any { tokMatch(it, w) }
        // запрос — сокращение: его полная фраза равна точному совпадению
        if (alias.any { a -> norm(a).let { it.length >= 4 && all.contains(it) } }) return 20.0 + minOf(2.0, synonyms.count { norm(it).length >= 4 && all.contains(norm(it)) }.toDouble())
        val phrase = norm(q)
        val stemP = phrase.replace(Regex("(ers|er|ing|es|s)$"), "")
        var s = 0.0
        var found = 0
        var part = 0.0
        words.forEach { w ->
            val root = w.replace(Regex("(ers|er|ing|es)$"), "").takeIf { it.length >= 4 } ?: w
            when {
                inTags(w) -> { part += 3; found++ }
                inText(w) -> { part += 2; found++ }
                root != w && (inTags(root) || inText(root)) -> { part += 1; found++ }
            }
        }
        s += when {
            phrase.length >= 4 && all.contains(phrase) -> 20.0
            phrase.length in 2..3 && words.size == 1 && found == 1 -> 20.0         // сокращение целым словом
            stemP.length >= 5 && all.contains(stemP) -> 16.0
            words.size >= 2 && tagTok.any { ts -> words.all { w -> ts.any { tokMatch(it, w) } } } -> 12.0
            words.size >= 2 && found == words.size -> 6.0
            else -> 0.0
        }
        s += part
        val synHits = synonyms.count { syn -> norm(syn).let { it.length >= 4 && all.contains(it) } }
        // совпал только синоним («doorknob» на запрос «knob») — тоже по теме, но ниже точных
        if (s == 0.0 && synHits > 0) s = 7.0 + minOf(2, synHits - 1)
        else if (s > 0) s += minOf(2.0, synHits * 1.0)
        return s
    }

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
        val f = rdPending ?: java.util.concurrent.CompletableFuture.supplyAsync<List<Gif>> {
            runCatching {
                val subs = rdSubs ?: (Reddit.searchSubs(q, 3) + (Api.words(q).take(2) + synonyms.take(2)).flatMap { runCatching { Reddit.searchSubs(it, 1) }.getOrDefault(emptyList()) })
                    .distinct().take(5).also { rdSubs = it; android.util.Log.i("Loopa", "поиск «$q» на Reddit: $it") }
                if (subs.isEmpty() || p > 1 && rdNext == null) emptyList()
                else Reddit.page(subs.joinToString("+"), "top-month", rdNext).let { (g, next) -> rdNext = next; g }
            }.onFailure { android.util.Log.w("Loopa", "поиск на Reddit", it) }.getOrDefault(emptyList())
        }
        // живьём ждать можно дольше: остальные источники уже показаны
        return try { f.get(25, TimeUnit.SECONDS).also { rdPending = null } } catch (e: java.util.concurrent.TimeoutException) { rdPending = f; emptyList() } catch (e: Exception) { rdPending = null; emptyList() }
    }
}
