package app.loopa

/**
 * Чистые части карты кэша ролика для ползунка перемотки и имён сохранённых файлов (без Android — проверяются юнит-тестами).
 */
object CacheMap {
    /** Отрезок в долях длины ролика: 0f…1f. */
    data class Part(val from: Float, val to: Float)

    /**
     * Куски дискового кэша (начало и длина в байтах) → отрезки в долях длины. Соседние и перекрывающиеся склеиваются,
     * пустые и вне файла отбрасываются. Байты в доли пропорционально (bytes / len): для mp4 это приблизительно
     * (битрейт неравномерный), для ползунка достаточно. [len] неизвестна (≤ 0) — пусто.
     */
    fun parts(spans: List<Pair<Long, Long>>, len: Long): List<Part> {
        if (len <= 0) return emptyList()
        val sorted = spans.filter { it.second > 0 && it.first < len }
            .map { it.first.coerceAtLeast(0) to (it.first + it.second).coerceAtMost(len) }
            .sortedBy { it.first }
        val merged = ArrayList<Pair<Long, Long>>()
        for (s in sorted) {
            val last = merged.lastOrNull()
            if (last != null && s.first <= last.second) merged[merged.size - 1] = last.first to maxOf(last.second, s.second)
            else merged += s
        }
        return merged.map { Part(it.first.toFloat() / len, it.second.toFloat() / len) }
    }

    /** Отрезки в долях → отрезки времени, мс (для подписи и проверок). */
    fun times(parts: List<Part>, durationMs: Long): List<LongRange> =
        parts.map { (it.from * durationMs).toLong()..(it.to * durationMs).toLong() }

    /** Предел длины части имени файла (автор или название), символов. */
    const val NAME_MAX = 60

    /** Часть имени файла: буквы любого языка, цифры, «.», «_», «-»; остальное — «_» (подряд — одно), без «_.-» по краям, ≤ [NAME_MAX]. */
    fun clean(s: String, max: Int = NAME_MAX): String {
        val x = s.replace(Regex("[^\\p{L}\\p{N}._-]+"), "_").replace(Regex("_{2,}"), "_")
        return x.take(max).trim('_', '.', '-')
    }

    /** Имя файла: `<сайт>_<автор или название>_<id>.mp4`. Нет ни автора, ни названия — `<сайт>_<id>.mp4`. */
    fun fileName(site: String, author: String, title: String, id: String): String {
        val mid = clean(author).ifEmpty { clean(title.lineSequence().firstOrNull().orEmpty()) }
        return listOf(clean(site).ifEmpty { "video" }, mid, clean(id, 80).ifEmpty { "x" }).filter { it.isNotEmpty() }.joinToString("_") + ".mp4"
    }
}
