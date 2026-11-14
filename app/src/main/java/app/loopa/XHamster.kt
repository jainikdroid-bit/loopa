package app.loopa

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.net.URLEncoder

/**
 * xHamster (xhamster.com): API нет, но в каждой странице лежит `window.initials = {...}` — JSON состояния страницы,
 * его и разбираем (а не HTML). Лента: q пусто — категория «amateur» (/categories/amateur/{N}, новые — /newest/{N}),
 * поиск — /search/{запрос}?page=N (новые — &sort=newest). Карточка: id, title, duration, views, created, pageURL,
 * thumbURL, landing.name (автор/канал, бывает null). Короткие «Moments» (/shorts/…) в выдаче пропускаем: у них нет
 * duration и другой формат страницы.
 *
 * Адрес видео в initials.xplayerSettings.sources зашифрован (hex), зато в <head> страницы ролика есть
 * `<link rel="preload" href="…/multi=…:144p:,…:720p:/…/_TPL_.av1.mp4.m3u8">` — HLS-мастер с подстановкой качества
 * вместо _TPL_. Подпись ссылки не зависит от имени файла: `720p.av1.mp4.m3u8` — плейлист нужного качества, а замена
 * av1 на h264 даёт H.264 (его декодирует любой телефон; AV1 — не всякий). У части хостов (video7…, с key=/data=
 * в пути) подпись привязана к имени файла и h264 даёт 403 — тогда берём AV1. Ссылка живёт ~3 часа и привязана к
 * IP, поэтому в ленте лежит ленивый адрес ([Sources.lazy]), а настоящий берёт [resolve] со страницы ролика.
 * Прогрессивного MP4 для обычных видео сайт не отдаёт — только HLS.
 */
object XHamster : Source {
    override val id = "xhamster"
    override val title = "xHamster"
    override val badge = "XH"
    override val hlsOnly = true
    override val about = "любительское и короткие Moments"

    const val SITE = "https://xhamster.com"
    private const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36"
    private const val CATEGORY = "$SITE/categories/amateur"
    /** Качество «обычного» ролика (HD) и лёгкого (SD): не выше этих высот. */
    private const val HD_MAX = 720
    private const val SD_MAX = 480

    override fun page(q: String, fresh: Boolean, p: Int): Page = parseList(get(listUrl(q, fresh, p)), p)

    /** Адрес страницы выдачи (чистая функция, чтобы проверять в тестах). */
    internal fun listUrl(q: String, fresh: Boolean, p: Int): String =
        if (q.isBlank()) CATEGORY + (if (fresh) "/newest" else "") + (if (p > 1) "/$p" else "")
        else "$SITE/search/" + URLEncoder.encode(q.trim(), "UTF-8") + "?" + (if (fresh) "sort=newest&" else "") + "page=$p"

    override fun resolve(key: String, hd: Boolean): Resolved? {
        require(key.startsWith("/videos/")) { "плохой ключ $key" }
        val tpl = parseTemplate(get(SITE + key)) ?: throw IllegalStateException("xHamster: на странице нет адреса видео")
        for (u in variants(tpl, hd)) if (works(u)) return Resolved(u)
        throw IllegalStateException("xHamster: адрес видео не отвечает")
    }

    private fun get(url: String): String = Net.retry {
        val c = Net.open(url)
        c.setRequestProperty("User-Agent", UA)
        try {
            if (c.responseCode !in 200..299) throw Net.fail(c)
            c.inputStream.bufferedReader().use { it.readText() }
        } finally { c.disconnect() }
    }

    /** Отвечает ли адрес (h264 на части хостов даёт 403 — тогда берём запасной). */
    private fun works(url: String): Boolean = runCatching {
        val c = Net.open(url)
        c.setRequestProperty("User-Agent", UA)
        try { c.responseCode in 200..299 } finally { c.disconnect() }
    }.getOrDefault(false)

    // ---- разбор (без сети) ----

