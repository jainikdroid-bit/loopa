package app.loopa

import org.junit.Assert.*
import org.junit.Test

class DiagnosticPolicyTest {
    @Test fun boundsAndFiniteValues() {
        assertTrue(DiagnosticPolicy.validSample(listOf("neutral" to 1f), 1f, 2f, 0.8))
        assertFalse(DiagnosticPolicy.validSample(listOf("neutral" to Float.NaN), 1f, 2f, 0.8))
        assertFalse(DiagnosticPolicy.validSample(listOf("neutral" to 1f), 1f, 2f, 1.1))
        assertFalse(DiagnosticPolicy.validSample(List(129) { "x$it" to 1f }, 1f, 2f, 0.8))
        assertFalse(DiagnosticPolicy.validSample(listOf("x" to 1f, "x" to 2f), 1f, 2f, 0.8))
    }
    @Test fun onlyTechnicalCategoriesCanBeExported() {
        assertTrue("buffering" in DiagnosticPolicy.technicalKinds)
        assertFalse("watch" in DiagnosticPolicy.technicalKinds)
        assertFalse("like" in DiagnosticPolicy.technicalKinds)
        assertFalse("feature" in DiagnosticPolicy.technicalKinds)
        assertFalse(DiagnosticPolicy.validKind("invalid free text"))
    }
}
