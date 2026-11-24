package app.loopa

/** Accumulates only intervals reported as playing, using monotonic milliseconds. */
internal class WatchClock {
    private var started: Long? = null
    private var elapsed = 0L

    fun playing(active: Boolean, now: Long) {
        if (active) {
            if (started == null) started = now
        } else {
            started?.let { elapsed += now - it }
            started = null
        }
    }

    fun take(now: Long): Long {
        playing(false, now)
        val result = elapsed
        elapsed = 0L
        return result
    }
}
