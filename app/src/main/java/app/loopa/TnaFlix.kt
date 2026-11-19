package app.loopa

import java.net.URLEncoder

/**
 * TNAFlix (tnaflix.com) и EMPFlix (empflix.com) — одна сеть Flix, одна вёрстка, поэтому движок общий ([FlixTube], [FlixParse]).
 * API нет — страницы сайта. Выдача: карточки `data-vid` (id, путь страницы, название, превью, длительность, макс. качество,
 * автор, просмотры). Адрес видео в выдаче не отдаётся: на странице ролика в `<video>` лежат `<source type="video/mp4" size="…">`
 * для каждого качества — MP4 с подписью `secure=…,{unix-время}` (живёт около 3 часов), поэтому в [Gif.hd]/[Gif.sd] кладётся
 * ленивый адрес [Sources.lazy], а [resolve] открывает страницу. MP4 отдаётся по Range (206) без Referer и особого User-Agent;
 * с испорченной подписью — 403. Постраничность: /amateur-porn/{раздел}/N и /search?what=…&page=N, следующая страница есть,
 * пока в пагинации есть `pagination-next` (за последней страницей сайт отдаёт первую, поэтому номер не угадываем).
 * Заставки в начале роликов нет (замер 2026-11-19: по 10 роликов каждого сайта, общих первых кадров нет).
 */
internal abstract class FlixTube : Source {
    abstract val host: String
    override val home get() = host
    /** Префикс id: «tf» / «ef». */
    abstract val prefix: String
    /** Раздел «новые» любительского: у TNAFlix most-recent, у EMPFlix new. */
    abstract val newSection: String

    /**
     * q пусто — любительский раздел (featured, при fresh — новые), иначе поиск. У поиска сортировки по дате нет
     * (параметры sort/u сайт игнорирует), поэтому fresh на поиск не влияет.
     */
    fun url(q: String, fresh: Boolean, p: Int): String {
        if (q.isEmpty()) return "$host/amateur-porn/" + (if (fresh) newSection else "featured") + if (p > 1) "/$p" else ""
        return "$host/search?what=" + URLEncoder.encode(q, "UTF-8") + if (p > 1) "&page=$p" else ""
    }

    override fun page(q: String, fresh: Boolean, p: Int): Page {
        val html = get(url(q.trim(), fresh, p))
        return Page(FlixParse.list(html, prefix, id), if (FlixParse.hasNext(html)) p + 1 else p, emptyList())
    }

    /** hd — самое высокое качество, иначе лёгкое (до 480p). */
    override fun resolve(key: String, hd: Boolean): Resolved? {
        val files = FlixParse.files(get(host + key))
        val url = (if (hd) files.lastOrNull() else FlixParse.light(files))?.second ?: return null
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
}

internal object TnaFlix : FlixTube() {
    override val id = "tnaflix"
    override val title = "TNAFlix"
    override val badge = "TF"
    override val about = "любительское и всё подряд"
    override val host = "https://www.tnaflix.com"
    override val prefix = "tf"
    override val newSection = "most-recent"
}

internal object EmpFlix : FlixTube() {
    override val id = "empflix"
    override val title = "EMPFlix"
    override val badge = "EF"
    override val about = "та же сеть, что TNAFlix"
    override val host = "https://www.empflix.com"
    override val prefix = "ef"
    override val newSection = "new"
}

/** Разбор страниц TNAFlix/EMPFlix без сети. */
internal object FlixParse {
    private val CARD = Regex("<div data-vid=\"")
    private val VID = Regex("^<div data-vid=\"(\\d+)\"")
    private val PATH = Regex("href=\"https?://[^/\"]+(/[^\"]*/video\\d+)\"")
    private val IMG = Regex("<img[^>]*>")
    private val DATA_SRC = Regex("data-src=\"(https?://[^\"]+)\"")
    private val SRC = Regex("\\ssrc=\"(https?://[^\"]+)\"")
    private val DURATION = Regex("video-duration\">\\s*([\\d:]+)\\s*<")
    private val QUALITY = Regex("max-quality\">\\s*(\\d+)p")
    private val TITLE = Regex("class=\"video-title[^\"]*\">\\s*([^<]*?)\\s*</a>")
    private val USER = Regex("/profile/[^\"]*\"[^>]*>\\s*([^<]*?)\\s*</a>")
    private val VIEWS = Regex("icon-eye\"></i>\\s*([\\d.,]+\\s*[kKmM]?)")
    private val SOURCE = Regex("<source src=\"([^\"]+)\"\\s+type=\"video/mp4\"\\s+size=\"(\\d+)\"")

    /** Карточки выдачи; без id, пути или превью карточку пропускаем. */
    fun list(html: String, prefix: String, site: String): List<Gif> {
        val starts = CARD.findAll(html).map { it.range.first }.toList()
        return starts.mapIndexedNotNull { i, s ->
            card(html.substring(s, if (i + 1 < starts.size) starts[i + 1] else html.length), prefix, site)
        }
    }

    private fun card(b: String, prefix: String, site: String): Gif? {
        val vid = VID.find(b)?.groupValues?.get(1) ?: return null
        val path = PATH.find(b)?.groupValues?.get(1) ?: return null
        // у первых карточек превью в src, у ленивых — в data-src (в src заглушка без хоста)
        val img = IMG.find(b)?.value.orEmpty()
        val thumb = DATA_SRC.find(img)?.groupValues?.get(1) ?: SRC.find(img)?.groupValues?.get(1) ?: return null
        // размер видео по метке макс. качества (16:9); нет метки — 1280x720
        val hh = QUALITY.find(b)?.groupValues?.get(1)?.toIntOrNull() ?: 720
        return Gif(id = "$prefix:$vid", thumb = thumb, hd = Sources.lazy(site, path, true), sd = Sources.lazy(site, path, false),
            w = hh * 16 / 9, h = hh, duration = clock(DURATION.find(b)?.groupValues?.get(1).orEmpty()),
            user = XParse.unescape(USER.find(b)?.groupValues?.get(1).orEmpty()), tags = emptyList(), hasAudio = true,
            views = VIEWS.find(b)?.let { XParse.count(it.groupValues[1]) } ?: 0, likes = 0,
            description = XParse.unescape(TITLE.find(b)?.groupValues?.get(1).orEmpty()), site = site)
    }

    /** Есть ли в пагинации кнопка «Next». */
    fun hasNext(html: String): Boolean = html.contains("pagination-next")

    /** Страница ролика: пары (высота, адрес MP4) по возрастанию высоты; пусто — на странице нет видео. */
    fun files(html: String): List<Pair<Int, String>> =
        SOURCE.findAll(html).mapNotNull { m -> m.groupValues[2].toIntOrNull()?.let { it to m.groupValues[1].replace("&amp;", "&") } }
            .distinctBy { it.first }.sortedBy { it.first }.toList()

    /** Лёгкое качество: самое высокое не выше 480p, а если таких нет — самое низкое. */
    fun light(files: List<Pair<Int, String>>): Pair<Int, String>? = files.lastOrNull { it.first <= 480 } ?: files.firstOrNull()

    /** «10:01», «1:02:03», «45» -> секунды; непонятное -> 0. */
    fun clock(t: String): Double {
        var acc = 0.0
        for (part in t.split(':').filter { it.isNotBlank() }) acc = acc * 60 + (part.trim().toIntOrNull() ?: return 0.0)
        return acc
    }
}
