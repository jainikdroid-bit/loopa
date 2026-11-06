package app.loopa

import android.content.Context
import android.content.SharedPreferences
import android.webkit.CookieManager
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.Executors

/**
 * Reddit через сессию сайта: пользователь входит на reddit.com в WebView, сайт кладёт токен в куку token_v2 (JWT) —
 * с ним ходим в oauth.reddit.com, как сам сайт. Не пустил (403) — те же ленты в .json сайта с куками сессии. Контент 18+
 * приходит, если он включён в настройках аккаунта. Из постов берём только видео: v.redd.it (поток HLS со звуком),
 * ссылки на RedGIFs (ролик через [Api.gif]), Imgur gifv/mp4 и mp4-превью гифок самого Reddit.
 */
object Reddit {
    private const val OAUTH = "https://oauth.reddit.com"
    private const val WWW = "https://www.reddit.com"
    const val LOGIN_NEEDED = "нужен вход в Reddit — нажмите"
    const val LOGIN_EXPIRED = "вход в Reddit истёк — обновляю…"
    private const val RENEW_AHEAD = 2 * 3600
    private var prefs: SharedPreferences? = null
    private val pool = Executors.newFixedThreadPool(6)

    /** Сортировка Reddit: id → подпись, путь, период для «топа». */
    class RSort(val id: String, val label: String, val short: String, val path: String, val t: String? = null)
    val SORTS = listOf(
        RSort("hot", "Горячее", "Горячее", "hot"), RSort("new", "Новое", "Новое", "new"), RSort("rising", "Набирает", "Набирает", "rising"),
        RSort("top-hour", "Топ за час", "Топ: час", "top", "hour"), RSort("top-day", "Топ за день", "Топ: день", "top", "day"),
        RSort("top-week", "Топ за неделю", "Топ: неделя", "top", "week"), RSort("top-month", "Топ за месяц", "Топ: месяц", "top", "month"),
        RSort("top-year", "Топ за год", "Топ: год", "top", "year"), RSort("top-all", "Топ за всё время", "Топ: всё", "top", "all"),
    )
    fun sort(id: String) = SORTS.firstOrNull { it.id == id } ?: SORTS[0]

    val SITE = object : Site("Reddit", "https://www.reddit.com/login/", "https://www.reddit.com/") {
        override val hosts = setOf("oauth.reddit.com", "gql.reddit.com", "gql-fed.reddit.com")
        override val cookie = "https://www.reddit.com" to "token_v2"
        override fun verify(t: String) = me(t) != null
        override fun save(ctx: Context, t: String, exp: Long) = setUser(ctx, t, exp, me(t).orEmpty())
    }

    fun init(ctx: Context) { if (prefs == null) prefs = ctx.applicationContext.getSharedPreferences("loopa", Context.MODE_PRIVATE) }
    private fun setUser(ctx: Context, t: String, exp: Long, name: String) {
        init(ctx); prefs!!.edit().putString("rd_token", t).putLong("rd_exp", exp).putString("rd_name", name).apply()
    }
    fun logout() {
        prefs?.edit()?.remove("rd_token")?.remove("rd_exp")?.remove("rd_name")?.apply()
        TokenCatcher.clearCookies(WWW, ".reddit.com")
    }
    val name get() = prefs?.getString("rd_name", null).orEmpty()
    val hadUser get() = prefs?.getString("rd_token", null) != null
    private fun token(): String? {
        val p = prefs ?: return null
        val t = p.getString("rd_token", null) ?: return null
        val exp = p.getLong("rd_exp", 0)
        return if (exp == 0L || exp > System.currentTimeMillis() / 1000 + 60) t else null
    }
    val loggedIn get() = token() != null
    val renewDue get() = hadUser && prefs!!.getLong("rd_exp", 0).let { it != 0L && it < System.currentTimeMillis() / 1000 + RENEW_AHEAD }

    /** Имя пользователя по токену; null — токен гостя или чужой. */
    private fun me(t: String): String? = runCatching {
        val c = Net.open("$OAUTH/api/v1/me?raw_json=1")
        c.setRequestProperty("Authorization", "Bearer $t")
        try { if (c.responseCode == 200) JSONObject(c.inputStream.bufferedReader().readText()).optString("name").takeIf { it.isNotBlank() } else null } finally { c.disconnect() }
    }.getOrNull()

    /** GET от имени пользователя: oauth с токеном, при 403 — .json сайта с куками сессии. 401 — вход истёк. */
    private fun get(path: String, query: String): String {
        val t = token() ?: throw RuntimeException(if (hadUser) LOGIN_EXPIRED else LOGIN_NEEDED)
        return try {
            Net.retry { fetch("$OAUTH$path?$query") { it.setRequestProperty("Authorization", "Bearer $t") } }
        } catch (e: HttpError) {
            if (e.code == 401) { prefs?.edit()?.putLong("rd_exp", 1)?.apply(); throw RuntimeException(LOGIN_EXPIRED) }
            if (e.code != 403) throw e
            val ck = CookieManager.getInstance().getCookie(WWW) ?: throw e
            Net.retry { fetch("$WWW$path.json?$query") { it.setRequestProperty("Cookie", ck) } }
        }
    }

