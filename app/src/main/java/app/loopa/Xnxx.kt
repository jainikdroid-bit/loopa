package app.loopa

import java.net.URLEncoder

/** XNXX (xnxx.com): та же вёрстка и тот же разбор, что у xVideos — общий движок [XTube] в XVideos.kt. */
internal object Xnxx : XTube() {
    override val id = "xnxx"
    override val title = "XNXX"
    override val badge = "XN"
    override val about = "любительское, тот же движок"
    override val host = "https://www.xnxx.com"
    override val prefix = "xn"

    /**
     * Пусто — поиск «amateur» (/tags/amateur переадресует на него), иначе /search/{запрос}/{N} (N с нуля, у первой страницы
     * его нет). Сортировки по дате у XNXX нет: по умолчанию окно «за месяц», fresh — окно «за неделю» (/search/week/…).
     */
    override fun url(q: String, fresh: Boolean, p: Int): String {
        val n = p - 1
        val k = URLEncoder.encode(q.ifEmpty { "amateur" }, "UTF-8")
        return "$host/search/" + (if (fresh) "week/" else "") + k + if (n > 0) "/$n" else ""
    }
}
