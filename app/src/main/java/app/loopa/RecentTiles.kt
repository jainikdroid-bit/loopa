package app.loopa

/** Oldest first. Timestamps are unnecessary: recency is relative to other visible tiles. */
internal class RecentTiles(private val capacity: Int = 240) {
    private val ids = LinkedHashSet<String>()
    private var visible = emptySet<String>()

    fun restore(saved: List<String>) {
        ids.clear()
        ids.addAll(saved.takeLast(capacity))
        visible = emptySet()
    }

    fun update(now: List<String>): Boolean {
        val entered = now.filterNot { it in visible }
        visible = now.toSet()
        if (entered.isEmpty()) return false
        // Treat the first tile as most recent when the whole viewport arrives together.
        for (id in entered.asReversed()) {
            ids.remove(id)
            ids.add(id)
        }
        while (ids.size > capacity) ids.remove(ids.first())
        return true
    }

    fun saved() = ids.toList()
    fun order() = ids.withIndex().associate { it.value to it.index + 1 }
}
