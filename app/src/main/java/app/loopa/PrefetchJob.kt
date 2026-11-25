package app.loopa

import java.util.concurrent.Future

/** Cancellation can happen before a future or writer has been installed. */
internal class PrefetchJob {
    private var future: Future<*>? = null
    private var cancelWriter: (() -> Unit)? = null
    @Volatile var cancelled = false
        private set

    @Synchronized fun attach(f: Future<*>) {
        if (cancelled) f.cancel(true) else future = f
    }

    @Synchronized fun attachWriter(cancel: () -> Unit): Boolean {
        if (cancelled) { cancel(); return false }
        cancelWriter = cancel
        return true
    }

    fun cancel() {
        val work = synchronized(this) {
            if (cancelled) return
            cancelled = true
            (future to cancelWriter).also { future = null; cancelWriter = null }
        }
        runCatching { work.second?.invoke() }
        work.first?.cancel(true)
    }
}
