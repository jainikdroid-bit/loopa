package app.loopa

import org.json.JSONObject
import java.net.URLEncoder

/**
 * Eporner (eporner.com): официальный открытый API v2 без ключа — /api/v2/video/search (query, order, page, per_page).
 * Файл видео API не отдаёт; его даёт ссылка «скачать» сайта /dload/{id}/{высота}/{номер}-{высота}p.mp4 (переадресация
 * на CDN), где номер — тот же, что в адресе превью (…/thumbs/…/{номер}/5_360.jpg). lq=0 — только ролики в HD (720p есть всегда).
 */
object Eporner {
    const val SITE = "https://www.eporner.com"
    private const val PER_PAGE = 30
    private val NUM = Regex("""/(\d+)/\d+_\d+\.jpg""")

    /** q пусто — лучшее за неделю, иначе поиск по популярности; fresh — сначала новые. */
    fun page(q: String, fresh: Boolean, p: Int): Page {
        val order = when { fresh -> "latest"; q.isBlank() -> "top-weekly"; else -> "most-popular" }
        val url = "$SITE/api/v2/video/search/?query=" + URLEncoder.encode(q.trim().ifEmpty { "all" }, "UTF-8") +
            "&per_page=$PER_PAGE&page=$p&thumbsize=big&order=$order&gay=0&lq=0&format=json"
        val js = JSONObject(Net.retry {
            val c = Net.open(url)
            try {
                if (c.responseCode !in 200..299) throw Net.fail(c)
                c.inputStream.bufferedReader().use { it.readText() }
            } finally { c.disconnect() }
        })
        val arr = js.optJSONArray("videos")
        val gifs = (0 until (arr?.length() ?: 0)).mapNotNull { gif(arr!!.getJSONObject(it)) }
        val pages = js.optInt("total_pages", p)
        return Page(gifs, if (p < pages) p + 1 else p, emptyList())
    }

    private fun gif(v: JSONObject): Gif? {
        val id = v.optString("id").takeIf { it.isNotEmpty() } ?: return null
        val th = v.optJSONObject("default_thumb")
        val thumb = th?.optString("src").orEmpty()
        val num = NUM.find(thumb)?.groupValues?.get(1) ?: return null
        fun file(h: Int) = "$SITE/dload/$id/$h/$num-${h}p.mp4"
        val tags = v.optString("keywords").split(",").map { it.trim().lowercase() }.filter { it.isNotEmpty() && it.length <= 20 && it.count { c -> c == ' ' } <= 1 && it.none(Char::isDigit) }.distinct().take(12)
        val created = runCatching { java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.ROOT).parse(v.optString("added"))!!.time / 1000 }.getOrDefault(0L)
        return Gif(id = "ep:$id", thumb = thumb, hd = file(720), sd = file(360), w = 1280, h = 720,
            duration = v.optDouble("length_sec", 0.0), user = "", tags = tags, hasAudio = true,
            views = v.optLong("views"), likes = 0, description = v.optString("title"), created = created, site = "eporner")
    }
}
