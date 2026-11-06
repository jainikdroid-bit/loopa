package app.loopa

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Ролик: превью, HD/SD mp4, теги, автор. Пост Reddit ([reddit]): sub — сабреддит, hls — поток v.redd.it (HD = SD = адрес
 * плейлиста), dash — манифест v.redd.it для скачивания (видео и звук раздельно), likes — рейтинг поста.
 */
data class Gif(
    val id: String, val thumb: String, val hd: String, val sd: String,
    val w: Int, val h: Int, val duration: Double, val user: String, val tags: List<String>,
    val hasAudio: Boolean, val views: Long, val likes: Long, val description: String = "",
    val sub: String = "", val hls: Boolean = false, val dash: String = "",
) {
    val reddit get() = sub.isNotEmpty()
}

/** Страница выдачи: ролики, сколько всего страниц и актуальные теги (лента присылает их сама). */
class Page(val gifs: List<Gif>, val pages: Int, val tags: List<String>)

/**
 * API RedGIFs (v2): временный токен без входа (/v2/auth/temporary), лента /v2/feeds/trending/popular, поиск
 * /v2/gifs/search, видео автора /v2/users/{имя}/search, подсказки /v2/search/suggest. Запросы — по образцу
 * открытой обёртки scrazzz/redgifs. Рекламные ролики (boosted_gifs, с полем cta) в выдачу не берём.
 */
object Api {
    private const val BASE = "https://api.redgifs.com"
    @Volatile private var token: String? = null
    @Volatile private var tokenAt = 0L
    private var prefs: SharedPreferences? = null
    /** Сессия для лент (сайт шлёт X-Session-Id — номер сессии аналитики, время начала в мс). */
    private val session = System.currentTimeMillis().toString()

    fun init(ctx: Context) { if (prefs == null) prefs = ctx.applicationContext.getSharedPreferences("loopa", Context.MODE_PRIVATE) }

    /** Токен вошедшего пользователя (из LoginActivity) и когда истекает (unix, с). */
    fun setUser(ctx: Context, t: String, exp: Long) { init(ctx); prefs!!.edit().putString("user_token", t).putLong("user_exp", exp).apply() }
    fun logout() { prefs?.edit()?.remove("user_token")?.remove("user_exp")?.apply() }
    private fun userToken(): String? {
        val p = prefs ?: return null
        val t = p.getString("user_token", null) ?: return null
        val exp = p.getLong("user_exp", 0)
        return if (exp == 0L || exp > System.currentTimeMillis() / 1000 + 60) t else null
    }
    val loggedIn get() = userToken() != null
    /** Был вход (токен сохранён) — даже если он истёк: тогда его продлевает [Renewer]. */
    val hadUser get() = prefs?.getString("user_token", null) != null
    /** Был вход, но токен истёк. */
    val expired get() = hadUser && userToken() == null
    /** Вход стоит продлить заранее: токен истёк или истекает в ближайшие [RENEW_AHEAD] с. */
    val renewDue get() = hadUser && prefs!!.getLong("user_exp", 0).let { it != 0L && it < System.currentTimeMillis() / 1000 + RENEW_AHEAD }
    private const val RENEW_AHEAD = 3 * 3600

    private fun auth(): String {
        userToken()?.let { return it }
        token?.takeIf { System.currentTimeMillis() - tokenAt < 12 * 3600_000L }?.let { return it }
        val t = JSONObject(http("$BASE/v2/auth/temporary", null)).getString("token")
        token = t; tokenAt = System.currentTimeMillis()
        return t
    }

    private fun get(path: String): String = try {
        http(BASE + path, auth())
    } catch (e: AuthExpired) {
        if (userToken() != null) { prefs?.edit()?.putLong("user_exp", 1)?.apply(); throw RuntimeException(LOGIN_EXPIRED) }
        token = null
        http(BASE + path, auth())
    }

    /** Запрос с телом JSON; null — успех, иначе текст ошибки сервера («HTTP 400: …»). */
    private fun send(method: String, path: String, body: JSONObject): String? {
        val c = Net.open(BASE + path)
        c.requestMethod = method; c.setRequestProperty("Authorization", "Bearer ${auth()}")
        c.setRequestProperty("X-Session-Id", session)
        c.doOutput = true; c.setRequestProperty("Content-Type", "application/json")
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        return try {
            val code = c.responseCode
            if (code in 200..299) null
            else "HTTP $code: " + runCatching { c.errorStream?.bufferedReader()?.readText().orEmpty() }.getOrDefault("").let { t ->
                runCatching { JSONObject(t).optJSONObject("error")?.let { it.optString("message").ifEmpty { it.optString("code") } } }.getOrNull() ?: t.take(120)
            }
        } finally { c.disconnect() }
    }

    /**
     * Лайк (нужен вход) — как на сайте: PUT /v2/gifs/{id}/like с {context, source, source_id, position}, снять —
     * DELETE с {context}. source/context — раздел, откуда лайк (for-you, trending, search-gifs, tag-gifs, profile, …).
     * null — прошёл, иначе ответ сервера.
     */
    fun like(id: String, on: Boolean, source: String, position: Int): String? =
        if (on) send("PUT", "/v2/gifs/${enc(id)}/like", JSONObject().put("context", source).put("source", source).put("source_id", JSONObject.NULL).put("position", position))
        else send("DELETE", "/v2/gifs/${enc(id)}/like", JSONObject().put("context", source))

    const val LOGIN_EXPIRED = "вход истёк — обновляю…"

    private class AuthExpired : Exception()

