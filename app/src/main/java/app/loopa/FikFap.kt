package app.loopa

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * FikFap (fikfap.com): короткие вертикальные ролики, открытый API api.fikfap.com для гостей. Без заголовков
 * Authorization-Anonymous (любой UUID, один на запуск), IsLoggedIn, Origin, Referer и браузерного User-Agent — 401/403.
 *
 * Ленты (amount — размер страницы, afterId — курсор: id последнего ролика прошлой страницы):
 *  - q пусто: /posts?sort=random — случайные ролики со всей базы (курсор не нужен, страницы бесконечны);
 *    «глобальный top» с afterId отвечает 500, поэтому популярное — случайная выборка;
 *  - q пусто + fresh: /posts?sort=new — новые по убыванию id;
 *  - поиск — хэштег: /hashtags/label/{тег}/posts?sort=top|new (тег — запрос в нижнем регистре без пробелов; у сайта
 *    поиск устроен так же). Нет такого хэштега (404) — запасной /search/posts?q= (10 лучших, без страниц).
 * Номера страниц Loopa превращаются в курсоры через [cursors] (в памяти; после перезапуска страницы >1 не восстановить).
 *
 * videoStreamUrl — HLS bunny CDN (vz-….b-cdn.net, мастер 240p…1080p, звук отдельно) с подписью, которая истекает
 * примерно через час, поэтому в ленте лежит ленивый адрес ([Sources.lazy], ключ — postId), а [resolve] берёт свежий из
 * /posts/{id}. Плейлисты, сегменты и превью с *.b-cdn.net отдаются ТОЛЬКО с Referer https://fikfap.com/ (без него 403) —
 * плеер и загрузчик картинок должны подставлять его для адресов, где [ours] истинно (как для Erome).
 */
object FikFap : Source {
    override val id = "fikfap"
    override val title = "FikFap"
    override val badge = "FF"
    override val hlsOnly = true
    override val about = "короткие вертикальные любительские ролики"
    override val default = false

    const val API = "https://api.fikfap.com"
    const val REFERER = "https://fikfap.com/"
    private const val ORIGIN = "https://fikfap.com"
    private const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36"
    private const val AMOUNT = 21
    private const val MAX_TAGS = 12

    /** Гостевой идентификатор: один на запуск приложения. */
    private val anon = UUID.randomUUID().toString()

    /** Курсоры страниц: «режим|запрос» -> (номер страницы -> afterId, с которым её загрузили). */
    private val cursors = ConcurrentHashMap<String, ConcurrentHashMap<Int, Long>>()

    /** Адрес отдаётся CDN FikFap (нужен Referer). */
    fun ours(url: String) = runCatching { java.net.URI(url).host.orEmpty() }.getOrDefault("").let { it == "b-cdn.net" || it.endsWith(".b-cdn.net") }

    override fun page(q: String, fresh: Boolean, p: Int): Page {
        val tag = tagOf(q)
        val random = tag.isEmpty() && !fresh
        val key = "${if (fresh) "new" else "top"}|$tag"
        val after = if (p > 1 && !random) cursors[key]?.get(p) ?: return Page(emptyList(), p, emptyList()) else null
        val sort = if (random) "random" else if (fresh) "new" else "top"
        val base = if (tag.isEmpty()) "posts" else "hashtags/label/" + URLEncoder.encode(tag, "UTF-8") + "/posts"
        val url = "$API/$base?amount=$AMOUNT&sort=$sort" + (if (after != null) "&afterId=$after" else "")
        val gifs = try {
            parsePosts(get(url))
        } catch (e: HttpError) {
            // нет такого хэштега — ищем по тексту (одна страница)
            if (e.code != 404 || tag.isEmpty()) throw e
            if (p > 1) return Page(emptyList(), p, emptyList())
            return Page(parsePosts(get("$API/search/posts?q=" + URLEncoder.encode(q.trim(), "UTF-8"))), p, emptyList())
        }
        val last = gifs.lastOrNull()?.id?.removePrefix("ff:")?.toLongOrNull()
        if (last != null && !random) cursors.getOrPut(key) { ConcurrentHashMap() }[p + 1] = last
        // случайная лента бесконечна; остальные кончаются на пустой странице
        return Page(gifs, if (gifs.isNotEmpty()) p + 1 else p, emptyList())
    }

    /** Запрос -> имя хэштега: без «#» и пробелов, в нижнем регистре. */
    internal fun tagOf(q: String): String = q.trim().removePrefix("#").lowercase().filter { !it.isWhitespace() }

    override fun resolve(key: String, hd: Boolean): Resolved? {
        require(key.isNotEmpty() && key.all { it.isDigit() }) { "плохой ключ $key" }
        val url = JSONObject(get("$API/posts/$key")).optString("videoStreamUrl")
        if (url.isEmpty()) throw IllegalStateException("FikFap: у ролика нет видео")
        return Resolved(url, mapOf("Referer" to REFERER))
    }

    private fun get(url: String): String = Net.retry {
        val c = Net.open(url)
        c.setRequestProperty("User-Agent", UA)
        c.setRequestProperty("Authorization-Anonymous", anon)
        c.setRequestProperty("IsLoggedIn", "false")
        c.setRequestProperty("Origin", ORIGIN)
        c.setRequestProperty("Referer", REFERER)
        try {
            if (c.responseCode !in 200..299) throw Net.fail(c)
            c.inputStream.bufferedReader().use { it.readText() }
        } finally { c.disconnect() }
    }

    // ---- разбор (без сети) ----

    /** Ответ ленты: массив постов или {"posts":[…]} (поиск). Рекламные (isBoosted) и ещё не обработанные пропускаются. */
    internal fun parsePosts(json: String): List<Gif> {
        val t = json.trimStart()
        val arr = if (t.startsWith("[")) JSONArray(t) else JSONObject(t).optJSONArray("posts") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { gif(arr.getJSONObject(it)) }
    }

    private fun gif(o: JSONObject): Gif? {
        val pid = o.optLong("postId").takeIf { it > 0 } ?: return null
        if (o.optBoolean("isBoosted") || !o.optBoolean("isBunnyVideoReady", true) || o.optString("videoStreamUrl").isEmpty()) return null
        val tags = o.optJSONArray("hashtags")?.let { a ->
            (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optString("labelLower")?.trim()?.lowercase() }
                .filter { it.isNotEmpty() && it.length <= 20 && it.none(Char::isWhitespace) }.distinct().take(MAX_TAGS)
        }.orEmpty()
        // вертикальные 720x1280 (размеров в ответе нет); адрес подписан на час — отдаём ленивый, оба качества одним ключом
        val lazy = Sources.lazy(id, pid.toString(), true)
        return Gif(id = "ff:$pid", thumb = o.optString("thumbnailStreamUrl"), hd = lazy, sd = lazy, w = 720, h = 1280,
            duration = o.optDouble("duration", 0.0), user = o.optJSONObject("author")?.optString("username").orEmpty(),
            tags = tags, hasAudio = true, views = o.optLong("viewsCount"), likes = o.optLong("likesCount"),
            description = o.optString("label").replace('\n', ' ').trim(), hls = true,
            created = iso(o.optString("publishedAt").ifEmpty { o.optString("createdAt") }), site = id)
    }

    /** «2026-11-14T07:29:16.259Z» -> unix-секунды (0 — не разобрать). */
    internal fun iso(s: String): Long = runCatching {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }.parse(s.take(19))!!.time / 1000
    }.getOrDefault(0L)
}
