package app.loopa

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray

/**
 * Счётчики пропусков «Для вас»: id → сколько раз пролистали и когда в последний раз.
 * Не больше [capacity] записей: при переполнении уходят давно не менявшиеся (порядок — от старых к новым).
 */
internal class SkipLog(private val capacity: Int = 800) {
    private class Entry(val count: Int, val at: Long)
    private val map = LinkedHashMap<String, Entry>()

    val size get() = map.size
    fun count(id: String) = map[id]?.count ?: 0

    /** Прибавить пропуск (не выше [max]); запись становится самой свежей. Возвращает новое значение. */
    fun bump(id: String, now: Long, max: Int = 4): Int {
        val next = ((map.remove(id)?.count ?: 0) + 1).coerceAtMost(max)
        map[id] = Entry(next, now)
        while (map.size > capacity) map.remove(map.keys.first())
        return next
    }

    fun remove(id: String) = map.remove(id) != null

    /** Добавить уже известное значение (миграция, загрузка). */
    fun put(id: String, count: Int, at: Long) {
        map.remove(id)
        map[id] = Entry(count, at)
        while (map.size > capacity) map.remove(map.keys.first())
    }

    fun toJson(): String {
        val rows = JSONArray()
        map.forEach { (id, e) -> rows.put(JSONArray().put(id).put(e.count).put(e.at)) }
        return rows.toString()
    }

    fun restore(raw: String) {
        map.clear()
        val rows = JSONArray(raw)
        // порядок в файле — от старых к новым; ломаные строки пропускаем
        for (i in 0 until rows.length()) runCatching {
            val r = rows.getJSONArray(i)
            put(r.getString(0), r.getInt(1), r.getLong(2))
        }
    }
}

/**
 * Отдельный файл настроек для вкусов: пропуски «Для вас» и веса поиска растут сами, основному "loopa" их не отдаём.
 * При первом открытии старые ключи переезжают из "loopa" (пропуски — без меток времени, веса поиска — как есть).
 */
internal object TasteStore {
    private const val FILE = "loopa_taste"
    private const val MIGRATED = "migrated_v1"
    const val SKIPS = "skips"
    const val SEARCH = "search_preferences_v1"
    private const val OLD_SKIP_PREFIX = "fy_skips:"

    @Synchronized fun open(ctx: Context): SharedPreferences {
        val app = ctx.applicationContext
        val taste = app.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        if (!taste.getBoolean(MIGRATED, false)) migrate(app.getSharedPreferences("loopa", Context.MODE_PRIVATE), taste)
        return taste
    }

    private fun migrate(old: SharedPreferences, taste: SharedPreferences) {
        val log = SkipLog()
        val oldKeys = old.all.keys.filter { it.startsWith(OLD_SKIP_PREFIX) }
        oldKeys.forEach { k -> log.put(k.removePrefix(OLD_SKIP_PREFIX), old.getInt(k, 0).coerceIn(1, 4), 0L) }
        val search = old.getString(SEARCH, null)
        // Keep the original preference data until the new file is written successfully.
        val saved = taste.edit().apply {
            if (oldKeys.isNotEmpty()) putString(SKIPS, log.toJson())
            if (search != null && !taste.contains(SEARCH)) putString(SEARCH, search)
            putBoolean(MIGRATED, true)
        }.commit()
        if (saved) old.edit().apply { oldKeys.forEach { remove(it) }; remove(SEARCH) }.apply()
    }
}