    /** GET с повторами при обрывах и 429/5xx ([Net.retry]); 401 — токен истёк ([AuthExpired], без повторов). */
    private fun http(url: String, bearer: String?): String = Net.retry {
        val c = Net.open(url)
        bearer?.let { c.setRequestProperty("Authorization", "Bearer $it"); c.setRequestProperty("X-Session-Id", session) }
        try {
            if (c.responseCode == 401) throw AuthExpired()
            if (c.responseCode !in 200..299) throw Net.fail(c)
            c.inputStream.bufferedReader().readText()
        } finally { c.disconnect() }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    /**
     * mode: foryou — рекомендации по лайкам (нужен вход), liked — лайкнутое; text пустой — популярное; author — видео
     * автора; иначе поиск: byTag — по тегам (tags=, через запятую — все сразу), иначе по тексту (query= — как поиск на
     * сайте: названия, описания, теги; при малом числе точных совпадений сайт добивает выдачу похожим).
     */
    fun page(text: String, order: String, page: Int, author: String? = null, mode: String? = null, byTag: Boolean = true): Page {
        val path = when {
            mode == "foryou" -> "/v2/feeds/for-you?page=$page&count=40"
            mode == "liked" -> "/v2/feeds/liked?page=$page&count=40&type=g"
            author != null -> "/v2/users/${enc(author)}/search?order=${if (order == "score") "latest" else order}&count=40&page=$page&type=g"
            text.isBlank() && order == "trending" -> "/v2/feeds/trending/popular?count=40&page=$page"
            text.isBlank() -> "/v2/gifs/search?order=$order&count=40&page=$page&type=g"
            // search_text API игнорирует (25.10: «car» → случайное); сайт шлёт query= или tags=
            byTag -> "/v2/gifs/search?tags=${enc(text)}&order=$order&count=40&page=$page&type=g"
            else -> "/v2/gifs/search?query=${enc(text)}&order=$order&count=40&page=$page&type=g"
        }
        val j = JSONObject(get(path))
        val arr = j.optJSONArray("gifs") ?: JSONArray()
        val out = ArrayList<Gif>()
        for (i in 0 until arr.length()) {
            val g = arr.getJSONObject(i)
            if (g.has("cta") && !g.isNull("cta")) continue          // реклама
            parse(g)?.let { out += it }
        }
        val tags = j.optJSONArray("tags")?.let { t -> List(t.length()) { t.optString(it) } }?.filter { it.isNotBlank() } ?: emptyList()
        return Page(out, j.optInt("pages", 1), tags)
    }

    private fun parse(g: JSONObject): Gif? {
        val u = g.optJSONObject("urls") ?: return null
        val hd = u.optString("hd").ifEmpty { u.optString("sd") }
        if (hd.isEmpty()) return null
        val tags = g.optJSONArray("tags")?.let { t -> List(t.length()) { t.getString(it) } } ?: emptyList()
        return Gif(g.getString("id"), u.optString("thumbnail").ifEmpty { u.optString("poster") }, hd,
            u.optString("sd").ifEmpty { hd }, g.optInt("width", 9), g.optInt("height", 16),
            g.optDouble("duration", 0.0).let { if (it.isNaN()) 0.0 else it }, g.optString("userName"), tags,
            g.optBoolean("hasAudio"), g.optLong("views"), g.optLong("likes"), g.optString("description").takeIf { it != "null" }.orEmpty())
    }

    private val known = java.util.Collections.synchronizedMap(object : LinkedHashMap<String, Gif?>(256, .75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, Gif?>) = size > 2000
    })

    /** Ролик по id (ссылка на RedGIFs из поста Reddit); null — удалён. Запомненные не запрашиваем снова. */
    fun gif(id: String): Gif? {
        val k = id.lowercase()
        if (known.containsKey(k)) return known[k]
        val g = try { parse(JSONObject(get("/v2/gifs/${enc(k)}")).getJSONObject("gif")) } catch (e: HttpError) { if (e.code == 404 || e.code == 410) null else throw e }
        known[k] = g
        return g
    }

    /** Подсказки тегов: [(тег, сколько роликов)]. */
    fun suggest(q: String): List<Pair<String, Int>> {
        val a = JSONArray(get("/v2/search/suggest?query=${enc(q)}"))
        return List(a.length()) { a.getJSONObject(it).let { o -> o.optString("text") to o.optInt("gifs") } }.filter { it.first.isNotBlank() }
    }

    /**
     * Запрос → теги, если он целиком из тегов: вся фраза — тег («car sex» → Car Sex) или каждое значимое слово — тег
     * («blowjob in the car» → Blowjob,Car). Иначе null — искать текстом (query=), как сайт («gearshift», «car shifter»).
     */
    fun toTags(q: String): String? {
        fun exact(x: String) = runCatching { suggest(x) }.getOrDefault(emptyList()).firstOrNull { it.first.equals(x, true) }?.first
        exact(q)?.let { return it }
        val words = words(q)
        if (words.size < 2) return null
        // слова проверяем параллельно: при высоком пинге последовательные запросы складывались в секунды
        val pool = java.util.concurrent.Executors.newFixedThreadPool(minOf(words.size, 4))
        try {
            val tags = words.map { w -> pool.submit<String?> { exact(w) ?: exact(w.removeSuffix("s")) } }.map { it.get() ?: return null }
            return tags.distinct().joinToString(",")
        } finally { pool.shutdownNow() }
    }

    /** Значимые слова запроса (без предлогов и артиклей). */
    fun words(q: String) = q.lowercase().split(Regex("[^a-z0-9'-]+")).filter { it.length > 1 && it !in STOP }.distinct()
    private val STOP = setOf("in", "the", "a", "an", "on", "with", "of", "at", "and", "for", "to", "my", "her", "his", "by", "from", "is", "into", "while")

    /** Приложение представляется честно, без маскировки под браузер. */
    val UA = "Loopa/${BuildConfig.VERSION_NAME} (Android; open-source viewer)"
}