    private fun fetch(url: String, auth: (java.net.HttpURLConnection) -> Unit): String {
        val c = Net.open(url); auth(c)
        try {
            if (c.responseCode !in 200..299) throw Net.fail(c)
            return c.inputStream.bufferedReader().readText()
        } finally { c.disconnect() }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    /**
     * Лента: sub пустой — главная (подписки), «u/имя» — посты пользователя, иначе сабреддиты через «+». Возвращает видео
     * страницы и курсор следующей (null — конец).
     */
    fun page(sub: String, sortId: String, after: String?): Pair<List<Gif>, String?> {
        val s = sort(sortId)
        val path = when {
            sub.isEmpty() -> "/${s.path}"
            sub.startsWith("u/") -> "/user/${enc(sub.substring(2))}/submitted"
            else -> "/r/$sub/${s.path}"
        }
        val q = buildString {
            append("limit=50&raw_json=1")
            if (sub.startsWith("u/")) append("&sort=${s.path}")
            s.t?.let { append("&t=$it") }
            after?.let { append("&after=${enc(it)}") }
        }
        val data = JSONObject(get(path, q)).getJSONObject("data")
        val kids = data.optJSONArray("children") ?: JSONArray()
        val posts = (0 until kids.length()).map { kids.getJSONObject(it).getJSONObject("data") }
        // ссылки на RedGIFs — параллельно (у RedGIFs нет запроса пачкой)
        val jobs = posts.map { d -> pool.submit<Gif?> { runCatching { video(d) }.getOrNull() } }
        return jobs.mapNotNull { it.get() } to data.optString("after").takeIf { it.isNotBlank() && it != "null" }
    }

    /** Подсказки сабреддитов: [(имя, подписчиков)], 18+ включительно. */
    fun suggest(q: String): List<Pair<String, Int>> {
        val j = JSONObject(get("/api/subreddit_autocomplete_v2", "query=${enc(q)}&include_over_18=true&include_profiles=false&limit=15&raw_json=1"))
        val a = j.getJSONObject("data").getJSONArray("children")
        return List(a.length()) { a.getJSONObject(it).getJSONObject("data").let { d -> d.optString("display_name") to d.optInt("subscribers") } }.filter { it.first.isNotBlank() }
    }

    private val RG = Regex("redgifs\\.com/(?:watch|ifr|i)/([A-Za-z]+)")
    private val IMGUR = Regex("i\\.imgur\\.com/(\\w+)\\.(?:gifv|mp4)")

    /** Видео из поста или null (текст, картинка, галерея, внешний сайт). */
    private fun video(d: JSONObject): Gif? {
        if (d.optBoolean("is_self")) return null
        val src = if (rv(d) == null) d.optJSONArray("crosspost_parent_list")?.optJSONObject(0)?.takeIf { rv(it) != null } ?: d else d
        val base = Gif("rd_" + d.optString("id"), thumb(src), "", "", 9, 16, 0.0, d.optString("author"), listOf("r/" + d.optString("subreddit")),
            false, d.optLong("num_comments"), d.optLong("score"), d.optString("title"), sub = d.optString("subreddit"))
        rv(src)?.let { v ->
            val hls = v.optString("hls_url").ifEmpty { return@let null }
            return base.copy(hd = hls, sd = hls, hls = true, dash = v.optString("dash_url"), w = v.optInt("width", 9), h = v.optInt("height", 16),
                duration = v.optDouble("duration", 0.0), hasAudio = !v.optBoolean("is_gif") && v.optBoolean("has_audio", true))
        }
        val url = src.optString("url_overridden_by_dest").ifEmpty { src.optString("url") }
        RG.find(url)?.let { m ->
            val g = Api.gif(m.groupValues[1]) ?: return preview(src, base)
            return base.copy(thumb = g.thumb, hd = g.hd, sd = g.sd, w = g.w, h = g.h, duration = g.duration, hasAudio = g.hasAudio, tags = base.tags + g.tags)
        }
        IMGUR.find(url)?.let { m -> return base.copy(hd = "https://i.imgur.com/${m.groupValues[1]}.mp4", sd = "https://i.imgur.com/${m.groupValues[1]}.mp4") }
        return preview(src, base)
    }

    private fun rv(d: JSONObject) = (d.optJSONObject("secure_media") ?: d.optJSONObject("media"))?.optJSONObject("reddit_video")

    /** Гифка Reddit (или ролик без своего плеера): mp4-превью, без звука. */
    private fun preview(d: JSONObject, base: Gif): Gif? {
        val v = d.optJSONObject("preview")?.optJSONObject("reddit_video_preview") ?: return null
        val u = v.optString("fallback_url").ifEmpty { return null }
        return base.copy(hd = u, sd = u, w = v.optInt("width", 9), h = v.optInt("height", 16), duration = v.optDouble("duration", 0.0))
    }

    /** Превью ~320 px из preview.images, иначе миниатюра поста. */
    private fun thumb(d: JSONObject): String {
        val img = d.optJSONObject("preview")?.optJSONArray("images")?.optJSONObject(0)
        val res = img?.optJSONArray("resolutions")
        if (res != null) for (i in 0 until res.length()) res.getJSONObject(i).takeIf { it.optInt("width") >= 320 }?.let { return it.optString("url") }
        img?.optJSONObject("source")?.optString("url")?.takeIf { it.isNotEmpty() }?.let { return it }
        return d.optString("thumbnail").takeIf { it.startsWith("http") }.orEmpty()
    }
}
