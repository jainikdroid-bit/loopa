package app.loopa

import org.json.JSONArray
import org.json.JSONObject

/**
 * Общее для Pornhub и RedTube (одна сеть Aylo, одинаковые API для вебмастеров и страницы ролика).
 *
 * Выдача — JSON: список videos, элемент — сам ролик (Pornhub) или обёртка {"video": {…}} (RedTube).
 * Адрес видео лежит на странице ролика в flashvars → mediaDefinitions: запись format=mp4 ведёт не на файл, а на «индекс»
 * (Pornhub /video/get_media?s=…, RedTube /media/mp4?s=…) — JSON со списком MP4 по качествам. Ссылки подписаны, живут
 * около двух часов и привязаны к IP, поэтому в ленте лежит ленивый адрес ([Sources.lazy]), а настоящий берёт [resolveMp4].
 * CDN без Referer сайта и User-Agent отвечает 410/404.
 */
internal object Aylo {
    /** Браузерный User-Agent: без него страницы отвечают 403. */
    const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36"
    private const val MAX_TAGS = 12
    private const val MAX_TAG_LEN = 20

    /** Один вариант файла: высота кадра (0 — неизвестна) и адрес (абсолютный или от корня сайта). */
    class Media(val height: Int, val format: String, val url: String)

    /** Ответ сайта: тело и cookie из Set-Cookie (имя -> значение). */
    class Reply(val body: String, val cookies: Map<String, String>)

    // ---- разбор (без сети) ----

    /** "мм:сс" или "ч:мм:сс" в секунды; непонятное — 0. */
    fun seconds(s: String): Double {
        val parts = s.split(':').map { it.trim().toIntOrNull() ?: return 0.0 }
        return parts.fold(0) { acc, n -> acc * 60 + n }.toDouble()
    }

