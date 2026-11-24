package app.loopa

/**
 * Чистая часть поиска (без сети и Android): оценка релевантности ролика и порядок выдачи. Вынесена из [Search] и [Feed],
 * чтобы проверяться юнит-тестами.
 */
object Relevance {
    /** Окончания, с которыми слово ещё «то же самое». */
    private val ENDINGS = setOf("s", "es", "ed", "er", "ers", "ing", "ings", "y", "ies", "d")
    private val CAMEL_BOUNDARY = Regex("(?<=[\\p{Ll}])(?=[\\p{Lu}])")
    private val SPLIT = Regex("[^\\p{L}0-9]+")


    fun norm(s: String) = s.lowercase().replace(Regex("[^\\p{L}0-9]"), "")
    private fun stem(w: String) = if (w.length > 4 && w.endsWith("s")) w.dropLast(1) else w

    /**
     * Оценка релевантности ролика запросу [q], одинаковая для всех источников (RedGIFs, Reddit, сторонние сайты): по
     * тегам, названию/описанию и сабреддиту. У роликов без тегов (xVideos, Pornhub) — по названию. Уровни:
     *  - 20+ — фраза целиком, слова подряд («standing dildo» в названии или теге); однословный запрос — слово в теге
     *    целым словом или с обычным окончанием (twin, twins, twinning);
     *  - 16+ — фраза без окончания; однословный запрос — слово в названии/описании/сабреддите (так же, целым словом);
     *  - 12+ — многословный: все слова в одном теге;
     *  - 9+ — многословный: все слова, но в разных тегах/местах («Standing Doggy» + «Dildo» — уже другое);
     *  дальше — «похожее», в конце выдачи (сначала точное, потом всё менее строгое):
     *  - 4…5 — совпал только синоним («doorknob» на «knob»);
     *  - 1…3 — однословный: совпадение по корню (3) или внутри другого слова (2); многословный: совпала хотя бы
     *    половина слов (1…3 — по доле совпавших);
     *  - дробная часть (до 0,9) — тай-брейк внутри уровня: сколько слов в тегах/тексте и сколько синонимов.
     *  0 — мимо, в выдачу не идёт (пост Reddit с частью слов — 0,1…0,9, ниже всех).
     */
    fun score(g: Gif, q: String, alias: List<String>, synonyms: List<String>): Double {
        fun tokens(s: String) = s.replace(CAMEL_BOUNDARY, " ").lowercase().split(SPLIT).filter { it.isNotEmpty() }
        val fields = (g.tags + listOf(g.description, g.sub)).map(::tokens)
        fun phraseMatch(query: List<String>): Boolean = query.isNotEmpty() && fields.any { field ->
            field.asSequence().windowed(query.size).any { it == query }
        }
        val words = Api.words(q).map(::stem).filter { it.length >= 2 }
        val tagsN = g.tags.map { norm(it) }
        val textN = norm(g.description + " " + g.sub)
        val all = tagsN.joinToString(" ") + " " + textN
        // короткие слова («dp», «atp») — только целым словом: иначе ловят что попало внутри других слов
        val tagTok = fields.take(g.tags.size)
        val textTok = fields.takeLast(2).flatten()
        // слово совпадает целым словом с обычным окончанием (twin, twins, twinned) или как начало слитного имени
        // (TwinGirls, 69er) — но не как кусок другого слова («twin» в «twink»)
        fun tokMatch(tok: String, w: String): Boolean {
            if (tok == w) return true
            if (!tok.startsWith(w)) return false
            val rest = tok.substring(w.length)
            return rest in ENDINGS || (w.length >= 3 && rest.firstOrNull() == w.last() && rest.drop(1) in ENDINGS)
        }
        fun inTags(w: String) = tagTok.any { ts -> ts.any { tokMatch(it, w) } }
        fun inText(w: String) = textTok.any { tokMatch(it, w) }
        val synHits = synonyms.count { syn -> phraseMatch(tokens(syn)) }
        // Literal matches have their own tier, including when the title is the only metadata.
        val queryTokens = tokens(q)
        if (phraseMatch(queryTokens)) {
            val inTag = tagTok.any { field -> field.asSequence().windowed(queryTokens.size).any { it == queryTokens } }
            return 24.0 + (if (inTag) 0.2 else 0.0) + minOf(0.3, synHits * 0.1)
        }
        if (queryTokens.size > 1 && queryTokens.all { token -> fields.any { token in it } }) {
            val oneTag = tagTok.any { it.containsAll(queryTokens) }
            return 22.0 + (if (oneTag) 0.5 else 0.0) + minOf(0.3, synHits * 0.1)
        }
        // запрос — сокращение: его полная фраза равна точному совпадению
        if (alias.any { a -> phraseMatch(tokens(a)) }) return 20.0 + minOf(0.9, synHits * 0.1)
        val phrase = norm(q)
        var base = 0.0
        var part = 0.0
        var found = 0
        var similar = 0.0
        when {
            // запрос не латиницей — по подстроке
            words.isEmpty() -> if (phrase.length >= 4 && all.contains(phrase)) base = 20.0
            words.size == 1 -> {
                val w = words[0]
                // целым словом или с обычным окончанием — точное; по корню и внутри другого слова — похожее (в конце)
                val root = w.replace(Regex("(ers|er|ing|es|y)$"), "").takeIf { it.length >= 4 }
                when {
                    inTags(w) -> { base = 20.0; part = 3.0 }
                    inText(w) -> { base = 16.0; part = 2.0 }
                    root != null && root != w && (inTags(root) || inText(root)) -> similar = 3.0
                    w.length >= 4 && all.contains(w) -> similar = 2.0
                }
            }
            else -> {
                words.forEach { w ->
                    val root = w.replace(Regex("(ers|er|ing|es)$"), "").takeIf { it.length >= 4 } ?: w
                    when {
                        inTags(w) -> { part += 3; found++ }
                        inText(w) -> { part += 2; found++ }
                        root != w && (inTags(root) || inText(root)) -> { part += 1; found++ }
                    }
                }
                base = when {
                    queryTokens.isNotEmpty() && fields.any { field -> field.asSequence().windowed(queryTokens.size).any { chunk -> chunk.indices.all { tokMatch(chunk[it], stem(queryTokens[it])) } } } -> 16.0
                    tagTok.any { ts -> words.all { w -> ts.any { tokMatch(it, w) } } } -> 12.0
                    found == words.size -> 9.0
                    else -> 0.0
                }
                // не все слова, но хотя бы половина — похожее: 1…3 по доле совпавших
                if (base == 0.0 && found * 2 >= words.size && found > 0) similar = 1.0 + 2.0 * found / words.size
            }
        }
        if (base > 0) return base + minOf(0.9, part * 0.1 + minOf(0.3, synHits * 0.1))
        // совпал только синоним («doorknob» на запрос «knob») — похожее, сразу после точных
        if (synHits > 0) return 4.0 + minOf(1.9, (synHits - 1) * 0.5)
        if (similar > 0) return minOf(3.9, similar + minOf(0.9, part * 0.1))
        // пост Reddit (сабреддит найден по запросу), часть слов многословного запроса — ниже всех, но остаётся
        if (g.reddit && words.size >= 2 && part > 0) return minOf(0.9, part * 0.1)
        return 0.0
    }

