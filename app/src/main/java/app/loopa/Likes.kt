package app.loopa

import android.content.Context
import android.content.SharedPreferences

/**
 * Лайкнутые ролики — помним у себя (на RedGIFs лайк уходит и на сервер, у Reddit и сторонних сайтов — только здесь).
 * Нужно и плееру (сердечко), и сетке (метка ♥ на плитке).
 */
object Likes {
    private var prefs: SharedPreferences? = null
    @Volatile private var liked: Set<String> = emptySet()
    @Volatile private var unliked: Set<String> = emptySet()

    fun init(ctx: Context) {
        if (prefs != null) return
        prefs = ctx.applicationContext.getSharedPreferences("loopa", Context.MODE_PRIVATE).also {
            liked = HashSet(it.getStringSet("liked", emptySet())!!); unliked = HashSet(it.getStringSet("unliked", emptySet())!!)
        }
    }

    /** Лайкнут ли: свой список; [inLikedFeed] — ролик из «Моих лайков» (там лайкнуты все, кроме снятых). */
    fun has(id: String, inLikedFeed: Boolean = false) = id in liked || inLikedFeed && id !in unliked

    fun set(id: String, on: Boolean) {
        val l = HashSet(liked); val u = HashSet(unliked)
        if (on) { l += id; u -= id } else { l -= id; u += id }
        liked = l; unliked = u
        prefs?.edit()?.putStringSet("liked", l)?.putStringSet("unliked", u)?.apply()
    }
}
