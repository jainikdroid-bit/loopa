package app.loopa

import java.net.URLEncoder
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Erome (erome.com): альбомы пользователей с картинками и видео. API нет — страницы сайта: обзор /explore (/explore/new —
 * новые), поиск /search?q=…&o=new, альбом /a/{id}. Видео альбома — теги <video poster> + <source src …mp4>; каждое видео
 * альбома — отдельный ролик в ленте. Файлы видео отдаются только с Referer сайта (без него 403) — плеер подставляет его
 * сам для адресов *.erome.com ([Video]). Альбомы только с картинками пропускаются.
 */
object Erome {
    const val SITE = "https://www.erome.com"
    const val REFERER = "$SITE/"
    /** Альбомы страницы открываются параллельно (на странице до 36 альбомов). */
    private const val THREADS = 6
    private val pool = Executors.newFixedThreadPool(THREADS)

    /** Адрес отдаётся с серверов Erome (нужен Referer). */
    fun ours(url: String) = runCatching { java.net.URI(url).host.orEmpty() }.getOrDefault("").let { it == "erome.com" || it.endsWith(".erome.com") }

    /** Карточка альбома из выдачи: id, название, автор, сколько в нём видео. */
    private class Album(val id: String, val title: String, val user: String, val videos: Int, val views: Long)

    /** Страница ленты: q пусто — обзор, иначе поиск; fresh — сначала новые. */
    fun page(q: String, fresh: Boolean, p: Int): Page {
        val base = if (q.isBlank()) "$SITE/explore" + (if (fresh) "/new" else "") + "?page=$p"
        else "$SITE/search?q=" + URLEncoder.encode(q.trim(), "UTF-8") + (if (fresh) "&o=new" else "") + "&page=$p"
        return parse(get(base), p)
    }

    /** Личная лента со страницы сайта (подписки и т. п., адрес найден при входе), страница p. */
    fun feed(url: String, p: Int): Page = parse(get(url + (if ('?' in url) "&" else "?") + "page=$p"), p)

    private fun parse(html: String, p: Int): Page {
        val albums = albums(html).filter { it.videos > 0 }
        val jobs = albums.map { a -> pool.submit(Callable { runCatching { videos(a) }.getOrDefault(emptyList()) }) }
        val gifs = jobs.flatMap { runCatching { it.get(40, TimeUnit.SECONDS) }.getOrDefault(emptyList()) }
        val more = html.contains("page=${p + 1}")
        return Page(gifs, if (more) p + 1 else p, emptyList())
    }

    private fun get(url: String): String = Net.retry {
        val c = Net.open(url)
        c.setRequestProperty("Referer", REFERER)
        // вошли в Erome ([Ext]) — страницы с куками входа (подписки, скрытое от гостей)
        Ext.cookie(url)?.let { c.setRequestProperty("Cookie", it) }
        try {
            if (c.responseCode !in 200..299) throw Net.fail(c)
            c.inputStream.bufferedReader().use { it.readText() }
        } finally { c.disconnect() }
    }

    private val CARD = Regex("""id="album-\d+"""")
    private val LINK = Regex("""class="album-link"\s+href="https://www\.erome\.com/a/([A-Za-z0-9]+)"""")
    private val TITLE = Regex("""class="album-title"[^>]*>([^<]*)<""")
    private val USER = Regex("""class="album-user"\s*>([^<]*)<""")
    private val VIDS = Regex("""class="album-videos"\s*>(?:<svg.*?</svg>)?\s*(\d+)""")
    private val VIEWS = Regex("""class="album-bottom-views">(?:<svg.*?</svg>)?\s*([\d.,]+)\s*([KkMm]?)""")

    private fun albums(html: String): List<Album> {
        val starts = CARD.findAll(html).map { it.range.first }.toList()
        return starts.mapIndexedNotNull { i, s ->
            val block = html.substring(s, if (i + 1 < starts.size) starts[i + 1] else html.length)
            val id = LINK.find(block)?.groupValues?.get(1) ?: return@mapIndexedNotNull null
            Album(id, unescape(TITLE.find(block)?.groupValues?.get(1).orEmpty().trim()),
                unescape(USER.find(block)?.groupValues?.get(1).orEmpty().trim()),
                VIDS.find(block)?.groupValues?.get(1)?.toIntOrNull() ?: 0,
                VIEWS.find(block)?.let { count(it.groupValues[1], it.groupValues[2]) } ?: 0)
        }
    }

    /** «57,6K» -> 57600. */
    private fun count(n: String, suffix: String): Long {
        val v = n.replace(',', '.').toDoubleOrNull() ?: return 0
        return (v * when (suffix.lowercase()) { "k" -> 1_000; "m" -> 1_000_000; else -> 1 }).toLong()
    }

    private val VIDEO = Regex("""<video\b(.*?)</video>\s*(?:<span[^>]*class="duration"[^>]*>([^<]*)<)?""", RegexOption.DOT_MATCHES_ALL)
    private val POSTER = Regex("""poster="([^"]+)"""")
    private val SOURCE = Regex("""<source\s+src="([^"]+\.mp4[^"]*)"[^>]*?(?:res='(\d+)')?[^>]*>""")
    private val TAG = Regex("""href="https://www\.erome\.com/search\?q=([^"&]+)"""")

    /** Видео альбома — ролики ленты (HD — лучшее качество, SD — худшее из доступных). */
    private fun videos(a: Album): List<Gif> {
        val html = get("$SITE/a/${a.id}")
        val tags = TAG.findAll(html).map { java.net.URLDecoder.decode(it.groupValues[1], "UTF-8") }.distinct().take(12).toList()
        return VIDEO.findAll(html).mapIndexedNotNull { i, m ->
            val body = m.groupValues[1]
            val src = SOURCE.findAll(body).map { it.groupValues[1] to (it.groupValues[2].toIntOrNull() ?: 0) }.sortedByDescending { it.second }.toList()
            if (src.isEmpty()) return@mapIndexedNotNull null
            val poster = POSTER.find(body)?.groupValues?.get(1).orEmpty()
            val res = src.first().second
            Gif(id = "er:${a.id}:$i", thumb = poster, hd = src.first().first, sd = src.last().first,
                w = if (res > 0) res * 16 / 9 else 0, h = res, duration = seconds(m.groupValues[2]), user = a.user, tags = tags,
                hasAudio = true, views = a.views, likes = 0, description = a.title, site = "erome")
        }.toList()
    }

    /** «03:44» или «1:02:03» -> секунды. */
    private fun seconds(t: String): Double = t.trim().split(":").mapNotNull { it.toIntOrNull() }.fold(0.0) { s, x -> s * 60 + x }

    private fun unescape(s: String) = s.replace("&amp;", "&").replace("&#039;", "'").replace("&quot;", "\"").replace("&lt;", "<").replace("&gt;", ">")
}
