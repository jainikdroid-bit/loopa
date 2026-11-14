package app.loopa

import java.net.URLEncoder

/**
 * xVideos (xvideos.com) и XNXX (xnxx.com) — один движок и одна вёрстка, поэтому разбор общий ([XTube], [XParse]).
 * API нет — страницы сайта. Выдача: карточки `thumb-block` (id, путь страницы, название, превью, длительность, просмотры,
 * канал). Адрес видео в выдаче не отдаётся: на странице ролика в скрипте html5player лежат setVideoUrlHigh / setVideoUrlLow —
 * подписанные MP4 (подпись живёт часы), поэтому в [Gif.hd]/[Gif.sd] кладётся ленивый адрес [Sources.lazy], а [resolve]
 * открывает страницу. MP4 отдаётся по Range (206) без Referer и особого User-Agent. Страницы в адресах считаются с нуля.
 */
internal abstract class XTube : Source {
    abstract val host: String
    /** Префикс id: «xv» / «xn». */
    abstract val prefix: String
    /** Адрес страницы ленты; p — номер с 1. */
    abstract fun url(q: String, fresh: Boolean, p: Int): String

    override fun page(q: String, fresh: Boolean, p: Int): Page {
        val html = get(url(q.trim(), fresh, p))
        return Page(XParse.list(html, prefix, id), if (XParse.hasNext(html, p)) p + 1 else p, emptyList())
    }

    /** hd — High (лучшее качество), иначе Low; нет одного — берём другой. */
    override fun resolve(key: String, hd: Boolean): Resolved? {
        val v = XParse.video(get(host + key)) ?: return null
        val url = (if (hd) v.high ?: v.low else v.low ?: v.high) ?: return null
        return Resolved(url)
    }

    private fun get(url: String): String = Net.retry {
        val c = Net.open(url)
        c.setRequestProperty("User-Agent", XParse.UA)
        c.setRequestProperty("Cookie", "age_verified=1")
        try {
            if (c.responseCode !in 200..299) throw Net.fail(c)
            c.inputStream.bufferedReader().use { it.readText() }
        } finally { c.disconnect() }
    }
}

internal object XVideos : XTube() {
    override val id = "xvideos"
    override val title = "xVideos"
    override val badge = "XV"
    override val about = "любительское и всё подряд"
    override val host = "https://www.xvideos.com"
    override val prefix = "xv"

    /** Пусто — раздел /tags/amateur/{N}; поиск — /?k=…&p=N; fresh — сортировка по дате загрузки (sort=uploaddate). */
    override fun url(q: String, fresh: Boolean, p: Int): String {
        val n = p - 1
        if (q.isEmpty() && !fresh) return "$host/tags/amateur" + if (n > 0) "/$n" else ""
        val k = URLEncoder.encode(q.ifEmpty { "amateur" }, "UTF-8")
        return "$host/?k=$k" + (if (fresh) "&sort=uploaddate" else "") + "&p=$n"
    }
}

/** Разбор страниц xVideos/XNXX без сети. */
internal object XParse {
    const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36"
    /** В отсортированной выдаче вместо номера кадра стоит заглушка THUMBNUM (и в пути, и в превью). */
    private const val PLACEHOLDER = "THUMBNUM"
    private const val FRAME = "15"

    class Video(val title: String, val high: String?, val low: String?)

    private val CARD = Regex("<div id=\"video_")
    private val EID = Regex("data-eid=\"([^\"]+)\"")
    private val PATH = Regex("<div class=\"thumb\"><a href=\"([^\"]+)\"")
    private val THUMB = Regex("data-src=\"([^\"]+)\"")
    private val TITLE = Regex("<p[^>]*>\\s*<a href=\"[^\"]*\" title=\"([^\"]*)\"")
    private val DUR_SPAN = Regex("<span class=\"duration\">([^<]*)</span>")
    private val RIGHT = Regex("<span class=\"right\">.*?</span></span>", RegexOption.DOT_MATCHES_ALL)
    private val VIEWS = Regex("([\\d.,]+\\s*[kKmM]?)\\s*<span class=\"(?:sprfluous\">Views|icon-f icf-eye)")
    private val NAME = Regex("<span class=\"name\">([^<]*)</span>")
    private val TAGS = Regex("<[^>]*>")
    private val HOURS = Regex("(\\d+)\\s*h\\b")
    private val MINUTES = Regex("(\\d+)\\s*min")
    private val SECONDS = Regex("(\\d+)\\s*sec")
    private val HIGH = Regex("setVideoUrlHigh\\('([^']+)'\\)")
    private val LOW = Regex("setVideoUrlLow\\('([^']+)'\\)")
    private val V_TITLE = Regex("setVideoTitle\\('((?:[^'\\\\]|\\\\.)*)'\\)")

