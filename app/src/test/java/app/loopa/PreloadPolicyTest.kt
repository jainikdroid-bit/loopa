package app.loopa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreloadPolicyTest {
    private val MB = PreloadPolicy.MB

    @Test fun fastNetworkPreloadsTwoAheadInScrollDirection() {
        assertEquals(Plan.Range(3_000), PreloadPolicy.plan(1, 1, NetKind.FAST, false))
        assertEquals(Plan.Range(2_000), PreloadPolicy.plan(2, 1, NetKind.FAST, false))
        assertEquals(Plan.None, PreloadPolicy.plan(3, 1, NetKind.FAST, false))
    }

    @Test fun scrollingBackPreloadsBackwards() {
        assertEquals(Plan.Range(3_000), PreloadPolicy.plan(-1, -1, NetKind.FAST, false))
        assertEquals(Plan.Prepare, PreloadPolicy.plan(1, -1, NetKind.FAST, false))
        assertEquals(Plan.None, PreloadPolicy.plan(2, -1, NetKind.FAST, false))
    }

    @Test fun previousOnlyPreparedOnFastNetwork() {
        assertEquals(Plan.Prepare, PreloadPolicy.plan(-1, 1, NetKind.FAST, false))
        assertEquals(Plan.None, PreloadPolicy.plan(-2, 1, NetKind.FAST, false))
        assertEquals(Plan.None, PreloadPolicy.plan(-1, 1, NetKind.METERED, false))
    }

    @Test fun meteredAndSlowPreloadOnlyNextAndLess() {
        assertEquals(Plan.Range(1_500), PreloadPolicy.plan(1, 1, NetKind.METERED, false))
        assertEquals(Plan.None, PreloadPolicy.plan(2, 1, NetKind.METERED, false))
        assertEquals(Plan.Range(1_500), PreloadPolicy.plan(1, 1, NetKind.SLOW, false))
        assertEquals(Plan.None, PreloadPolicy.plan(2, 1, NetKind.SLOW, false))
    }

    @Test fun dataSaverAndOfflinePreloadNothing() {
        for (n in listOf(NetKind.SAVER, NetKind.OFFLINE)) {
            assertEquals(0, PreloadPolicy.depth(n, false))
            assertEquals(Plan.None, PreloadPolicy.plan(1, 1, n, false))
            assertEquals(Plan.None, PreloadPolicy.plan(-1, 1, n, false))
        }
    }

    @Test fun flingingSkipsIntermediateClips() {
        assertEquals(1, PreloadPolicy.depth(NetKind.FAST, true))
        assertEquals(Plan.Range(3_000), PreloadPolicy.plan(1, 1, NetKind.FAST, true))
        assertEquals(Plan.None, PreloadPolicy.plan(2, 1, NetKind.FAST, true))
        assertEquals(Plan.None, PreloadPolicy.plan(-1, 1, NetKind.FAST, true))
    }

    @Test fun currentClipIsNeverPlanned() {
        assertEquals(Plan.None, PreloadPolicy.plan(0, 1, NetKind.FAST, false))
    }

    @Test fun flingingDetectedFromShortDwells() {
        assertFalse(PreloadPolicy.flinging(emptyList()))
        assertFalse(PreloadPolicy.flinging(listOf(300L)))
        assertTrue(PreloadPolicy.flinging(listOf(5_000L, 400L, 500L)))
        assertFalse(PreloadPolicy.flinging(listOf(400L, 500L, 4_000L)))
    }

    @Test fun autoCacheIsFivePercentOfFreeWithinBounds() {
        assertEquals(512 * MB, PreloadPolicy.cacheBytes(0, 4_000 * MB))
        assertEquals(800 * MB, PreloadPolicy.cacheBytes(0, 16_000 * MB))
        assertEquals(1024 * MB, PreloadPolicy.cacheBytes(0, 100_000 * MB))
    }

    @Test fun cacheNeverTakesMoreThanQuarterOfSpace() {
        assertEquals(250 * MB, PreloadPolicy.cacheBytes(0, 1_000 * MB))
        assertEquals(500 * MB, PreloadPolicy.cacheBytes(2048, 2_000 * MB))
        assertEquals(64 * MB, PreloadPolicy.cacheBytes(0, 100 * MB))
    }

    @Test fun chosenCacheSizeIsUsedWhenThereIsRoom() {
        assertEquals(2048 * MB, PreloadPolicy.cacheBytes(2048, 50_000 * MB))
        // занятое кэшем место тоже считается доступным под кэш
        assertEquals(1024 * MB, PreloadPolicy.cacheBytes(1024, 4_000 * MB, 1_000 * MB))
    }
}
