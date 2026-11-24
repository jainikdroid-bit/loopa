package app.loopa

import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder

/** Sepia Search indexes federated videos. The original instance serves the media. */
object PeerTube : Source {
    override val id = "peertube"
    override val title = "PeerTube"
    override val badge = "PT"
    override val home = "https://sepiasearch.org"
    override val about = "PeerTube · Sepia Search"
    override val hlsOnly = true
    private const val SIZE = 50
    private val uuid = Regex("[a-fA-F0-9]{8}(?:-[a-fA-F0-9]{4}){3}-[a-fA-F0-9]{12}")

    private fun json(url: String): JSONObject {
        val c = Net.open(url)
        try {
            if (c.responseCode != 200) throw Net.fail(c)
            return JSONObject(Net.text(c))
        } finally { c.disconnect() }
    }

    override fun page(q: String, fresh: Boolean, p: Int): Page {
        val sort = if (fresh) "-publishedAt" else if (q.isBlank()) "-views" else "-match"
        val term = if (q.isBlank()) "" else "&search=" + URLEncoder.encode(q.trim(), "UTF-8")
        return parse(json("$home/api/v1/search/videos?nsfw=true&isLive=false&count=$SIZE&start=${(p - 1) * SIZE}&sort=$sort$term"), p)
    }

    internal fun parse(response: JSONObject, page: Int): Page {
        val rows = response.getJSONArray("data")
        val clips = ArrayList<Gif>()
        for (i in 0 until rows.length()) {
            val v = rows.getJSONObject(i)
            // NSFW alone can designate violence. Only accept videos explicitly labelled sexual.
            if (v.optInt("nsfwFlags") and 2 == 0 || v.optBoolean("isLive")) continue
            val idOnSite = v.optString("uuid").takeIf(uuid::matches) ?: continue
            val origin = runCatching { URI(v.getString("url")) }.getOrNull() ?: continue
            if (origin.scheme != "https" || origin.host == null || origin.userInfo != null) continue
            val key = "https://${origin.rawAuthority}/api/v1/videos/$idOnSite"
            val tags = v.optJSONArray("tags")
            val channel = v.optJSONObject("channel")
            clips += Gif("pt:$idOnSite", v.optString("thumbnailUrl"), Sources.lazy(id,key,true), Sources.lazy(id,key,false),
                (720 * v.optDouble("aspectRatio", 16.0 / 9)).toInt().coerceAtLeast(1), 720, v.optDouble("duration",0.0),
                channel?.optString("displayName").orEmpty(), List(tags?.length() ?: 0) { tags!!.getString(it) },
                true, v.optLong("views"), v.optLong("likes"), v.optString("name"), hls = true, site = id)
        }
        return Page(clips, if (page.toLong() * SIZE < response.optLong("total")) page + 1 else page, emptyList())
    }

    override fun resolve(key: String, hd: Boolean): Resolved? {
        val playlists = json(key).optJSONArray("streamingPlaylists") ?: return null
        for (i in 0 until playlists.length()) {
            val url = playlists.getJSONObject(i).optString("playlistUrl")
            if (url.startsWith("https://")) return Resolved(url)
        }
        return null
    }
}
