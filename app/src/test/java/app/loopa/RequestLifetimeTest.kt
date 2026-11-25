package app.loopa

import java.util.concurrent.CancellationException
import java.util.concurrent.FutureTask
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
}
