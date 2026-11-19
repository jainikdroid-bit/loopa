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

    @Test fun `превью всего - больше аппаратных, кроме слабого телефона`() {
        assertEquals(3, DeviceTier.previewTotal(Tier.LOW))
        assertEquals(6, DeviceTier.previewTotal(Tier.MID))
        assertEquals(8, DeviceTier.previewTotal(Tier.HIGH))
        // аппаратных декодеров 4 — аппаратных превью 2, остальные 6 — программные
        assertEquals(2, DeviceTier.previewLimit(Tier.HIGH, 4))
    }

    @Test fun `превью - три куска 10, 50 и 80 процентов, первый после заставки`() {
        assertEquals(listOf(4_400L, 22_000L, 35_200L), GridPreview.previewCuts(44_000, 0, 2000).toList())
        assertEquals(listOf(5_000L, 15_000L, 24_000L), GridPreview.previewCuts(30_000, 5_000, 2000).toList())   // 10 % < заставки
        assertEquals(listOf(264_000L, 1_320_000L, 2_112_000L), GridPreview.previewCuts(44 * 60_000L, 3400, 2500).toList())
    }
}
