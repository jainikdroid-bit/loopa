package app.loopa

import java.util.concurrent.FutureTask
import org.junit.Assert.*
import org.junit.Test

class PreviewLifecycleTest {
    @Test fun retryBudgetCannotBeExceededUntilHealthy() {
        val retries = PreviewRetryState()
        assertEquals(1, retries.failure("a"))
        assertEquals(2, retries.failure("a"))
        assertEquals(3, retries.failure("a"))
        assertNull(retries.failure("a"))
        assertTrue(retries.exhausted("a"))
        assertNull(retries.failure("a"))
        assertFalse(retries.exhausted("b"))
        retries.healthy("a")
        assertEquals(1, retries.failure("a"))
    }

    @Test fun cancellationBeforeResourcesAttach() {
        val job = PrefetchJob()
        job.cancel()
        val task = FutureTask { 1 }
        job.attach(task)
        assertTrue(task.isCancelled)
        var cancelled = false
        assertFalse(job.attachWriter { cancelled = true })
        assertTrue(cancelled)
    }

    @Test fun cancellationAfterResourcesAttach() {
        val job = PrefetchJob()
        val task = FutureTask { 1 }
        var count = 0
        job.attach(task)
        assertTrue(job.attachWriter { count++ })
        job.cancel()
        job.cancel()
        assertTrue(job.cancelled)
        assertTrue(task.isCancelled)
        assertEquals(1, count)
    }
}
