package app.loopa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Разбор выдачи и страницы ролика xHamster на коротких фрагментах реальных ответов (2026-11).
 * org.json в JVM-тестах — заглушка Android: без `testImplementation("org.json:json:…")` тесты разбора JSON пропускаются.
 */
class XHamsterTest {
    private fun needJson() = assumeTrue("нет настоящего org.json", runCatching { org.json.JSONObject("{\"a\":1}").getInt("a") == 1 }.getOrDefault(false))

    private val card = """{"id":30514078,"duration":982,"created":1787671816,"title":"Young Wife Cheated on Her Husband with a Delivery Guy","thumbId":17874665,"videoType":"video","pageURL":"https://xhamster.com/videos/young-wife-cheated-on-her-husband-with-a-delivery-guy-xhE0cfX","thumbURL":"https://ic-vt-nss.xhcdn.com/a/NzhhMDU4/s(w:526,h:298),webp/030/514/078/1280x720.17874665.jpg","imageURL":"https://ic-vt-nss.xhcdn.com/a/NzRiOWI1/s(w:640,h:360),webp/030/514/078/1280x720.17874665.jpg","views":23062724,"landing":{"type":"person","id":363853,"name":"Comatozze","link":"https://xhamster.com/creators/comatozze"}}"""
    /** Канал не указан — landing: null (так бывает у старых роликов). */
    private val cardNoLanding = """{"id":5866037,"duration":1802,"created":1459506303,"title":"MILF Picked Up","videoType":"video","pageURL":"https://xhamster.com/videos/milf-picked-up-and-fucked-5866037","thumbURL":"https://ic-vt-nss.xhcdn.com/a/Y2Zm/s(w:526,h:298),webp/005/866/037/526x298.3.webp","views":3977509,"landing":null}"""
    /** Moments: нет duration, адрес /shorts/ — в ленту не берём. */
    private val short = """{"id":26007111,"title":"CreamPie","pageURL":"https://xhamster.com/shorts/creampie-xhTPnJY","thumbURL":"https://ic-vt-nss.xhcdn.com/a/NTk3/s(w:350,h:620),webp/026/007/111/orig.2.jpg","views":944908}"""

    private fun listPage(vararg cards: String, last: Int? = 35285): String {
        val pagination = if (last != null) """{"paginationProps":{"currentPageNumber":1,"lastPageNumber":$last}}""" else "{}"
        return """<html><head><script>window.initials={"layoutPage":{"trendingVideoListProps":{"videoThumbProps":[${cards.joinToString(",")}],"additionalItems":[{"type":"momentsList","props":{"videoThumbProps":[$short]}}]}},"layout":{"layoutBottomMobileProps":$pagination}};</script></head></html>"""
    }

    @Test fun parsesCards() {
        needJson()
        val p = XHamster.parseList(listPage(card, cardNoLanding), 1)
        assertEquals(2, p.gifs.size)
        val g = p.gifs[0]
        assertEquals("xh:30514078", g.id)
        assertEquals("xhamster", g.site)
        assertEquals("Comatozze", g.user)
        assertEquals(982.0, g.duration, 0.0)
        assertEquals(23062724L, g.views)
        assertEquals(1787671816L, g.created)
        assertEquals("Young Wife Cheated on Her Husband with a Delivery Guy", g.description)
        assertTrue(g.hls)
        assertTrue(g.thumb.contains("526,h:298"))
        assertEquals("", p.gifs[1].user)
    }

    @Test fun addressIsLazyWithPagePathAsKey() {
        needJson()
        val g = XHamster.parseList(listPage(card), 1).gifs.single()
        val key = "/videos/young-wife-cheated-on-her-husband-with-a-delivery-guy-xhE0cfX"
        assertEquals(Sources.lazy("xhamster", key, true), g.hd)
        assertEquals(Sources.lazy("xhamster", key, false), g.sd)
    }

