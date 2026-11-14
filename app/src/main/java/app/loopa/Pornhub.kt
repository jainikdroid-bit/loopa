package app.loopa

import java.net.URLEncoder

/**
 * Pornhub (pornhub.com): официальный API для вебмастеров /webmasters/search (search, page, ordering, category). Без запроса
 * — категория Amateur, самые просматриваемые; fresh — сначала новые. Общего числа страниц API не даёт: неполная
 * страница — последняя. Адрес видео — со страницы view_video.php (cookie age_verified и ss из ответа), см. [Aylo].
 * Страница ролика тяжёлая (1,4 МБ, ~150 КБ по сети), поэтому лёгкая embed-страница (только 480p) — запасной путь.
 */
object Pornhub : Source {
    override val id = "pornhub"
    override val title = "Pornhub"
    override val badge = "PH"
    override val about = "любительское и verified amateurs"
    const val SITE = "https://www.pornhub.com"
    /** Сколько роликов отдаёт одна страница API. */
    private const val PER_PAGE = 30
    private val AGE = mapOf("age_verified" to "1", "accessAgeDisclaimerPH" to "1", "platform" to "pc")

    override fun page(q: String, fresh: Boolean, p: Int): Page {
        val order = if (fresh) "newest" else "mostviewed"
        val url = "$SITE/webmasters/search?search=" + URLEncoder.encode(q.trim(), "UTF-8") + "&page=$p&ordering=$order" +
            (if (q.isBlank()) "&category=amateur" else "") + "&thumbsize=large_hd"
        val gifs = Aylo.parseList(Aylo.get(url).body, id, "ph")
        return Page(gifs, if (gifs.size >= PER_PAGE) p + 1 else p, emptyList())
    }

    override fun resolve(key: String, hd: Boolean): Resolved? =
        runCatching { Aylo.resolveMp4("$SITE/view_video.php?viewkey=$key", SITE, AGE, hd) }.getOrNull()
            ?: Aylo.resolveMp4("$SITE/embed/$key", SITE, AGE, hd)
}
