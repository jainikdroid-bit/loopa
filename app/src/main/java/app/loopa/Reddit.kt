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
    class RSort(val id: String, private val labelRu: String, private val shortRu: String, val path: String, val t: String? = null) {
        val label get() = tr(labelRu)
        val short get() = tr(shortRu)
    }
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

    fun init(ctx: Context) {
        if (prefs == null) prefs = ctx.applicationContext.getSharedPreferences("loopa", Context.MODE_PRIVATE)
        app = ctx.applicationContext
        if (browserUa.isEmpty()) browserUa = runCatching { android.webkit.WebSettings.getDefaultUserAgent(ctx).replace("; wv", "").replace(Regex(" Version/\\d+(\\.\\d+)*"), "") }.getOrDefault("")
    }
    /** Подпись, которую Reddit требует от сторонних клиентов API: платформа:приложение:версия (by /u/имя). */
    private val apiUa get() = "android:app.loopa:v${BuildConfig.VERSION_NAME}" + name.takeIf { it.isNotEmpty() }?.let { " (by /u/$it)" }.orEmpty()
    private var app: Context? = null
    /** Подпись браузера — как у окна входа ([Disguise]); для запасного пути через сайт. */
    @Volatile private var browserUa = ""
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
            Net.retry { fetch("$OAUTH$path?$query") { it.setRequestProperty("Authorization", "Bearer $t"); it.setRequestProperty("User-Agent", apiUa) } }
        } catch (e: HttpError) {
            android.util.Log.w("Loopa", "Reddit $path: HTTP ${e.code}")
            if (e.code == 401) { prefs?.edit()?.putLong("rd_exp", 1)?.apply(); throw RuntimeException(LOGIN_EXPIRED) }
            if (e.code != 403) throw e
            throw e
        }
    }

    private fun fetch(url: String, auth: (java.net.HttpURLConnection) -> Unit): String {
        val c = Net.open(url); auth(c)
        try {
            if (c.responseCode !in 200..299) {
                val body = runCatching { c.errorStream?.bufferedReader()?.readText()?.take(300) }.getOrNull()
                android.util.Log.w("Loopa", "Reddit ${c.responseCode} ${url.substringBefore('?')}: ${c.headerFields.filterKeys { it != null && (it.startsWith("x-", true) || it.equals("server", true)) }} ${body?.replace("\n", " ")}")
                throw Net.fail(c)
            }
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
        // подписки и смесь «a+b»: главная и страница смеси у сайта ленту не отдают — листаем сабреддиты по кругу
        if (sub.isEmpty() || sub.contains('+')) return roundRobin(sub, sortId, after)
        if (after?.startsWith(WEB) == true) return web(null, after.removePrefix(WEB))
        // ленты через API Reddit режет (403 или держит соединение без ответа минутами) — сразу со страницы сайта
        val url = WWW + (if (sub.startsWith("u/")) "/user/${sub.substring(2)}/submitted/" else "/r/$sub/${s.path}/") + (s.t?.let { "?t=$it" } ?: "")
        return web(url, null)
    }

    /** Круг по сабреддитам: у каждого свой курсор; курсор круга — «rr:» (номер шага), конец — когда кончились все. */
    private class Round(val subs: List<String>) { var i = 0; val cur = HashMap<String, String?>() }
    private val rounds = HashMap<String, Round>()

    private fun roundRobin(sub: String, sortId: String, after: String?): Pair<List<Gif>, String?> {
        val key = "$sub|$sortId"
        val r = synchronized(rounds) {
            if (after == null || rounds[key] == null) {
                val list = if (sub.isEmpty()) runCatching { subscribed() }.getOrElse { throw RuntimeException(tr("Reddit: не получить подписки (%s)", it.message)) }
                    else sub.split("+").filter { it.isNotBlank() }
                if (list.isEmpty()) throw RuntimeException(tr("Reddit: подписок нет — найдите сабреддит в поиске выше"))
                rounds[key] = Round(list.filter { !isBad(it) }.shuffled())
            }
            rounds[key]!!
        }
        // до 3 сабреддитов за шаг, пока не наберётся десяток роликов
        val out = ArrayList<Gif>()
        var tries = 0
        while (out.size < 10 && tries++ < 3) {
            val s = synchronized(rounds) { r.subs.filter { !r.cur.containsKey(it) || r.cur[it] != null }.let { open -> if (open.isEmpty()) null else open[r.i++ % open.size] } } ?: break
            val (g, next) = runCatching { page(s, sortId, r.cur[s]) }.getOrElse { android.util.Log.w("Loopa", "Reddit r/$s: ${it.message}"); emptyList<Gif>() to null }
            synchronized(rounds) { r.cur[s] = next }
            out += g
        }
        val more = r.subs.any { !r.cur.containsKey(it) || r.cur[it] != null }
        return out to (if (more) "rr:${r.i}" else null)
    }

    /** Курсор ленты со страницы сайта: адрес следующей порции (её подгружает сам сайт при прокрутке). */
    private const val WEB = "web:"

    /** Посты со страницы (shreddit-post) — для невидимого браузера; G(корень) → список, M(корень) → адрес следующей порции. */
    private const val GRAB = """var G=function(r){return Array.prototype.map.call(r.querySelectorAll('shreddit-post'),function(p){
var pl=p.querySelector('shreddit-player-2,shreddit-player');var im=p.querySelector('img[src*="preview.redd.it"],img[src*="external-preview.redd.it"],img[src*="i.redd.it"]');
return{id:(p.getAttribute('id')||'').replace(/^t3_/,''),title:p.getAttribute('post-title')||'',author:p.getAttribute('author')||'',
sub:(p.getAttribute('subreddit-prefixed-name')||'').replace(/^r\//,''),score:+(p.getAttribute('score')||0),comments:+(p.getAttribute('comment-count')||0),
type:p.getAttribute('post-type')||'',ts:Math.floor((Date.parse(p.getAttribute('created-timestamp')||'')||0)/1000),href:p.getAttribute('content-href')||'',src:pl?(pl.getAttribute('src')||''):'',poster:pl?(pl.getAttribute('poster')||''):'',
thumb:im?im.getAttribute('src'):''};});};
var M=function(r){var m=r.querySelector('faceplate-partial[src*="more-posts"]');return m?m.getAttribute('src'):null;};"""

    /** Порция со страницы: url — открыть страницу, иначе more — подгрузить следующую порцию из уже открытой. */
    private fun web(url: String?, more: String?): Pair<List<Gif>, String?> {
        val ctx = app ?: throw RuntimeException("нет контекста")
        val raw = if (url != null) WebJson.page(ctx, url, "(function(){$GRAB return JSON.stringify({posts:G(document),more:M(document)});})()")
            else WebJson.run(ctx, "(function(){$GRAB fetch(${JSONObject.quote(more)},{credentials:'include'}).then(function(r){return r.text()}).then(function(t){" +
                "var d=new DOMParser().parseFromString(t,'text/html');window.__lp=JSON.stringify({posts:G(d),more:M(d)});}).catch(function(e){window.__lp=JSON.stringify({error:String(e)})});})()")
        val j = runCatching { JSONObject(raw ?: "") }.getOrNull() ?: throw RuntimeException(tr("Reddit: страница не открылась"))
        j.optString("error").takeIf { it.isNotEmpty() }?.let { throw RuntimeException("Reddit: $it") }
        val a = j.optJSONArray("posts") ?: JSONArray()
        val posts = (0 until a.length()).map { a.getJSONObject(it) }.toMutableList()
        // сайт сначала рисует 3–5 постов — сразу подгрузить ещё (до ~25), как при прокрутке
        var moreSrc = j.optString("more").takeIf { it.isNotBlank() && it != "null" }
        var extra = 0
        while (url != null && posts.size < 20 && moreSrc != null && extra++ < 2) {
            val r2 = runCatching { JSONObject(WebJson.run(ctx, "(function(){$GRAB fetch(${JSONObject.quote(moreSrc)},{credentials:'include'}).then(function(r){return r.text()}).then(function(t){" +
                "var d=new DOMParser().parseFromString(t,'text/html');window.__lp=JSON.stringify({posts:G(d),more:M(d)});}).catch(function(e){window.__lp=JSON.stringify({error:String(e)})});})()") ?: "") }.getOrNull() ?: break
            val a2 = r2.optJSONArray("posts") ?: break
            for (i in 0 until a2.length()) posts += a2.getJSONObject(i)
            moreSrc = r2.optString("more").takeIf { it.isNotBlank() && it != "null" }
        }
        if (url != null) j.put("more", moreSrc ?: JSONObject.NULL)
        android.util.Log.i("Loopa", "Reddit со страницы: ${posts.size} постов, пример ${posts.firstOrNull()}")
        val jobs = posts.map { o -> pool.submit<Gif?> { runCatching { fromPage(o) }.getOrNull() } }
        val next = j.optString("more").takeIf { it.isNotBlank() && it != "null" }?.let { if (it.startsWith("http")) it else WWW + it }
        return jobs.mapNotNull { it.get() } to next?.let { WEB + it }
    }

    /** Пост со страницы → ролик: поток v.redd.it, ссылка на RedGIFs, Imgur, mp4 гифки; остальное — мимо. */
    private fun fromPage(o: JSONObject): Gif? {
        val src = o.optString("src"); val href = o.optString("href")
        val base = Gif("rd_" + o.optString("id"), o.optString("poster").ifEmpty { o.optString("thumb") }, "", "", 9, 16, 0.0, o.optString("author"),
            listOf("r/" + o.optString("sub")), true, o.optLong("comments"), o.optLong("score"), o.optString("title"), sub = o.optString("sub"), created = o.optLong("ts"))
        if (src.contains(".m3u8")) return base.copy(hd = src, sd = src, hls = true, dash = src.substringBefore('?').replace("HLSPlaylist.m3u8", "DASHPlaylist.mpd"))
        RG.find(href)?.let { m ->
            val g = Api.gif(m.groupValues[1]) ?: return null
            return base.copy(thumb = g.thumb, hd = g.hd, sd = g.sd, w = g.w, h = g.h, duration = g.duration, hasAudio = g.hasAudio, tags = base.tags + g.tags)
        }
        IMGUR.find(href)?.let { m -> return base.copy(hd = "https://i.imgur.com/${m.groupValues[1]}.mp4", sd = "https://i.imgur.com/${m.groupValues[1]}.mp4", hasAudio = false) }
        if (src.contains(".mp4")) return base.copy(hd = src, sd = src, hasAudio = false)
        return null
    }

    /** Поиск постов по всему Reddit (18+ включительно) — «Для вас» собирает не только из своих сабреддитов. */
    @Suppress("UNUSED_PARAMETER")
    fun searchPosts(term: String, after: String?): Pair<List<Gif>, String?> = emptyList<Gif>() to null   // поиск постов API режет (403)

    private fun listing(path: String, q: String, sub: String): Pair<List<Gif>, String?> {
        val data = try { JSONObject(get(path, q)).getJSONObject("data") } catch (e: HttpError) {
            // 403 на смеси — среди сабреддитов закрытый, на карантине или забаненный: убрать плохие и повторить
            if (e.code != 403 || !sub.contains('+')) throw e
            val good = prune(sub.split("+"))
            if (good.isEmpty() || good.size == sub.split("+").size) throw e
            return listing(path.replace(sub, good.joinToString("+")), q, good.joinToString("+"))
        }
        val kids = data.optJSONArray("children") ?: JSONArray()
        val posts = (0 until kids.length()).map { kids.getJSONObject(it).getJSONObject("data") }
        // ссылки на RedGIFs — параллельно (у RedGIFs нет запроса пачкой)
        val jobs = posts.map { d -> pool.submit<Gif?> { runCatching { video(d) }.getOrNull() } }
        return jobs.mapNotNull { it.get() } to data.optString("after").takeIf { it.isNotBlank() && it != "null" }
    }

    /** Сабреддиты, что отвечают 403 (закрытые, карантин, бан), — запоминаем и в смеси больше не берём. */
    private val bad = java.util.Collections.synchronizedSet(HashSet<String>())
    fun isBad(s: String) = s.lowercase() in bad || prefs?.getStringSet("rd_bad", emptySet())?.contains(s.lowercase()) == true

    /** Проверить сабреддиты по одному (параллельно), плохие — в [bad]; вернуть хорошие. */
    private fun prune(subs: List<String>): List<String> {
        val ok = subs.map { s -> s to pool.submit<Boolean> { runCatching { get("/r/$s/about", "raw_json=1"); true }.getOrElse { it !is HttpError || it.code != 403 && it.code != 404 } } }
            .filter { (s, f) -> f.get().also { good -> if (!good) bad += s.lowercase() } }.map { it.first }
        prefs?.edit()?.putStringSet("rd_bad", (prefs?.getStringSet("rd_bad", emptySet()).orEmpty() + bad).toSet())?.apply()
        android.util.Log.i("Loopa", "Reddit: убраны ${subs - ok.toSet()}")
        return ok
    }

    /** Подсказки сабреддитов: [(имя, подписчиков)], 18+ включительно. */
    fun suggest(q: String): List<Pair<String, Int>> {
        val j = JSONObject(get("/api/subreddit_autocomplete_v2", "query=${enc(q)}&include_over_18=true&include_profiles=false&limit=15&raw_json=1"))
        val a = j.getJSONObject("data").getJSONArray("children")
        return List(a.length()) { a.getJSONObject(it).getJSONObject("data").let { d -> d.optString("display_name") to d.optInt("subscribers") } }.filter { it.first.isNotBlank() }
    }

    /** Сабреддиты по слову (для «Для вас»): 18+ вперёд, по числу подписчиков, мелкие (< 2000) отбрасываем. */
    fun searchSubs(q: String, n: Int = 2): List<String> {
        val j = JSONObject(get("/subreddits/search", "q=${enc(q)}&include_over_18=on&limit=10&raw_json=1"))
        val a = j.getJSONObject("data").getJSONArray("children")
        return List(a.length()) { a.getJSONObject(it).getJSONObject("data") }
            .filter { it.optInt("subscribers") >= 2000 && it.optString("subreddit_type") == "public" && !it.optBoolean("quarantine") && !isBad(it.optString("display_name")) }
            .filter { it.optBoolean("over18") }
            .sortedByDescending { it.optInt("subscribers") }
            .take(n).map { it.optString("display_name") }.filter { it.isNotBlank() }
    }

    /** Подписки пользователя (кэш на час). */
    @Volatile private var mine: Pair<Long, List<String>>? = null
    fun subscribed(): List<String> {
        mine?.takeIf { System.currentTimeMillis() - it.first < 3600_000L }?.let { return it.second }
        val j = JSONObject(get("/subreddits/mine/subscriber", "limit=100&raw_json=1"))
        val a = j.getJSONObject("data").getJSONArray("children")
        val all = List(a.length()) { a.getJSONObject(it).getJSONObject("data") }.filter { it.optString("display_name").let { n -> n.isNotBlank() && !n.startsWith("u_") } }
        val l = all.map { it.optString("display_name") }
        nsfwMine = all.filter { it.optBoolean("over18") }.map { it.optString("display_name") }
        mine = System.currentTimeMillis() to l
        return l
    }

    /** Сабреддит 18+? (about через API работает; ответ запоминаем). Не узнали — считаем, что да. */
    private val nsfw = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    fun isNsfw(s: String): Boolean = nsfw.getOrPut(s.lowercase()) {
        runCatching { JSONObject(get("/r/$s/about", "raw_json=1")).getJSONObject("data").optBoolean("over18", true) }.getOrDefault(true)
    }

    /** Подписки 18+ (для «Для вас»: обычные подписки видео 18+ не дают). */
    @Volatile var nsfwMine: List<String> = emptyList(); private set
    fun subscribedNsfw(): List<String> { subscribed(); return nsfwMine }

    private val RG = Regex("redgifs\\.com/(?:watch|ifr|i)/([A-Za-z]+)")
    private val IMGUR = Regex("i\\.imgur\\.com/(\\w+)\\.(?:gifv|mp4)")

    /** Видео из поста или null (текст, картинка, галерея, внешний сайт). */
    private fun video(d: JSONObject): Gif? {
        if (d.optBoolean("is_self")) return null
        val src = if (rv(d) == null) d.optJSONArray("crosspost_parent_list")?.optJSONObject(0)?.takeIf { rv(it) != null } ?: d else d
        val base = Gif("rd_" + d.optString("id"), thumb(src), "", "", 9, 16, 0.0, d.optString("author"), listOf("r/" + d.optString("subreddit")),
            false, d.optLong("num_comments"), d.optLong("score"), d.optString("title"), sub = d.optString("subreddit"), created = d.optLong("created_utc"))
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
