package app.loopa

import java.net.URI

/**
 * Ссылка из «Поделиться» / «Открыть в приложении» → ролик для скачивания. Понимает: RedGIFs (watch, ifr, i, media),
 * посты Reddit (comments, redd.it, ссылки-«поделиться» /s/…) и прямые v.redd.it.
 */
object ShareLink {
    sealed class Link {
        data class RedGifs(val id: String) : Link()
        data class RedditPost(val id: String, val url: String?) : Link()
        data class VReddit(val id: String) : Link()
        /** Короткая ссылка reddit.com/r/…/s/… — раскрывается переадресацией. */
        data class Short(val url: String) : Link()
    }

    private val URL = Regex("https?://[^\\s<>\"']+")
    private val RG_PAGE = Regex("^/(?:watch|ifr|i)/([A-Za-z]+)")
    private val RG_MEDIA = Regex("^/([A-Za-z]+?)(?:-[a-z]+)?\\.(?:mp4|webm|jpg|mp3)$")
    private val POST = Regex("/comments/([a-z0-9]{5,8})(?:/|$)")
    private val SHORT = Regex("^/r/[^/]+/s/[A-Za-z0-9]+")
    private val SHORT_HOST = Regex("^/([a-z0-9]{5,8})$")

    /** Первая понятная ссылка в тексте (в «Поделиться» к ссылке нередко приписано название поста). */
    fun find(text: String): Link? = URL.findAll(text).mapNotNull { parse(it.value) }.firstOrNull()

    fun parse(raw: String): Link? {
        val url = raw.trimEnd('.', ',', ')', ']', '!', '?')
        val u = runCatching { URI(url) }.getOrNull() ?: return null
        val host = u.host?.lowercase()?.removePrefix("www.")?.removePrefix("old.")?.removePrefix("new.")?.removePrefix("m.") ?: return null
        val path = u.rawPath ?: ""
        return when (host) {
            "redgifs.com", "v3.redgifs.com" -> RG_PAGE.find(path)?.let { Link.RedGifs(it.groupValues[1]) }
            "media.redgifs.com" -> RG_MEDIA.find(path)?.let { Link.RedGifs(it.groupValues[1]) }
            "v.redd.it" -> Regex("^/([a-z0-9]+)").find(path)?.let { Link.VReddit(it.groupValues[1]) }
            "redd.it" -> SHORT_HOST.find(path)?.let { Link.RedditPost(it.groupValues[1], null) }
            "reddit.com" -> POST.find(path)?.let { Link.RedditPost(it.groupValues[1], url) }
                ?: if (SHORT.containsMatchIn(path)) Link.Short(url) else null
            else -> null
        }
    }

    /** Ролик по ссылке; null — не нашли. */
    fun resolve(link: Link): Gif? = when (link) {
        is Link.RedGifs -> Api.gif(link.id)
        is Link.VReddit -> {
            val hls = "https://v.redd.it/${link.id}/HLSPlaylist.m3u8"
            Gif("rd_" + link.id, "", hls, hls, 9, 16, 0.0, "reddit", emptyList(), true, 0, 0, sub = "reddit", hls = true,
                dash = "https://v.redd.it/${link.id}/DASHPlaylist.mpd")
        }
        is Link.RedditPost -> Reddit.post(link.id, link.url)
        is Link.Short -> redirect(link.url)?.let { parse(it) }?.takeIf { it !is Link.Short }?.let { resolve(it) }
    }

    /** Куда ведёт короткая ссылка (после переадресаций). */
    private fun redirect(url: String): String? = runCatching {
        val c = Net.open(url); c.instanceFollowRedirects = true
        try { c.responseCode; c.url.toString() } finally { c.disconnect() }
    }.getOrNull()
}