    /** Карточки выдачи; без eid, пути или превью карточку пропускаем. */
    fun list(html: String, prefix: String, site: String): List<Gif> {
        val starts = CARD.findAll(html).map { it.range.first }.toList()
        return starts.mapIndexedNotNull { i, s ->
            card(html.substring(s, if (i + 1 < starts.size) starts[i + 1] else html.length), prefix, site)
        }
    }

    private fun card(b: String, prefix: String, site: String): Gif? {
        val eid = EID.find(b)?.groupValues?.get(1) ?: return null
        val path = PATH.find(b)?.groupValues?.get(1)?.replace("/$PLACEHOLDER", "") ?: return null
        val thumb = THUMB.find(b)?.groupValues?.get(1)?.replace(PLACEHOLDER, FRAME) ?: return null
        val meta = b.substringAfter("class=\"metadata\"", "")
        // у xVideos длительность в span.duration, у XNXX — голым текстом после блока просмотров
        val dur = DUR_SPAN.find(b)?.groupValues?.get(1) ?: RIGHT.replace(meta, " ").replace(TAGS, " ")
        return Gif(id = "$prefix:$eid", thumb = thumb, hd = Sources.lazy(site, path, true), sd = Sources.lazy(site, path, false),
            w = 1280, h = 720, duration = seconds(dur), user = unescape(NAME.find(b)?.groupValues?.get(1).orEmpty().trim()),
            tags = emptyList(), hasAudio = true, views = VIEWS.find(b)?.let { count(it.groupValues[1]) } ?: 0, likes = 0,
            description = unescape(TITLE.find(b)?.groupValues?.get(1).orEmpty().trim()), site = site)
    }

    /** Есть ли в блоке пагинации ссылка на следующую страницу (номера в адресах с нуля, значит, следующая — p). */
    fun hasNext(html: String, p: Int): Boolean {
        val pag = html.substringAfter("class=\"pagination", "").substringBefore("</ul>")
        return Regex("[/=]$p\"").containsMatchIn(pag)
    }

    /** Страница ролика: адреса High/Low и название. null — на странице нет ни одного адреса. */
    fun video(html: String): Video? {
        val high = HIGH.find(html)?.groupValues?.get(1)
        val low = LOW.find(html)?.groupValues?.get(1)
        if (high == null && low == null) return null
        return Video(unescape(V_TITLE.find(html)?.groupValues?.get(1).orEmpty()), high, low)
    }

    /** «16 min», «1 h 6 min», «45 sec», «6min» -> секунды. */
    fun seconds(t: String): Double =
        (HOURS.find(t)?.groupValues?.get(1)?.toIntOrNull() ?: 0) * 3600.0 +
            (MINUTES.find(t)?.groupValues?.get(1)?.toIntOrNull() ?: 0) * 60.0 +
            (SECONDS.find(t)?.groupValues?.get(1)?.toIntOrNull() ?: 0)

    /** «3.7M», «916.1k», «1,234» -> число. */
    fun count(s: String): Long {
        val t = s.trim().replace(",", "")
        val v = t.trimEnd('k', 'K', 'm', 'M').toDoubleOrNull() ?: return 0
        return (v * when (t.last().lowercaseChar()) { 'k' -> 1_000; 'm' -> 1_000_000; else -> 1 }).toLong()
    }

    private val NAMED = mapOf("amp" to "&", "quot" to "\"", "apos" to "'", "lt" to "<", "gt" to ">", "nbsp" to " ",
        "ntilde" to "ñ", "Ntilde" to "Ñ", "aacute" to "á", "eacute" to "é", "iacute" to "í", "oacute" to "ó", "uacute" to "ú",
        "agrave" to "à", "egrave" to "è", "ograve" to "ò", "acirc" to "â", "ecirc" to "ê", "ocirc" to "ô", "ccedil" to "ç",
        "auml" to "ä", "ouml" to "ö", "uuml" to "ü", "szlig" to "ß", "atilde" to "ã", "otilde" to "õ", "iuml" to "ï")
    private val ENTITY = Regex("&(?:#(\\d+)|#[xX]([0-9a-fA-F]+)|([A-Za-z]+));")

    /** HTML-сущности в названиях: числовые и самые частые именованные; незнакомые оставляем как есть. */
    fun unescape(s: String): String = ENTITY.replace(s) { m ->
        val dec = m.groupValues[1].toIntOrNull()
        val hex = m.groupValues[2].toIntOrNull(16)
        when {
            dec != null -> String(Character.toChars(dec))
            hex != null -> String(Character.toChars(hex))
            else -> NAMED[m.groupValues[3]] ?: m.value
        }
    }
}
