package app.loopa

import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.FutureTask

/** Owns the work of one UI session. Late registration after cancellation is rejected. */
internal class RequestLifetime {
    private val jobs = HashSet<Future<*>>()
    private val cancellations = LinkedHashSet<() -> Unit>()
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

    fun <T> run(block: () -> T): T {
        check()
        val previous = current
        scope.set(this)
        try {
            return block().also { check() }
        } finally {
            if (previous == null) scope.remove() else scope.set(previous)
        }
    }

    /** Register transport cancellation for this request, including connections opened by its child tasks. */
    fun onCancel(action: () -> Unit) {
        val registered = synchronized(this) {
            if (active) {
                cancellations.add(action)
                true
            } else false
        }
        if (!registered) action()
    }

    fun cancel() {
        val (pending, actions) = synchronized(this) {
            active = false
            (jobs.toList() to cancellations.toList()).also { jobs.clear(); cancellations.clear() }
        }
        pending.forEach { it.cancel(true) }
        actions.forEach { runCatching(it) }
    }

    companion object {
        private val scope = ThreadLocal<RequestLifetime>()
        val current: RequestLifetime? get() = scope.get()

        /** Pool threads inherit only the lifetime of the submitted operation. */
        fun <T> submit(pool: ExecutorService, block: () -> T): Future<T> {
            val lifetime = current ?: return pool.submit<T> { block() }
            lifetime.check()
            val job = lifetime.track(FutureTask { lifetime.run(block) })
            if (!job.isCancelled) pool.execute(job)
            return job
        }
    }
}
