package app.loopa

/** Считает один эпизод буферизации за раз, только после появления кадра. */
internal class PlaybackStall {
    private var hasFrame = false
    private var seeking = false
    private var since: Long? = null
    private var reported = false

    fun firstFrame() { hasFrame = true }

    fun resetSource() {
        hasFrame = false
        seeking = false
        endEpisode()
    }

    fun seek() {
        seeking = true
        endEpisode()
    }

    fun sample(now: Long, buffering: Boolean, wantsPlayback: Boolean): Boolean {
        if (!buffering) seeking = false
        if (!buffering || !wantsPlayback || !hasFrame || seeking) {
            endEpisode()
            return false
        }
        val start = since ?: now.also { since = it }
        if (reported || now - start < 1_000L) return false
        reported = true
        return true
    }

    private fun endEpisode() {
        since = null
        reported = false
    }
}
