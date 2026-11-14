package app.loopa

import android.util.LruCache
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Похожие ролики для открытого в плеере.
 *
 * Кандидаты (параллельно, у каждого источника свой срок): сочетания двух самых конкретных тегов ролика и отдельные такие теги
 * в RedGIFs (по тегу — точнее текста), другие ролики того же автора, тот же сабреддит, поиск сторонних сайтов по тем же
 * тегам (если эти сайты включены в меню 👤).
 *
 * Оценка — строгая, похожесть по смыслу, а не популярность:
 *  - теги: косинус по весам (общий тег вроде «amateur», «teen» весит 0,15, конкретный — 1); теги сравниваются без регистра,
 *    пробелов и окончания -s;
 *  - слова названия (длиннее 3 букв, без служебных) — по 0,4 за общее, до 1,2;
 *  - тот же автор +0,35, тот же сабреддит +0,25, близкая длительность до +0,1;
 *  - порог [MIN]: без хотя бы одного общего конкретного тега или пары слов названия ролик не попадает.
 * Скрытое ([Hide]) и тот же файл — выкидываются, уже просмотренные — чуть ниже.
 */
object Similar {
    private val pool = Executors.newFixedThreadPool(6)
    private val cache = LruCache<String, List<Gif>>(30)
    private const val GENERIC_W = 0.15
    private const val MIN = 0.32
    private const val LIMIT = 45
    private val STOP = setOf("with", "this", "that", "from", "your", "have", "what", "when", "they", "them", "then", "there", "into", "just",
        "like", "love", "make", "more", "very", "real", "video", "videos", "full", "free", "best", "new", "part", "tiktok", "onlyfans", "leaked")

    private fun norm(t: String) = t.lowercase().replace(Regex("[^\\p{L}0-9]"), "").let { if (it.length > 4 && it.endsWith("s")) it.dropLast(1) else it }
    private fun w(t: String) = if (Api.isGeneric(t)) GENERIC_W else 1.0
    private fun tagsOf(g: Gif) = g.tags.filter { !it.startsWith("r/") }.map(::norm).filter { it.length > 1 }.toSet()
    private fun words(g: Gif) = g.description.lowercase().split(Regex("[^\\p{L}0-9]+")).filter { it.length > 3 && it !in STOP }.map(::norm).toSet()

    /** Самые конкретные теги ролика (без общих), по порядку сайта. */
    private fun specific(g: Gif) = g.tags.filter { !it.startsWith("r/") && !Api.isGeneric(it) && it.length > 1 }.distinct().take(6)

    /** Похожие (в фоне; результат кэшируется на ролик). */
    fun find(g: Gif): List<Gif> {
        cache.get(g.id)?.let { return it }
        val jobs = ArrayList<java.util.concurrent.Future<List<Gif>>>()
        fun src(f: () -> List<Gif>) { jobs += pool.submit(Callable { runCatching(f).getOrDefault(emptyList()) }) }
        val sp = specific(g)
        val key = sp.take(3)
        // RedGIFs: пары конкретных тегов (самое точное), отдельные теги, автор
        for (i in key.indices) for (j in i + 1 until key.size) { val pr = "${key[i]},${key[j]}"; src { Api.page(pr, "trending", 1, byTag = true).gifs } }
        sp.take(2).forEach { t -> src { Api.page(t, "trending", 1, byTag = true).gifs } }
        if (!g.reddit && !g.ext && g.user.isNotBlank()) src { Api.page("", "top", 1, author = g.user).gifs }
        // Reddit: тот же сабреддит
        if (g.reddit && Reddit.hadUser) src { Reddit.page(g.sub, "hot", null).first }
        // без конкретных тегов (пост Reddit, Eporner без ключевых слов) — по словам названия
        val q = (sp.take(2).takeIf { it.isNotEmpty() } ?: words(g).take(3).toList()).joinToString(" ")
        if (sp.isEmpty() && q.isNotBlank()) src { Api.page(q, "trending", 1, byTag = false).gifs }
        if (q.isNotBlank()) Sources.ALL.filter { ForYou.uses(it) || g.site == it.id }.forEach { s -> src { s.page(q, false, 1).gifs } }
        val all = jobs.flatMap { runCatching { it.get(30, TimeUnit.SECONDS) }.getOrDefault(emptyList()) }
        return rank(g, all).also { cache.put(g.id, it) }
    }

    private fun rank(g: Gif, cand: List<Gif>): List<Gif> {
        val ta = tagsOf(g); val wa = words(g)
        val na = sqrt(ta.sumOf { w(it) }.coerceAtLeast(1.0))
        val seen = HashSet<String>().apply { add(g.id); add(g.hd) }
        return cand.asSequence().filter { seen.add(it.id) && seen.add(it.hd) && !Hide.hidden(it) }.map { c ->
            val tb = tagsOf(c)
            val shared = ta.intersect(tb)
            val tagSim = if (ta.isEmpty() || tb.isEmpty()) 0.0 else shared.sumOf { w(it) } / (na * sqrt(tb.sumOf { w(it) }.coerceAtLeast(1.0)))
            val ws = wa.intersect(words(c)).size
            var s = tagSim + minOf(1.2, 0.4 * ws)
            // сколько общих конкретных тегов: одного общего тега «amateur» мало
            val strong = shared.count { !Api.isGeneric(it) }
            if (strong == 0 && ws < 2) s *= 0.3
            if (g.user.isNotBlank() && c.user.equals(g.user, true) && c.site == g.site) s += 0.35
            if (g.reddit && c.sub.equals(g.sub, true)) s += 0.25
            if (g.duration > 0 && c.duration > 0) s += 0.1 * (1 - minOf(1.0, abs(g.duration - c.duration) / maxOf(g.duration, c.duration)))
            if (Interests.isSeen(c.id)) s -= 0.1
            c to s
        }.filter { it.second >= MIN }.sortedByDescending { it.second }.take(LIMIT).map { it.first }.toList()
    }
}
