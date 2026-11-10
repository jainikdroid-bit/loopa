package app.loopa

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Прошлая лента — на диск, чтобы при запуске сетка не была пустой: «Для вас» и «Популярное» сразу показывают то, что
 * было в прошлый раз (картинки и видео этих роликов уже в кэше), а свежее дописывается, как только загрузится.
 * Храним первые [KEEP] роликов ленты не дольше [TTL] (адреса файлов у сайтов со временем меняются).
 */
object FeedCache {
    private const val KEEP = 90
    private const val TTL = 3 * 24 * 3600_000L
    private var dir: File? = null

    fun init(ctx: Context) { if (dir == null) dir = File(ctx.applicationContext.filesDir, "feeds").apply { mkdirs() } }

    private fun file(key: String) = dir?.let { File(it, key.replace(Regex("[^a-z0-9_-]"), "_") + ".json") }

    /** Запомнить ленту (в фоне). */
    fun save(key: String, items: List<Gif>) {
        val f = file(key) ?: return
        val list = items.take(KEEP)
        Thread {
            runCatching {
                val o = JSONObject().put("at", System.currentTimeMillis()).put("items", JSONArray().apply { list.forEach { put(toJson(it)) } })
                File(f.path + ".tmp").apply { writeText(o.toString()) }.renameTo(f)
            }.onFailure { android.util.Log.w("Loopa", "лента $key не сохранилась", it) }
        }.start()
    }

    /** Прошлая лента; пусто — нет, устарела или не читается. */
    fun load(key: String): List<Gif> {
        val f = file(key)?.takeIf { it.exists() } ?: return emptyList()
        return runCatching {
            val o = JSONObject(f.readText())
            if (System.currentTimeMillis() - o.optLong("at") > TTL) return emptyList()
            val a = o.getJSONArray("items")
            List(a.length()) { fromJson(a.getJSONObject(it)) }
        }.onFailure { android.util.Log.w("Loopa", "лента $key не прочиталась", it) }.getOrDefault(emptyList())
    }

    private fun toJson(g: Gif) = JSONObject()
        .put("id", g.id).put("thumb", g.thumb).put("hd", g.hd).put("sd", g.sd).put("w", g.w).put("h", g.h)
        .put("duration", g.duration).put("user", g.user).put("tags", JSONArray(g.tags)).put("audio", g.hasAudio)
        .put("views", g.views).put("likes", g.likes).put("description", g.description).put("sub", g.sub)
        .put("hls", g.hls).put("dash", g.dash).put("created", g.created).put("site", g.site)

    private fun fromJson(o: JSONObject): Gif {
        val t = o.optJSONArray("tags")
        return Gif(o.getString("id"), o.optString("thumb"), o.getString("hd"), o.optString("sd"), o.optInt("w"), o.optInt("h"),
            o.optDouble("duration", 0.0), o.optString("user"), if (t == null) emptyList() else List(t.length()) { t.getString(it) },
            o.optBoolean("audio"), o.optLong("views"), o.optLong("likes"), o.optString("description"), o.optString("sub"),
            o.optBoolean("hls"), o.optString("dash"), o.optLong("created"), o.optString("site"))
    }
}