    /**
     * Уровень оценки (для [order]): 6 — фраза/слово в теге или названии, 5 — все слова в одном теге, 4 — все слова врозь
     * (это точные); 3 — синоним, 2 — похожее (по корню, внутри слова, часть слов), 1 — пост Reddit с частью слов.
     */
    fun level(s: Double) = when { s >= 24 -> 8; s >= 22 -> 7; s >= 16 -> 6; s >= 12 -> 5; s >= 9 -> 4; s >= 4 -> 3; s >= 1 -> 2; s > 0 -> 1; else -> 0 }
    /** Точное совпадение (уровень ≥ 4). */
    fun exact(s: Double) = level(s) >= 4

    /** Показывать ролик: всё, что хоть как-то по теме; похожее — ниже точного за счёт уровня ([order]). */
    fun visible(s: Double) = s > 0

    /** Источник ролика для чередования: сторонний сайт, Reddit или RedGIFs. */
    fun source(g: Gif) = if (g.ext) g.site else if (g.reddit) "reddit" else "redgifs"

    /** Оценка ролика в выдаче: основа [score] и добавка за вкус/качество ([bonus]) — только внутри уровня. */
    class Rank(val score: Double, val bonus: Double = 0.0) {
        val level get() = level(score)
        val total get() = score + bonus
    }

    /**
     * Порядок выдачи: уровни — от высшего; внутри уровня ролики разных источников чередуются по кругу (RedGIFs, xVideos,
     * Pornhub, … — источники по лучшему ролику уровня), а не «все RedGIFs, потом остальные»; внутри источника при
     * равной оценке — популярнее выше. Ролик без оценки ([rank] = null) — самый низкий уровень.
     */
    fun order(items: List<Gif>, rank: (Gif) -> Rank?): List<Gif> {
        val none = Rank(0.0)
        val byLevel = items.groupBy { (rank(it) ?: none).level }
        val out = ArrayList<Gif>(items.size)
        val cmp = compareByDescending<Gif> { (rank(it) ?: none).total }.thenByDescending { it.likes }.thenByDescending { it.views }
        for (lv in byLevel.keys.sortedDescending()) {
            val queues = byLevel.getValue(lv).groupBy(::source).mapValues { (_, l) -> ArrayDeque(l.sortedWith(cmp)) }
            val names = queues.keys.sortedWith(compareByDescending<String> { (rank(queues.getValue(it).first()) ?: none).total }.thenBy { it })
            while (queues.values.any { it.isNotEmpty() }) for (n in names) queues.getValue(n).removeFirstOrNull()?.let { out += it }
        }
        return out
    }
}
