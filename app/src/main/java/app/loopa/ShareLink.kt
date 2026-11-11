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
        /** Не ролик, а страница RedGIFs: поиск, автор, тег — открывается в ленте Loopa. */
        data class RedGifsSearch(val query: String) : Link()
        data class RedGifsUser(val name: String) : Link()
        data class RedGifsTag(val tag: String) : Link()
    }

    private val URL = Regex("https?://[\\w\\-._~:/?#\\[\\]@!$&()*+,;=%]+")
    private val RG_PAGE = Regex("^/(?:watch|ifr|i)/([A-Za-z]+)")
    private val RG_MEDIA = Regex("^/([A-Za-z]+?)(?:-[a-z]+)?\\.(?:mp4|webm|jpg|mp3)$")
    private val POST = Regex("/comments/([a-z0-9]{5,8})(?:/|$)")
    private val SHORT = Regex("^/(?:r|u|user)/[^/]+/s/[A-Za-z0-9]+")
    private val SHORT_HOST = Regex("^/([a-z0-9]{5,8})$")

    /** Первая понятная ссылка в тексте (в «Поделиться» к ссылке нередко приписано название поста). */
    fun find(text: String): Link? = URL.findAll(text).mapNotNull { parse(it.value) }.firstOrNull()

    fun parse(raw: String): Link? {
        val url = raw.trimEnd('.', ',', ')', ']', '!', '?')
        val u = runCatching { URI(url) }.getOrNull() ?: return null
        val host = u.host?.lowercase()?.removePrefix("www.")?.removePrefix("old.")?.removePrefix("new.")?.removePrefix("m.") ?: return null
        val path = u.rawPath ?: ""
        return when (host) {
            "redgifs.com", "v3.redgifs.com" -> RG_PAGE.find(path)?.let { Link.RedGifs(it.groupValues[1]) } ?: redgifsPage(path, u.rawQuery)
            "media.redgifs.com" -> RG_MEDIA.find(path)?.let { Link.RedGifs(it.groupValues[1]) }
            "v.redd.it" -> Regex("^/([a-z0-9]+)").find(path)?.let { Link.VReddit(it.groupValues[1]) }
            "redd.it" -> SHORT_HOST.find(path)?.let { Link.RedditPost(it.groupValues[1], null) }
            "reddit.com" -> POST.find(path)?.let { Link.RedditPost(it.groupValues[1], url) }
                ?: if (SHORT.containsMatchIn(path)) Link.Short(url) else null
            else -> null
        }
    }

    /** Поиск (/search/gifs?query=…), автор (/users/имя), тег (/gifs/тег-через-дефис). */
    private fun redgifsPage(path: String, rawQuery: String?): Link? {
        if (path.startsWith("/search")) {
            val q = rawQuery?.split('&')?.firstOrNull { it.startsWith("query=") }?.substringAfter('=')
                ?.let { java.net.URLDecoder.decode(it, "UTF-8").trim() }
            return q?.takeIf { it.isNotEmpty() }?.let { Link.RedGifsSearch(it) }
        }
        Regex("^/users/([A-Za-z0-9._-]+)").find(path)?.let { return Link.RedGifsUser(it.groupValues[1]) }
        Regex("^/gifs/([^/]+)").find(path)?.let { m ->
            val t = java.net.URLDecoder.decode(m.groupValues[1], "UTF-8").replace('-', ' ').trim()
            return t.takeIf { it.isNotEmpty() }?.let { Link.RedGifsTag(it) }
        }
        return null
    }

    /** Страница RedGIFs → что передать ленте (ключ extra и значение); null — это ссылка на ролик. */
    fun screen(link: Link): Pair<String, String>? = when (link) {
        is Link.RedGifsSearch -> "query" to link.query
        is Link.RedGifsUser -> "author" to link.name
        is Link.RedGifsTag -> "tag" to link.tag
        else -> null
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
        is Link.RedGifsSearch, is Link.RedGifsUser, is Link.RedGifsTag -> null
        is Link.Short -> redirect(link.url)?.let { parse(it) }?.takeIf { it !is Link.Short }?.let { resolve(it) }
    }

    private val main by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }

    /** Найти ролик по ссылке и поставить на скачивание; сообщения — в главный поток. */
    fun download(app: android.content.Context, link: Link) {
        fun say(t: String) = main.post { android.widget.Toast.makeText(app, t, android.widget.Toast.LENGTH_LONG).show() }
        say(tr("Ищу ролик по ссылке…"))
        Thread {
            val r = runCatching { resolve(link) }.onFailure { android.util.Log.w("Loopa", "ссылка $link", it) }
            val g = r.getOrNull()
            android.util.Log.i("Loopa", "ссылка в поиске: ролик ${g?.id ?: "не найден"}")
            when {
                g != null -> main.post { Downloads.get(app, g) }
                r.isFailure -> say(tr("Не скачать: %s", r.exceptionOrNull()?.message ?: "?"))
                else -> say(tr("Ролик по ссылке не найден"))
            }
        }.start()
    }

    /** Куда ведёт короткая ссылка (после переадресаций). */
    private fun redirect(url: String): String? {
        // сначала обычным запросом; Reddit приложению нередко не отвечает — тогда через невидимый браузер
        val plain = runCatching {
            val c = Net.open(url); c.instanceFollowRedirects = true
            try { c.responseCode; c.url.toString() } finally { c.disconnect() }
        }.getOrNull()
        if (plain != null && POST.containsMatchIn(plain)) return plain
        android.util.Log.i("Loopa", "поделиться: короткая ссылка напрямую → ${plain ?: "нет ответа"}; открываю в браузере")
        return Reddit.openedUrl(url).also { android.util.Log.i("Loopa", "поделиться: после браузера → ${it ?: "нет"}") }
    }
}
