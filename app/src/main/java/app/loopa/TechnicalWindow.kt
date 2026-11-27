package app.loopa

/** Aggregate counters only: no identifiers, timestamps per event or arbitrary labels. */
internal object TechnicalWindow {
    val kinds = setOf("first_frame", "buffering", "playback_error", "player_background",
        "preview_start", "preview_error", "preview_release", "prefetch_cancel")
    const val LIMIT = 10_000
    const val AGE = 30L * 24 * 60 * 60 * 1000

    fun expired(start: Long, now: Long): Boolean = start <= 0L || now < start || now - start >= AGE

    fun increment(counts: Map<String, Int>, kind: String): Map<String, Int> {
        if (kind !in kinds || counts.values.sum() >= LIMIT) return counts
        return counts + (kind to ((counts[kind] ?: 0) + 1))
    }
}
