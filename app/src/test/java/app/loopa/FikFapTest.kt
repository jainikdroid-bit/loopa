package app.loopa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Разбор ответов api.fikfap.com на коротких фрагментах реальных ответов (2026-11).
 * org.json в JVM-тестах — заглушка Android: без `testImplementation("org.json:json:…")` тесты разбора JSON пропускаются.
 */
class FikFapTest {
    private fun needJson() = assumeTrue("нет настоящего org.json", runCatching { org.json.JSONObject("{\"a\":1}").getInt("a") == 1 }.getOrDefault(false))

    private val post = """{"postId":2036800,"label":"This lingerie makes this tease harder 🥵\nsecond line","score":0,"likesCount":7,"userId":"59b736d7","mediaId":"9dadaa15","duration":22,"viewsCount":1234,"bunnyVideoId":"6288c977","isBunnyVideoReady":true,"createdAt":"2026-11-14T07:25:29.422Z","publishedAt":"2026-11-14T07:29:16.259Z","algorithm":"find-all-posts","isBoosted":false,"videoStreamUrl":"https://vz-5d293dac-178.b-cdn.net/bcdn_token=BX00&expires=1794645051/6288c977/playlist.m3u8","thumbnailStreamUrl":"https://vz-5d293dac-178.b-cdn.net/6288c977/thumbnail.jpg?token=V5bK&height=768&width=432&expires=1794645051","author":{"userId":"59b736d7","username":"EmilySweet","countPosts":421},"hashtags":[{"hashtagId":"473b","label":"OnOff","labelLower":"onoff"},{"hashtagId":"3d8e","label":"GoneWild","labelLower":"gonewild"},{"hashtagId":"x","label":"big tits","labelLower":"big tits"}]}"""

    @Test fun parsesPostArray() {
        needJson()
        val g = FikFap.parsePosts("[$post]").single()
        assertEquals("ff:2036800", g.id)
        assertEquals("fikfap", g.site)
        assertEquals("EmilySweet", g.user)
        assertEquals(22.0, g.duration, 0.0)
        assertEquals(1234L, g.views)
        assertEquals(7L, g.likes)
        assertEquals(720, g.w)
        assertEquals(1280, g.h)
        assertTrue(g.hls)
        assertEquals("This lingerie makes this tease harder 🥵 second line", g.description)
        assertEquals(listOf("onoff", "gonewild"), g.tags)   // тег с пробелом не годится
        assertTrue(g.thumb.startsWith("https://vz-5d293dac-178.b-cdn.net/"))
    }

    @Test fun streamAddressIsLazyAndSameForBothQualities() {
        needJson()
        val g = FikFap.parsePosts("[$post]").single()
        assertEquals("loopa://fikfap/2036800/hd", g.hd)
        assertEquals(g.hd, g.sd)
        assertTrue(Sources.isLazy(g.hd))
    }

    @Test fun createdIsUnixSecondsOfPublishedAt() {
        assertEquals(1794641356L, FikFap.iso("2026-11-14T07:29:16.259Z"))
        assertEquals(0L, FikFap.iso("мусор"))
    }

    @Test fun searchEnvelopeIsAccepted() {
        needJson()
        assertEquals(1, FikFap.parsePosts("""{"posts":[$post]}""").size)
        assertTrue(FikFap.parsePosts("""{"other":1}""").isEmpty())
    }

    @Test fun skipsAdsNotReadyAndWithoutStream() {
        needJson()
        val boosted = post.replace("\"isBoosted\":false", "\"isBoosted\":true")
        val notReady = post.replace("\"isBunnyVideoReady\":true", "\"isBunnyVideoReady\":false")
        val noStream = post.replace(Regex(""""videoStreamUrl":"[^"]*","""), "")
        assertTrue(FikFap.parsePosts("[$boosted,$notReady,$noStream]").isEmpty())
    }

    @Test fun tagFromQuery() {
        assertEquals("milf", FikFap.tagOf("  #MILF "))
        assertEquals("bigtits", FikFap.tagOf("Big Tits"))
        assertEquals("", FikFap.tagOf("   "))
    }

    @Test fun cdnHostsNeedReferer() {
        assertTrue(FikFap.ours("https://vz-5d293dac-178.b-cdn.net/x/playlist.m3u8"))
        assertTrue(FikFap.ours("https://fikfap-media-prod.b-cdn.net/users/a.jpg"))
        assertFalse(FikFap.ours("https://example.com/b-cdn.net"))
        assertFalse(FikFap.ours("https://evilb-cdn.net/x"))
    }
}
