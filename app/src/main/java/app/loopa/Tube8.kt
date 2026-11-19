package app.loopa

import java.net.URLEncoder

/**
 * Tube8 (tube8.com): та же вёрстка и тот же каталог, что у YouPorn ([Aylo.parseHtmlList]); API для вебмастеров закрыт.
 * Без запроса — любительский раздел /cat/amateur/, fresh — /newest.html/ (новые, почти все от любителей); поиск —
 * /searches.html/?q=. Страница N — ?page=N. Сортировки у поиска нет. Видео — со страницы /porn-video/{id}/ через индекс
 * MP4 ([Aylo.resolveMp4]); CDN без Referer сайта отвечает 410.
 */
object Tube8 : Source {
    override val id = "tube8"
    override val title = "Tube8"
    override val badge = "T8"
    override val home = SITE
    override val about = "любительское, сеть Pornhub"
    override val introSec = INTRO_SEC
    /** Заставка сайта в начале ролика, с (по замерам 19.11). */
    private const val INTRO_SEC = 3.4   // замер 19.11: заставка 3,1 с у 7 из 10 роликов, у 3 её нет (+0,3 с запас, как у Pornhub/RedTube)
    const val SITE = "https://www.tube8.com"
    private val AGE = mapOf("age_verified" to "1")

    override fun page(q: String, fresh: Boolean, p: Int): Page {
        val url = when {
            q.isNotBlank() -> Aylo.paged("$SITE/searches.html/?q=" + URLEncoder.encode(q.trim(), "UTF-8"), p)
            fresh -> Aylo.paged("$SITE/newest.html/", p)
            else -> Aylo.paged("$SITE/cat/amateur/", p)
        }
        return Aylo.htmlPage(url, id, "t8", p, AGE)
    }

    override fun resolve(key: String, hd: Boolean): Resolved? = Aylo.resolveMp4("$SITE/porn-video/$key/", SITE, AGE, hd)
}
