package app.loopa

import java.net.URLEncoder

/**
 * PornOne (pornone.com): API нет — страницы сайта. Лента: q пусто — раздел /amateur/ (новые — /amateur/{N}/, по просмотрам —
 * /amateur/views/{N}/), поиск — /search/{N}/?q=… (новые — /search/newest/{N}/?q=…). Карточка `videocard`: путь страницы
 * (…/{id}/), превью, название, автор, длительность, просмотры; рекламные карточки (customnative) без пути с id пропускаем.
 *
 * Прогрессивный MP4 лежит только на странице ролика: `<source src="…/vid2/{подпись}/{время}/…/{id}_{WxH}_{kbps}k.mp4" res="480">`
 * (и тот же адрес в ld+json contentUrl). Подпись в пути с временем, поэтому в ленте лежит ленивый адрес ([Sources.lazy]), а
 * [resolve] берёт MP4 со страницы ролика. Качество у ролика одно (обычно 480p), выбора hd/sd нет. Отдаётся по Range (206)
 * без Referer и особого User-Agent.
 */
object PornOne : Source {
    override val id = "pornone"
    override val title = "PornOne"
    override val badge = "P1"
    override val home = SITE
    override val about = "любительское"

    const val SITE = "https://pornone.com"

    override fun page(q: String, fresh: Boolean, p: Int): Page = parseList(get(listUrl(q, fresh, p)), p)

    /** Адрес страницы выдачи (чистая функция, чтобы проверять в тестах). */
    internal fun listUrl(q: String, fresh: Boolean, p: Int): String {
        val n = if (p > 1) "$p/" else ""
        val query = q.trim()
        if (query.isEmpty()) return "$SITE/amateur/" + (if (fresh) "" else "views/") + n
        return "$SITE/search/" + (if (fresh) "newest/" else "") + n + "?q=" + URLEncoder.encode(query, "UTF-8")
    }

    /** Ключ — путь страницы ролика (/категория/название/{id}/). */
    override fun resolve(key: String, hd: Boolean): Resolved? {
        require(key.startsWith("/") && key.endsWith("/")) { "плохой ключ $key" }
        val url = parseVideo(get(SITE + key)) ?: throw IllegalStateException("PornOne: на странице нет адреса видео")
        return Resolved(url)
    }

    private fun get(url: String): String = Net.retry {
        val c = Net.open(url)
        Net.ua(c, XParse.UA)
        try {
            if (c.responseCode !in 200..299) throw Net.fail(c)
            Net.text(c)
        } finally { c.disconnect() }
    }

    // ---- разбор (без сети) ----

    private val CARD = Regex("<a href=\"https://pornone\\.com(/[^\"#]*/(\\d+)/)(?:#r\\d+)?\" class=\"popbop")
    private val IMG = Regex("<img[^>]*thumbimg[^>]*>")
    private val DATA_SRC = Regex("data-src=\"(http[^\"]+)\"")
    private val SRC = Regex("\\ssrc=\"(http[^\"]+)\"")
    private val TITLE = Regex("<div class=\"videotitle[^\"]*\">([^<]*)</div>")
    private val AUTHOR = Regex("by <span class=\"font-semibold[^\"]*\">([^<]*)</span>")
    private val DUR = Regex("<span class=\"durlabel\">(?:<img[^>]*>)?\\s*([\\d:]+)")
    private val VIEWS = Regex("viewsIcon\"></i>\\s*([\\d.,]+\\s*[kKmM]?)")
    private val SOURCE = Regex("<source src=\"([^\"]+\\.mp4[^\"]*)\"")
    private val CONTENT_URL = Regex("\"contentUrl\":\\s*\"([^\"]+)\"")

    /** Карточки выдачи; pages — следующая страница (rel="next") или эта же, если дальше нет. */
    internal fun parseList(html: String, p: Int): Page {
        val starts = CARD.findAll(html).map { it.range.first }.toList()
        val gifs = starts.mapNotNull { s -> card(html.substring(s, html.indexOf("</a>", s).let { if (it < 0) html.length else it })) }
        return Page(gifs, if ("rel=\"next\"" in html) p + 1 else p, emptyList())
    }

    private fun card(b: String): Gif? {
        val m = CARD.find(b) ?: return null
        val path = m.groupValues[1]
        // первые карточки отдают превью сразу в src, остальные — лениво в data-src
        val img = IMG.find(b)?.value ?: return null
        val thumb = (DATA_SRC.find(img) ?: SRC.find(img))?.groupValues?.get(1) ?: return null
        return Gif(id = "p1:${m.groupValues[2]}", thumb = thumb, hd = Sources.lazy("pornone", path, true), sd = Sources.lazy("pornone", path, false),
            w = 1280, h = 720, duration = clockSeconds(DUR.find(b)?.groupValues?.get(1).orEmpty()),
            user = XParse.unescape(AUTHOR.find(b)?.groupValues?.get(1).orEmpty().trim()), tags = emptyList(), hasAudio = true,
            views = VIEWS.find(b)?.let { XParse.count(it.groupValues[1]) } ?: 0, likes = 0,
            description = XParse.unescape(TITLE.find(b)?.groupValues?.get(1).orEmpty().trim()), site = "pornone")
    }

    /** Адрес MP4 со страницы ролика: тег source, иначе contentUrl из ld+json. null — нет. */
    internal fun parseVideo(html: String): String? =
        (SOURCE.find(html)?.groupValues?.get(1) ?: CONTENT_URL.find(html)?.groupValues?.get(1)?.takeIf { ".mp4" in it })
            ?.replace("&amp;", "&")
}
