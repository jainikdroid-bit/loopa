package app.loopa

import android.content.Context

/**
 * Лайки (upvote), поставленные на Reddit раньше, — в профиль вкусов ([Interests.imported]) и в «понравившиеся» у себя
 * ([Likes], метка ♥). Берутся видео из последних лайков (до [PAGES] страниц сайта); уже учтённое повторно не считаем.
 */
object RedditImport {
    private const val PAGES = 6
    private const val KEEP = 3000

    /** Сколько новых лайков учтено; ошибка — исключением (нет входа, страница не открылась). */
    fun run(ctx: Context): Int {
        val p = ctx.applicationContext.getSharedPreferences("loopa", Context.MODE_PRIVATE)
        val done = LinkedHashSet(p.getStringSet("rd_imported", emptySet())!!.toList())
        var after: String? = null; var added = 0
        repeat(PAGES) {
            val (list, next) = Reddit.upvoted(after)
            for (g in list) if (done.add(g.id)) { Interests.imported(g); Likes.set(g.id, true); added++ }
            after = next
            if (after == null) return@repeat
        }
        while (done.size > KEEP) done.remove(done.first())
        p.edit().putStringSet("rd_imported", done).putBoolean("rd_likes_done", true).apply()
        android.util.Log.i("Loopa", "лайки Reddit: учтено $added")
        return added
    }
}
