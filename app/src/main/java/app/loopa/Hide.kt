package app.loopa

import android.content.Context
import android.content.SharedPreferences

/**
 * Скрывать темы: гей-контент и sissy / фембой / транс — лесби и прочее остаются. Проверяются теги, сабреддит, автор и
 * слова названия: по началу слова («gay», «gaybros», «twinks»), чтобы «trans» не ловил «transparent», а сабреддиты из
 * слитных слов («GayBrosGoneWild») — по вхождению. Плюс свои слова пользователя.
 */
object Hide {
    private var prefs: SharedPreferences? = null

    /** id → подпись и корни слов. */
    val PRESETS = listOf(
        Triple("gay", "Гей", listOf("gay", "twink", "malesolo", "broslikeus", "manlove", "hotguys", "ladybonersgw", "jockstrap")),
        Triple("sissy", "Sissy, фембой, транс", listOf("sissy", "sissies", "femboy", "femboi", "trap", "traps", "crossdress", "tgirl", "tgirls", "trans", "transgender",
            "shemale", "ladyboy", "ladyboys", "futanari", "futa", "tranny", "tbabes", "transporn")),
    )

    fun init(ctx: Context) { if (prefs == null) prefs = ctx.applicationContext.getSharedPreferences("loopa", Context.MODE_PRIVATE) }
    fun on(id: String) = prefs?.getBoolean("hide_$id", false) ?: false
    fun set(id: String, v: Boolean) { prefs?.edit()?.putBoolean("hide_$id", v)?.apply(); cache = null }
    var custom: String
        get() = prefs?.getString("hide_custom", "").orEmpty()
        set(v) { prefs?.edit()?.putString("hide_custom", v)?.apply(); cache = null }
    val active get() = roots().isNotEmpty()

    @Volatile private var cache: List<String>? = null
    private fun roots(): List<String> = cache ?: (PRESETS.filter { on(it.first) }.flatMap { it.third } +
        custom.lowercase().split(Regex("[,\\s]+")).filter { it.length >= 3 }).also { cache = it }

    /** Скрыть ролик? */
    fun hidden(g: Gif): Boolean {
        val r = roots()
        if (r.isEmpty()) return false
        // слитные имена (теги без пробелов, сабреддит, автор) — по вхождению длинных корней; слова — по началу слова
        val glued = (g.tags + g.sub + g.user).map { it.lowercase().replace(" ", "") }
        val words = (g.tags.joinToString(" ") + " " + g.description).lowercase().split(Regex("[^\\p{L}0-9]+")).filter { it.isNotEmpty() }
        return r.any { root ->
            val exact = root in EXACT
            words.any { if (exact) it == root else it.startsWith(root) } || !exact && (root.length >= 4 || root == "gay") && glued.any { it.contains(root) }
        }
    }

    /** Короткие корни, что встречаются внутри обычных слов (trans-parent, trap-ped), — только целым словом. */
    private val EXACT = setOf("trap", "traps", "trans", "futa")
}
