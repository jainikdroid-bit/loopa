package app.loopa

import org.junit.Assert.*
import org.junit.Test

class MediaIdentityTest {
    @Test fun rejectsStableDuplicatesWithoutPoisoningOtherKeys() {
        val ids = MediaIdentity()
        assertTrue(ids.accept("a", "url-a"))
        assertFalse(ids.accept("a", "url-b"))
        assertTrue(ids.accept("b", "url-b"))
        assertFalse(ids.accept("c", "url-a"))
        assertTrue(ids.accept("c", "url-c"))
    }
    @Test fun missingUrlsAreNotSharedIdentities() {
        val ids = MediaIdentity()
        assertTrue(ids.accept("a", ""))
        assertTrue(ids.accept("b", ""))
        assertFalse(ids.accept("b", ""))
    }
    @Test fun distinctNamespacesDoNotCollide() {
        val ids = MediaIdentity()
        assertTrue(ids.accept("a", "b"))
        assertTrue(ids.accept("b", "c"))
    }
}
