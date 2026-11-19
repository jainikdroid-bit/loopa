package app.loopa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HlsPackTest {
    private val master = """
        #EXTM3U
        #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud",NAME="a",URI="HLS_AUDIO_64.m3u8"
        #EXT-X-STREAM-INF:BANDWIDTH=800000,AVERAGE-BANDWIDTH=700000,AUDIO="aud"
        HLS_360.m3u8
        #EXT-X-STREAM-INF:BANDWIDTH=2400000,AUDIO="aud"
        HLS_720.m3u8
    """.trimIndent()

    @Test fun variantsSortedBestFirstWithAudio() {
        val v = HlsPack.variants(master, "https://v.redd.it/abc/HLSPlaylist.m3u8")
        assertEquals(listOf("https://v.redd.it/abc/HLS_720.m3u8", "https://v.redd.it/abc/HLS_360.m3u8"), v.map { it.url })
        assertEquals(800000L, v[1].bandwidth)
        assertEquals("https://v.redd.it/abc/HLS_AUDIO_64.m3u8", v[0].audio)
    }

    @Test fun mediaListsInitAndSegments() {
        val m = HlsPack.media("#EXTM3U\n#EXT-X-MAP:URI=\"init.mp4\"\n#EXTINF:4.0,\nseg0.m4s\n#EXTINF:4.0,\nhttps://cdn/x/seg1.m4s?s=1\n#EXT-X-ENDLIST", "https://a/b/p.m3u8")
        assertEquals("https://a/b/init.mp4", m.init)
        assertEquals(listOf("https://a/b/seg0.m4s", "https://cdn/x/seg1.m4s?s=1"), m.segments)
        assertTrue(!m.encrypted)
    }

    @Test fun encryptedIsFlagged() {
        val m = HlsPack.media("#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"k\"\n#EXTINF:4,\ns.ts", "https://a/p.m3u8")
        assertTrue(m.encrypted)
        assertNull(m.init)
    }

    @Test fun masterDetection() {
        assertTrue(HlsPack.isMaster(master))
        assertTrue(!HlsPack.isMaster("#EXTM3U\n#EXTINF:4,\ns.ts"))
    }
}
