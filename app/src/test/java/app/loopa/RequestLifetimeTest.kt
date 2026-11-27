package app.loopa

import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class RequestLifetimeTest {
    @Test fun cancellingSessionDoesNotCancelIndependentSession() {
        val old = RequestLifetime()
        val next = RequestLifetime()
        val a = old.track(FutureTask { 1 })
        val b = next.track(FutureTask { 2 })
        old.cancel()
        assertTrue(a.isCancelled)
        assertFalse(b.isCancelled)
        assertFalse(old.active)
        assertTrue(next.active)
    }

    @Test fun lateRegistrationIsCancelled() {
        val lifetime = RequestLifetime()
        lifetime.cancel()
        assertTrue(lifetime.track(FutureTask { 1 }).isCancelled)
    }

    @Test(expected = CancellationException::class)
    fun closedLifetimeRejectsWork() {
        val lifetime = RequestLifetime()
        lifetime.cancel()
        lifetime.check()
    }

    @Test fun cancellationReachesTransportRegisteredByChildWork() {
        val lifetime = RequestLifetime()
        val started = CountDownLatch(1)
        val disconnected = CountDownLatch(1)
        val pool = Executors.newSingleThreadExecutor()
        try {
            val future = lifetime.run {
                RequestLifetime.submit(pool) {
                    assertSame(lifetime, RequestLifetime.current)
                    RequestLifetime.current!!.onCancel { disconnected.countDown() }
                    started.countDown()
                    CountDownLatch(1).await()
                }
            }
            assertTrue(started.await(3, TimeUnit.SECONDS))
            lifetime.cancel()
            assertTrue(future.isCancelled)
            assertEquals(0L, disconnected.count)
            assertNull(RequestLifetime.current)
        } finally {
            pool.shutdownNow()
        }
    }

    @Test fun lateTransportRegistrationClosesImmediately() {
        val lifetime = RequestLifetime()
        lifetime.cancel()
        var disconnected = false
        lifetime.onCancel { disconnected = true }
        assertTrue(disconnected)
    }
}
