package app.loopa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceTierTest {
    private fun t(ram: Long = 8192, cores: Int = 8, low: Boolean = false, is64: Boolean = true) = DeviceTier.tier(ram, cores, low, is64)

    @Test fun strongPhoneIsHigh() = assertEquals(Tier.HIGH, t())
    @Test fun lowRamFlagIsLow() = assertEquals(Tier.LOW, t(low = true))
    @Test fun noSixtyFourBitIsLow() = assertEquals(Tier.LOW, t(is64 = false))

    @Test fun ramBelow3GbIsLow() {
        assertEquals(Tier.LOW, t(ram = 3071))
        assertEquals(Tier.MID, t(ram = 3072))
    }

    @Test fun fourCoresOrFewerIsLow() {
        assertEquals(Tier.LOW, t(cores = 4))
        assertEquals(Tier.MID, t(cores = 5))
    }

    @Test fun ramBelow6GbIsMid() {
        assertEquals(Tier.MID, t(ram = 6143))
        assertEquals(Tier.HIGH, t(ram = 6144))
    }

    @Test fun sixCoresOrFewerIsMid() {
        assertEquals(Tier.MID, t(cores = 6))
        assertEquals(Tier.HIGH, t(cores = 7))
    }

    @Test fun paramsGrowWithTier() {
        val l = DeviceTier.params(Tier.LOW); val m = DeviceTier.params(Tier.MID); val h = DeviceTier.params(Tier.HIGH)
        assertEquals(listOf(300, 800, 2000), listOf(l.maxNodes, m.maxNodes, h.maxNodes))
        assertEquals(listOf(600, 2000, 6000), listOf(l.maxLinks, m.maxLinks, h.maxLinks))
        assertEquals(listOf(15, 25, 30), listOf(l.labels, m.labels, h.labels))
        assertEquals(listOf(4, 6, 8), listOf(l.nodeResolution, m.nodeResolution, h.nodeResolution))
        assertEquals(listOf(1.0, 1.25, 1.5), listOf(l.pixelRatio, m.pixelRatio, h.pixelRatio))
        assertEquals(listOf(60, 100, 150), listOf(l.cooldownTicks, m.cooldownTicks, h.cooldownTicks))
        assertEquals(listOf(30, 50, 60), listOf(l.warmupTicks, m.warmupTicks, h.warmupTicks))
        assertTrue(l.thinLinks && !m.thinLinks && !h.thinLinks)
    }
}