    /** Карточки ролика из выдачи; pages — следующая страница (или эта же, если дальше нет). */
    internal fun parseList(html: String, p: Int): Page {
        val root = initials(html) ?: return Page(emptyList(), p, emptyList())
        val seen = HashSet<Long>()
        val gifs = ArrayList<Gif>()
        collect(root) { o -> if (seen.add(o.optLong("id"))) gif(o)?.let(gifs::add) }
        val last = lastPage(root)
        val more = if (last > 0) p < last else gifs.isNotEmpty()
        return Page(gifs, if (more) p + 1 else p, emptyList())
    }

    /** JSON из `window.initials=…;`; null — страницы с таким состоянием нет (блокировка, капча). */
    internal fun initials(html: String): JSONObject? {
        val i = html.indexOf("window.initials")
        if (i < 0) return null
        val eq = html.indexOf('=', i)
        if (eq < 0) return null
        return runCatching { JSONObject(JSONTokener(html.substring(eq + 1).trimStart())) }.getOrNull()
    }

    /** Обходит JSON и отдаёт каждый объект-карточку (есть pageURL, thumbURL и duration). */
    private fun collect(n: Any?, on: (JSONObject) -> Unit) {
        when (n) {
            is JSONObject -> if (n.has("pageURL") && n.has("thumbURL") && n.has("duration")) on(n)
                else n.keys().forEach { collect(n.opt(it), on) }
            is JSONArray -> for (i in 0 until n.length()) collect(n.opt(i), on)
        }
    }

    /** Номер последней страницы из paginationProps (его нет в поиске — тогда 0). */
    private fun lastPage(n: Any?): Int {
        when (n) {
            is JSONObject -> {
                n.optJSONObject("paginationProps")?.optInt("lastPageNumber", 0)?.takeIf { it > 0 }?.let { return it }
                n.keys().forEach { k -> lastPage(n.opt(k)).takeIf { it > 0 }?.let { return it } }
            }
            is JSONArray -> for (i in 0 until n.length()) lastPage(n.opt(i)).takeIf { it > 0 }?.let { return it }
        }
        return 0
    }

    private fun gif(o: JSONObject): Gif? {
        val vid = o.optLong("id").takeIf { it > 0 } ?: return null
        val url = o.optString("pageURL")
        if ("/videos/" !in url) return null   // /shorts/… (Moments) — другой формат страницы
        val key = url.substringAfter(SITE, "").takeIf { it.startsWith("/videos/") } ?: return null
        val thumb = o.optString("thumbURL").ifEmpty { o.optString("imageURL") }
        return Gif(id = "xh:$vid", thumb = thumb, hd = Sources.lazy(id, key, true), sd = Sources.lazy(id, key, false),
            w = 1280, h = 720, duration = o.optDouble("duration", 0.0),
            user = o.optJSONObject("landing")?.optString("name").orEmpty(), tags = emptyList(), hasAudio = true,
            views = o.optLong("views"), likes = 0, description = o.optString("title"), hls = true,
            created = o.optLong("created"), site = id)
    }

    private val TEMPLATE = Regex("""<link rel="preload" href="(https://[^"]*_TPL_[^"]*\.m3u8)"""")

    /** HLS-мастер с подстановкой _TPL_ со страницы ролика; null — не найден. */
    internal fun parseTemplate(html: String): String? = TEMPLATE.find(html)?.groupValues?.get(1)

    /** Качества, перечисленные в multi=…:144p:,…:720p: (по возрастанию). */
    internal fun heights(tpl: String): List<Int> =
        Regex("""multi=([^/]*)""").find(tpl)?.groupValues?.get(1)?.let { m ->
            Regex("""(\d+)p""").findAll(m).map { it.groupValues[1].toInt() }.distinct().sorted().toList()
        }.orEmpty()

    /** Адреса плейлиста нужного качества по убыванию предпочтения: H.264, затем AV1. */
    internal fun variants(tpl: String, hd: Boolean): List<String> {
        val hs = heights(tpl)
        val cap = if (hd) HD_MAX else SD_MAX
        val h = hs.lastOrNull { it <= cap } ?: hs.firstOrNull()
        val av1 = tpl.replace("_TPL_", if (h != null) "${h}p" else "_TPL_")
        return if (".av1." in av1) listOf(av1.replace(".av1.", ".h264."), av1) else listOf(av1)
    }
}
