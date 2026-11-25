package app.loopa

import java.util.concurrent.CancellationException
import java.util.concurrent.Future

/** Owns the work of one UI session. Late registration after cancellation is rejected. */
internal class RequestLifetime {
    private val jobs = HashSet<Future<*>>()
    @Volatile var active = true
        private set

    @Synchronized fun <T : Future<*>> track(job: T): T {
        jobs.removeAll { it.isDone }
        if (active) jobs.add(job) else job.cancel(true)
        return job
    }

    fun check() {
        if (!active || Thread.currentThread().isInterrupted) throw CancellationException("Session closed")
    }

    fun cancel() {
        val pending = synchronized(this) {
            active = false
            jobs.toList().also { jobs.clear() }
        }
        pending.forEach { it.cancel(true) }
    }
}
