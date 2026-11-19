package app.loopa

import java.net.URLEncoder

/**
 * YouPorn (youporn.com): API для вебмастеров закрыт (404), поэтому лента — HTML-плитки ([Aylo.parseHtmlList]). Без запроса —
 * любительский раздел /category/amateur/, fresh — /browse/time/ (новые, почти все от любителей); поиск — /search/?query=.
 * Страница N — ?page=N, есть ли дальше, видно по <link rel="next">. Сортировки у поиска нет. Видео — со страницы
 * /watch/{id}/ через индекс MP4 (/media/mp4/?s=…), как у Pornhub ([Aylo.resolveMp4]); CDN без Referer сайта отвечает 410.
 */
object YouPorn : Source {
    override val id = "youporn"
    override val title = "YouPorn"
    override val badge = "YP"
    override val home = SITE
    override val about = "любительское, сеть Pornhub"
    override val introSec = INTRO_SEC
    /** Заставка сайта в начале ролика, с (по замерам 19.11). */
    private const val INTRO_SEC = 3.4   // замер 19.11: заставка 3,1 с у 10 из 10 роликов (+0,3 с запас)
    const val SITE = "https://www.youporn.com"
    private val AGE = mapOf("age_verified" to "1")

    override fun page(q: String, fresh: Boolean, p: Int): Page {
        val url = when {
            q.isNotBlank() -> Aylo.paged("$SITE/search/?query=" + URLEncoder.encode(q.trim(), "UTF-8"), p)
            fresh -> Aylo.paged("$SITE/browse/time/", p)
            else -> Aylo.paged("$SITE/category/amateur/", p)
        }
        return Aylo.htmlPage(url, id, "yp", p, AGE)
    }

    override fun resolve(key: String, hd: Boolean): Resolved? = Aylo.resolveMp4("$SITE/watch/$key/", SITE, AGE, hd)
}
