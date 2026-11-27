package app.loopa

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackStallTest {
    @Test fun countsEachLongEpisodeOnceAfterTheFirstFrame() {
        val stall = PlaybackStall()
        assertFalse(stall.sample(0, buffering = true, wantsPlayback = true))
        assertFalse(stall.sample(2_000, buffering = true, wantsPlayback = true))
        stall.firstFrame()
        assertFalse(stall.sample(3_000, buffering = true, wantsPlayback = true))
        assertFalse(stall.sample(3_999, buffering = true, wantsPlayback = true))
        assertTrue(stall.sample(4_000, buffering = true, wantsPlayback = true))
        assertFalse(stall.sample(4_500, buffering = true, wantsPlayback = true))
        assertFalse(stall.sample(5_000, buffering = false, wantsPlayback = true))
        assertFalse(stall.sample(6_000, buffering = true, wantsPlayback = true))
        assertTrue(stall.sample(7_000, buffering = true, wantsPlayback = true))
    }

    @Test fun ignoresSeekingPauseAndNewSourceStartup() {
        val stall = PlaybackStall()
        stall.firstFrame()
        stall.seek()
        assertFalse(stall.sample(0, buffering = true, wantsPlayback = true))
        assertFalse(stall.sample(2_000, buffering = true, wantsPlayback = true))
        assertFalse(stall.sample(2_500, buffering = false, wantsPlayback = true))
        assertFalse(stall.sample(3_000, buffering = true, wantsPlayback = true))
        assertFalse(stall.sample(5_000, buffering = true, wantsPlayback = false))
        assertFalse(stall.sample(6_000, buffering = true, wantsPlayback = true))
        stall.resetSource()
        assertFalse(stall.sample(8_000, buffering = true, wantsPlayback = true))
    }
}
