package app.loopa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InterestsPairsTest {
    private val generic = setOf("blonde", "pov", "amateur")
    private val isGeneric: (String) -> Boolean = { it in generic }

    @Test fun `в сочетания берутся до двенадцати конкретных тегов`() {
        val tags = (1..20).map { "t%02d".format(it) }
        val pairs = Interests.pairs(tags, isGeneric)
        assertEquals(12 * 11 / 2, pairs.size)
        assertTrue("t01,t12" in pairs)
        assertFalse(pairs.any { "t13" in it })
    }

    @Test fun `общие теги связываются с конкретными, но не между собой`() {
        val pairs = Interests.pairs(listOf("blonde", "pov", "car", "garage"), isGeneric)
        assertTrue("blonde,car" in pairs); assertTrue("blonde,garage" in pairs)
        assertTrue("car,pov" in pairs); assertTrue("garage,pov" in pairs)
        assertTrue("car,garage" in pairs)
        assertFalse("blonde,pov" in pairs)
        assertEquals(5, pairs.size)
    }

    @Test fun `общий тег весит треть пары конкретных`() {
        assertEquals(Interests.GENERIC_PAIR_K, Interests.pairFactor("blonde,car", isGeneric), 1e-9)
        assertEquals(0.3, Interests.pairFactor("car,pov", isGeneric), 1e-9)
        assertEquals(1.0, Interests.pairFactor("car,garage", isGeneric), 1e-9)
    }

    @Test fun `ключи пар — по алфавиту, теги с запятой и r-метки пропускаются`() {
        val pairs = Interests.pairs(listOf("Zebra", "apple", "r/sub", "a,b", "Big  Tits"), isGeneric)
        assertEquals(listOf("apple,zebra", "apple,big tits", "big tits,zebra").sorted(), pairs.sorted())
    }
}
