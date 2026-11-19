package app.loopa

import org.json.JSONArray
import java.net.URLEncoder

/**
 * YouJizz (youjizz.com): API нет — страницы сайта. Лента: q пусто — раздел /categories/amateur-{N}.html, поиск —
 * /search/{запрос}-{N}.html, новые — тот же поиск с префиксом порядка: /search/recent_{запрос}-{N}.html (для пустого q запрос
 * «amateur»). Карточка `video-thumb`: id, путь страницы, превью (data-original), название, длительность, просмотры.
 *
 * Адрес видео в выдаче только пробный (data-clip — короткий клип), настоящий лежит на странице ролика в скрипте
 * `var dataEncodings = [...]`: прогрессивные MP4 всех качеств (240–1080p) и HLS-копии. Ссылки подписаны (validfrom/validto,
 * окно ~2 суток), поэтому в ленте лежит ленивый адрес ([Sources.lazy]), а [resolve] берёт MP4 со страницы ролика. MP4 отдаётся
 * по Range (206) без Referer и особого User-Agent.
 */
object YouJizz : Source {
    override val id = "youjizz"
    override val title = "YouJizz"
    override val badge = "YJ"
    override val home = SITE
    override val about = "любительское и всё подряд"

    const val SITE = "https://www.youjizz.com"
    private const val AMATEUR = "amateur"
    /** Качество «обычного» ролика (HD) и лёгкого (SD): не выше этих высот. */
    private const val HD_MAX = 720
    private const val SD_MAX = 480

    override fun page(q: String, fresh: Boolean, p: Int): Page = parseList(get(listUrl(q, fresh, p)), p)

    /** Адрес страницы выдачи (чистая функция, чтобы проверять в тестах). */
    internal fun listUrl(q: String, fresh: Boolean, p: Int): String {
        val query = q.trim()
        if (query.isEmpty() && !fresh) return "$SITE/categories/$AMATEUR-$p.html"
        val k = URLEncoder.encode(query.ifEmpty { AMATEUR }, "UTF-8").replace("+", "%20")
        return "$SITE/search/" + (if (fresh) "recent_" else "") + "$k-$p.html"
    }

    override fun resolve(key: String, hd: Boolean): Resolved? {
        require(key.startsWith("/videos/")) { "плохой ключ $key" }
        val url = pick(parseEncodings(get(SITE + key)), if (hd) HD_MAX else SD_MAX)
            ?: throw IllegalStateException("YouJizz: на странице нет адреса видео")
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

    private val CARD = Regex("<div class=\"video-thumb\" data-videoId=\"(\\d+)\"")
    private val PATH = Regex("class=\"frame video\" href=\"(/videos/[^\"]+)\"")
    private val THUMB = Regex("data-original=\"([^\"]+)\"")
    private val TITLE = Regex("class=\"agego-content-title\">([^<]*)</a>")
    private val TIME = Regex("<span class=\"time\">.*?&nbsp;([\\d:]+)</span>", RegexOption.DOT_MATCHES_ALL)
    private val VIEWS = Regex("<span class=\"format-views\">([\\d.,]+)</span>")
    private val ENCODINGS = Regex("var dataEncodings = (\\[.*?\\]);", RegexOption.DOT_MATCHES_ALL)

    /** Качество и адрес одного MP4 со страницы ролика. */
    class Encoding(val height: Int, val url: String)

    /** Карточки выдачи; pages — следующая страница (или эта же, если дальше нет). */
    internal fun parseList(html: String, p: Int): Page {
        val starts = CARD.findAll(html).map { it.range.first }.toList()
        val gifs = starts.mapIndexedNotNull { i, s ->
            card(html.substring(s, if (i + 1 < starts.size) starts[i + 1] else html.length))
        }
        return Page(gifs, if ("class=\"pagination-next\"" in html) p + 1 else p, emptyList())
    }

    private fun card(b: String): Gif? {
        val vid = CARD.find(b)?.groupValues?.get(1) ?: return null
        val path = PATH.find(b)?.groupValues?.get(1) ?: return null
        val thumb = THUMB.find(b)?.groupValues?.get(1)?.let { if (it.startsWith("//")) "https:$it" else it } ?: return null
        return Gif(id = "yj:$vid", thumb = thumb, hd = Sources.lazy("youjizz", path, true), sd = Sources.lazy("youjizz", path, false),
            w = 1280, h = 720, duration = clockSeconds(TIME.find(b)?.groupValues?.get(1).orEmpty()), user = "", tags = emptyList(),
            hasAudio = true, views = VIEWS.find(b)?.let { XParse.count(it.groupValues[1]) } ?: 0, likes = 0,
            description = XParse.unescape(TITLE.find(b)?.groupValues?.get(1).orEmpty().trim()), site = "youjizz")
    }

    /** MP4 из dataEncodings страницы ролика (HLS-копии пропускаем); по возрастанию качества. Пусто — нет адресов. */
    internal fun parseEncodings(html: String): List<Encoding> {
        val json = ENCODINGS.find(html)?.groupValues?.get(1) ?: return emptyList()
        val arr = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val file = o.optString("filename")
            val h = o.optString("quality").toIntOrNull()
            if (h == null || file.isEmpty() || "_hls/" in file || ".m3u8" in file) return@mapNotNull null
            Encoding(h, if (file.startsWith("//")) "https:$file" else file)
        }.sortedBy { it.height }
    }

    /** Лучшее качество не выше cap; если все выше — самое лёгкое. */
    internal fun pick(list: List<Encoding>, cap: Int): String? = (list.lastOrNull { it.height <= cap } ?: list.firstOrNull())?.url
}

/** «10:38», «1:02:03», «45» -> секунды; мусор -> 0. */
internal fun clockSeconds(t: String): Double {
    val parts = t.trim().split(':').map { it.trim().toIntOrNull() ?: return 0.0 }
    return parts.fold(0.0) { acc, n -> acc * 60 + n }
}
