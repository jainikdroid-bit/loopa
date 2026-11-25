package app.loopa

/** Main-thread pending work. Foreground requests supersede speculative work. */
internal class GridWorkQueue<K>(private val capacity: Int) {
    init { require(capacity > 0) }
    private val pending = LinkedHashMap<K, Boolean>()
    val size get() = pending.size

    fun offer(key: K, visible: Boolean): List<K> {
        val previous = pending.remove(key)
        pending[key] = visible || previous == true
        val evicted = ArrayList<K>()
        while (pending.size > capacity) {
            val oldest = pending.entries.firstOrNull { !it.value } ?: pending.entries.first()
            evicted += oldest.key
            pending.remove(oldest.key)
        }
        return evicted
    }

    fun remove(key: K) { pending.remove(key) }
    fun poll(visible: Boolean): K? {
        val entry = if (visible) pending.entries.lastOrNull { it.value }
            else pending.entries.firstOrNull { !it.value }
        return entry?.key?.also { pending.remove(it) }
    }
}
