package app.loopa

/** Main-thread retry state. Refreshing the viewport must not bypass backoff or exhaustion. */
internal class PreviewRetryState(private val maximum: Int = 3, private val capacity: Int = 500) {
    private val counts = LinkedHashMap<String, Int>()
    fun failure(id: String): Int? {
        val n = ((counts.remove(id) ?: 0) + 1).coerceAtMost(maximum + 1)
        counts[id] = n
        while (counts.size > capacity) counts.remove(counts.keys.first())
        return n.takeIf { it <= maximum }
    }
    fun exhausted(id: String) = (counts[id] ?: 0) > maximum
    fun healthy(id: String) { counts.remove(id) }
    fun clear() { counts.clear() }
}
