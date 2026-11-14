package app.loopa

import java.net.URLEncoder

/**
 * RedTube (redtube.com): официальный API api.redtube.com (data=redtube.Videos.searchVideos: search, page, ordering,
 * thumbsize), ответ — тот же формат, что у Pornhub ([Aylo]), но ролик лежит в обёртке {"video": …}; всего роликов — count.
 * Адрес видео — со страницы redtube.com/{id}: /media/mp4?s=… (адрес от корня сайта) отдаёт список MP4 без cookie.
 */
object RedTube : Source {
    override val id = "redtube"
    override val title = "RedTube"
    override val badge = "RT"
    override val about = "та же сеть, что Pornhub"
    const val SITE = "https://www.redtube.com"
    private const val API = "https://api.redtube.com/"
    /** Сколько роликов отдаёт одна страница API. */
    private const val PER_PAGE = 20
    private val AGE = mapOf("age_verified" to "1")

    override fun page(q: String, fresh: Boolean, p: Int): Page {
        val order = if (fresh) "newest" else "mostviewed"
        val url = "$API?data=redtube.Videos.searchVideos&output=json&search=" + URLEncoder.encode(q.trim(), "UTF-8") +
            "&page=$p&ordering=$order&thumbsize=big"
        val body = Aylo.get(url).body
        val gifs = Aylo.parseList(body, id, "rt")
        val total = runCatching { org.json.JSONObject(body).optLong("count") }.getOrDefault(0L)
        val pages = ((total + PER_PAGE - 1) / PER_PAGE).toInt().coerceAtLeast(p)
        return Page(gifs, if (gifs.isEmpty()) p else pages, emptyList())
    }

    override fun resolve(key: String, hd: Boolean): Resolved? = Aylo.resolveMp4("$SITE/$key", SITE, AGE, hd)
}