    /** Теги: короткие, в нижнем регистре, без цифр и длинных фраз (как у Eporner); [key] — поле имени в объекте тега. */
    fun tags(arr: JSONArray?, key: String): List<String> =
        (0 until (arr?.length() ?: 0)).mapNotNull { arr!!.optJSONObject(it)?.optString(key) }
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() && it.length <= MAX_TAG_LEN && it.count { c -> c == ' ' } <= 1 && it.none(Char::isDigit) }
            .distinct().take(MAX_TAGS)

    /**
     * Ролики из ответа поиска; [prefix] — префикс [Gif.id]. Пустой список и для ответа-ошибки
     * ({"code":"2001","message":"No Videos found!"} у Pornhub). Ролики без id или превью пропускаются.
     */
    fun parseList(json: String, site: String, prefix: String): List<Gif> {
        val arr = JSONObject(json).optJSONArray("videos") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val item = arr.optJSONObject(i) ?: return@mapNotNull null
            gif(item.optJSONObject("video") ?: item, site, prefix)
        }
    }

    private fun gif(v: JSONObject, site: String, prefix: String): Gif? {
        val key = v.optString("video_id").takeIf { it.isNotEmpty() } ?: return null
        val thumb = v.optString("default_thumb").ifEmpty { v.optString("thumb") }.takeIf { it.isNotEmpty() } ?: return null
        val created = runCatching {
            java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.ROOT).parse(v.optString("publish_date"))!!.time / 1000
        }.getOrDefault(0L)
        val likes = (v.optDouble("rating", 0.0) / 100 * v.optLong("ratings")).toLong().coerceAtLeast(0)
        val star = v.optJSONArray("pornstars")?.optJSONObject(0)?.optString("pornstar_name").orEmpty()
        return Gif(id = "$prefix:$key", thumb = thumb, hd = Sources.lazy(site, key, true), sd = Sources.lazy(site, key, false),
            w = 1280, h = 720, duration = seconds(v.optString("duration")), user = star, tags = tags(v.optJSONArray("tags"), "tag_name"),
            hasAudio = true, views = v.optLong("views"), likes = likes, description = v.optString("title"), created = created, site = site)
    }

    /**
     * Записи mediaDefinitions со страницы ролика (первый массив): format, videoUrl, quality (строка или число).
     * Массив вырезается по скобкам с учётом строк — на странице он идёт среди сотен килобайт другого JS.
     */
    fun parseMediaDefinitions(page: String): List<Media> {
        val at = page.indexOf("\"mediaDefinitions\":[")
        if (at < 0) return emptyList()
        val start = page.indexOf('[', at)
        var depth = 0
        var inStr = false
        var i = start
        while (i < page.length) {
            val c = page[i]
            when {
                inStr -> if (c == '\\') i++ else if (c == '"') inStr = false
                c == '"' -> inStr = true
                c == '[' -> depth++
                c == ']' -> if (--depth == 0) return mediaList(page.substring(start, i + 1))
            }
            i++
        }
        return emptyList()
    }

    /** Список {format, quality, videoUrl} из mediaDefinitions или ответа get_media; битый JSON — пустой список. */
    fun mediaList(json: String): List<Media> = runCatching {
        val arr = JSONArray(json)
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val url = o.optString("videoUrl").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            Media(o.optString("quality").toIntOrNull() ?: o.optInt("height", 0), o.optString("format"), url)
        }
    }.getOrDefault(emptyList())

    /** hd — 720p, иначе лучшее до 1080p (выше не берём), иначе что есть; sd — 480p, иначе лучшее ниже, иначе самое лёгкое. */
    fun pick(list: List<Media>, hd: Boolean): Media? {
        val sorted = list.sortedBy { it.height }
        val want = if (hd) 720 else 480
        sorted.firstOrNull { it.height == want }?.let { return it }
        return if (hd) sorted.lastOrNull { it.height <= 1080 } ?: sorted.firstOrNull()
        else sorted.lastOrNull { it.height in 1 until want } ?: sorted.firstOrNull()
    }

    // ---- сеть ----

    /** GET с браузерными заголовками и cookie; ошибка HTTP — исключение (Net.retry повторит временные). */
    fun get(url: String, cookies: Map<String, String> = emptyMap()): Reply = Net.retry {
        val c = Net.open(url)
        try {
            c.setRequestProperty("User-Agent", UA)
            c.setRequestProperty("Accept-Language", "en-US,en;q=0.9")
            if (cookies.isNotEmpty()) c.setRequestProperty("Cookie", cookies.entries.joinToString("; ") { "${it.key}=${it.value}" })
            if (c.responseCode !in 200..299) throw Net.fail(c)
            val set = (c.headerFields["Set-Cookie"] ?: emptyList<String>()).mapNotNull { h ->
                h.substringBefore(';').split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0].trim() to it[1].trim() }
            }.toMap()
            Reply(c.inputStream.bufferedReader().use { it.readText() }, set)
        } finally { c.disconnect() }
    }

    /**
     * Настоящий MP4 ролика: страница [pageUrl] → mediaDefinitions → индекс MP4 (cookie сессии «ss» со страницы обязателен
     * для Pornhub) → [pick]. [site] — корень сайта (для относительных адресов RedTube и Referer), [base] — cookie
     * подтверждения возраста. null — на странице нет MP4 (например, только HLS).
     */
    fun resolveMp4(pageUrl: String, site: String, base: Map<String, String>, hd: Boolean): Resolved? {
        val page = get(pageUrl, base)
        val session = base + page.cookies
        val files = parseMediaDefinitions(page.body).filter { it.format == "mp4" }.flatMap { d ->
            val url = if (d.url.startsWith("/")) site + d.url else d.url
            // Запись со ссылкой на индекс (get_media, /media/mp4) раскрывается вторым запросом, прямая — уже файл.
            if (url.contains("/get_media") || url.contains("/media/mp4")) mediaList(get(url, session).body) else listOf(Media(d.height, "mp4", url))
        }.filter { it.format == "mp4" && it.url.startsWith("http") }
        val m = pick(files, hd) ?: return null
        return Resolved(m.url, mapOf("Referer" to "$site/", "User-Agent" to UA))
    }
}
