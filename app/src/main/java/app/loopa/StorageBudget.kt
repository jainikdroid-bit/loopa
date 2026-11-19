package app.loopa

/**
 * Общий предел места: дисковый кэш видео и сохранённые из него ролики ([CachedExport]) вместе укладываются в предел кэша
 * ([Video.cacheLimit]). Чистая часть без Android — [StorageBudgetTest]: по списку всего, что занимает место, выбирает,
 * что удалить, чтобы сумма влезла в предел.
 */
object StorageBudget {
    enum class Kind { CACHE, SAVED }

    /** Один занимающий место элемент: кусок кэша или сохранённый файл; [at] — когда его последний раз смотрели, мс. */
    data class Item(val id: String, val kind: Kind, val size: Long, val at: Long)

    /**
     * Кого удалить, чтобы сумма размеров стала не больше [limit]: самые давние по [Item.at] среди ВСЕХ (кэш и файлы
     * вперемешку); при равном времени кэш раньше файла (его проще докачать). [keep] — id, которые не трогаем (ролик на
     * экране): их размер считается, но в жертвы они не попадают. Порядок результата — порядок удаления. Удалять больше
     * не из чего, а сумма всё ещё больше предела — вернётся всё, что можно, остальное остаётся.
     */
    fun victims(items: List<Item>, limit: Long, keep: Set<String> = emptySet()): List<Item> {
        var total = items.sumOf { it.size }
        if (total <= limit) return emptyList()
        val out = ArrayList<Item>()
        for (x in items.filter { it.id !in keep }.sortedWith(compareBy<Item> { it.at }.thenBy { it.kind }.thenBy { it.id })) {
            if (total <= limit) break
            if (x.size <= 0) continue
            out += x; total -= x.size
        }
        return out
    }
}