    @Test fun shortsAndDuplicatesAreSkipped() {
        needJson()
        val p = XHamster.parseList(listPage(card, short, card), 1)
        assertEquals(1, p.gifs.size)
    }

    @Test fun nextPageUsesLastPageNumberOrGuessesInSearch() {
        needJson()
        assertEquals(2, XHamster.parseList(listPage(card), 1).pages)
        assertEquals(35285, XHamster.parseList(listPage(card, last = 35285), 35285).pages)
        // в поиске номера последней страницы нет: идём дальше, пока есть ролики
        assertEquals(4, XHamster.parseList(listPage(card, last = null), 3).pages)
        assertEquals(3, XHamster.parseList(listPage(last = null), 3).pages)
    }

    @Test fun pageWithoutInitialsIsEmpty() {
        val p = XHamster.parseList("<html>captcha</html>", 2)
        assertTrue(p.gifs.isEmpty())
        assertEquals(2, p.pages)
    }

    @Test fun listUrls() {
        assertEquals("https://xhamster.com/categories/amateur", XHamster.listUrl("", false, 1))
        assertEquals("https://xhamster.com/categories/amateur/3", XHamster.listUrl(" ", false, 3))
        assertEquals("https://xhamster.com/categories/amateur/newest", XHamster.listUrl("", true, 1))
        assertEquals("https://xhamster.com/categories/amateur/newest/2", XHamster.listUrl("", true, 2))
        assertEquals("https://xhamster.com/search/big+tits?page=2", XHamster.listUrl("big tits", false, 2))
        assertEquals("https://xhamster.com/search/milf?sort=newest&page=1", XHamster.listUrl("milf", true, 1))
    }

    private val tpl = "https://video-nss.xhcdn.com/bCPKQItmom6AMBTGS649tA==,1794654000/media=hls4/multi=256x144:144p:,426x240:240p:,854x480:480p:,1280x720:720p:,1920x1080:1080p:/030/514/078/_TPL_.av1.mp4.m3u8"

    @Test fun findsPreloadTemplateInVideoPage() {
        val html = """<link rel="dns-prefetch" href="https://video-nss.xhcdn.com"/><link rel="preload" href="$tpl" as="fetch" crossorigin="true"/><link rel="preload" href="https://ic-vt-nss.xhcdn.com/a/x.jpg" as="image"/>"""
        assertEquals(tpl, XHamster.parseTemplate(html))
        assertNull(XHamster.parseTemplate("<html>нет видео</html>"))
    }

    @Test fun heightsFromMulti() {
        assertEquals(listOf(144, 240, 480, 720, 1080), XHamster.heights(tpl))
        assertEquals(listOf(144, 240), XHamster.heights("https://video-nss.xhcdn.com/k/media=hls4/multi=256x144:144p:,426x240:240p:/007/421/523/_TPL_.av1.mp4.m3u8"))
    }

    @Test fun variantsPreferH264ThenAv1AtRequestedQuality() {
        val hd = XHamster.variants(tpl, true)
        assertEquals(tpl.replace("_TPL_.av1.", "720p.h264."), hd[0])
        assertEquals(tpl.replace("_TPL_", "720p"), hd[1])
        val sd = XHamster.variants(tpl, false)
        assertTrue(sd[0].endsWith("/480p.h264.mp4.m3u8"))
        assertTrue(sd[1].endsWith("/480p.av1.mp4.m3u8"))
    }

    @Test fun lowQualityOnlyVideoTakesWhatExists() {
        val small = "https://video-nss.xhcdn.com/k/media=hls4/multi=256x144:144p:,426x240:240p:/007/421/523/_TPL_.av1.mp4.m3u8"
        assertTrue(XHamster.variants(small, true)[0].endsWith("/240p.h264.mp4.m3u8"))
        assertTrue(XHamster.variants(small, false)[0].endsWith("/240p.h264.mp4.m3u8"))
    }
}
