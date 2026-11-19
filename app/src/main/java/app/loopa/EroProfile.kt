package app.loopa

import java.net.URLEncoder

/**
 * EroProfile (eroprofile.com): домашние ролики пользователей. API нет — страницы мобильной версии, вход и куки не нужны
 * (проверено 2026-11-19 без куки и Referer: список, поиск и ролик отдаются, видео отвечает 206):
 *  - популярное /m/videos/popular?pnum=N, новое /m/videos/home?pnum=N, поиск /m/videos/search?text=…&pnum=N;
 *  - плитка: ссылка /m/videos/view/{slug}, превью, длительность, название, раздел («niche»), «12 hours ago», автор;
 *  - страница ролика: один <source src=….m4v> (обычный MP4 с подписью validfrom/validto на ~3 часа), поэтому в ленте лежит
 *    ленивый адрес ([Sources.lazy], ключ — slug), а [resolve] берёт свежий. Размеров и качества в странице нет — один файл.
 * Просмотры на плитках не показываются (они только на странице ролика), поэтому views = 0.
 * Заставки нет (замер 2026-11-19, 10 роликов: общего начала нет; у двух роликов одного автора по 4,7 с чёрного — его монтаж).
 */
object EroProfile : Source {
    override val id = "eroprofile"
    override val title = "EroProfile"
    override val badge = "EO"
    override val home = SITE
    override val about = "домашние ролики пользователей"

    private const val SITE = "https://www.eroprofile.com"
    private const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36"
    private const val MAX_TAGS = 12

    /** Популярное — /popular, «сначала новые» — /home; поиск — один для обоих режимов. */
    override fun page(q: String, fresh: Boolean, p: Int): Page {
        val n = p.coerceAtLeast(1)
        val url = if (q.isBlank()) "$SITE/m/videos/" + (if (fresh) "home" else "popular") + "?lang=en&pnum=$n"
        else "$SITE/m/videos/search?lang=en&text=" + URLEncoder.encode(q.trim(), "UTF-8") + "&pnum=$n"
        val html = get(url)
        return Page(parseList(html, System.currentTimeMillis() / 1000), if (hasNext(html, n)) n + 1 else n, emptyList())
    }

    override fun resolve(key: String, hd: Boolean): Resolved? {
        require(SLUG.matches(key)) { "плохой ключ $key" }
        return Resolved(parseVideo(get("$SITE/m/videos/view/$key?lang=en")) ?: throw IllegalStateException("EroProfile: у ролика нет видео"))
    }

    private fun get(url: String): String = Net.retry {
        val c = Net.open(url)
        Net.ua(c, UA)
        c.setRequestProperty("Accept-Language", "en")
        try {
            if (c.responseCode !in 200..299) throw Net.fail(c)
            Net.text(c)
        } finally { c.disconnect() }
    }

    // ---- разбор (без сети) ----

    private val SLUG = Regex("""[A-Za-z0-9_.~%-]+""")
    private val TILE = Regex("""<div class="grid-tile-video">""")
    private val LINK = Regex("""href="/m/videos/view/([A-Za-z0-9_.~%-]+)"""")
    private val THUMB = Regex("""data-src="([^"]+)"""")
    private val DURATION = Regex("""class="duration">([^<]*)<""")
    private val TITLE = Regex("""class="title cap-line">([^<]*)<""")
    private val NICHE = Regex("""href="/m/videos/search\?niche=\d+">([^<]*)<""")
    private val AGO = Regex("""(\d+)\s+(second|minute|hour|day|week|month|year)s?\s+ago""")
    private val USER = Regex("""<span class="colGray">by</span>\s*<a href="/([^"/?]+)"""")
    private val SOURCE = Regex("""<source\s+src="([^"]+)"""")
    private val PNUM = Regex("""pnum=(\d+)""")

    /** Плитки страницы -> ролики; плитка без ссылки или превью пропускается. now — unix-секунды для «12 hours ago». */
    internal fun parseList(html: String, now: Long): List<Gif> {
        val starts = TILE.findAll(html).map { it.range.first }.toList()
        return starts.mapIndexedNotNull { i, s ->
            val b = html.substring(s, if (i + 1 < starts.size) starts[i + 1] else html.length)
            val slug = LINK.find(b)?.groupValues?.get(1) ?: return@mapIndexedNotNull null
            val thumb = THUMB.find(b)?.groupValues?.get(1)?.let(XParse::unescape).orEmpty()
            val lazy = Sources.lazy(id, slug, true)
            Gif(id = "eo:$slug", thumb = thumb, hd = lazy, sd = lazy, w = 1280, h = 720,
                duration = seconds(DURATION.find(b)?.groupValues?.get(1).orEmpty()),
                user = USER.find(b)?.groupValues?.get(1).orEmpty(), tags = tags(NICHE.find(b)?.groupValues?.get(1).orEmpty()),
                hasAudio = true, views = 0, likes = 0,
                description = XParse.unescape(TITLE.find(b)?.groupValues?.get(1).orEmpty().trim()),
                created = ago(AGO.find(b), now), site = id)
        }
    }

    /** Адрес видео со страницы ролика (с подписью, лежит в <source>); null — видео нет. */
    internal fun parseVideo(html: String): String? =
        SOURCE.find(html)?.groupValues?.get(1)?.let(XParse::unescape)?.takeIf { it.startsWith("http") }

    /** Есть ли страница после n: в пагинации страницы ссылки pnum=… с большим номером. */
    internal fun hasNext(html: String, n: Int): Boolean = PNUM.findAll(html).any { (it.groupValues[1].toIntOrNull() ?: 0) > n }

    /** Раздел сайта -> теги: «Swingers / Gangbang» -> swingers, gangbang. */
    internal fun tags(niche: String): List<String> =
        XParse.unescape(niche).split('/').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct().take(MAX_TAGS)

    /** «03:44» или «1:02:03» -> секунды. */
    internal fun seconds(t: String): Double = t.trim().split(":").mapNotNull { it.toIntOrNull() }.fold(0.0) { s, x -> s * 60 + x }

    /** «12 hours ago» -> unix-секунды (приблизительно: месяц = 30 дней, год = 365); 0 — не разобрать. */
    private fun ago(m: MatchResult?, now: Long): Long {
        m ?: return 0
        val n = m.groupValues[1].toLongOrNull() ?: return 0
        val unit = when (m.groupValues[2]) {
            "second" -> 1L; "minute" -> 60L; "hour" -> 3600L; "day" -> 86400L; "week" -> 7 * 86400L
            "month" -> 30 * 86400L; else -> 365 * 86400L
        }
        return now - n * unit
    }
}
