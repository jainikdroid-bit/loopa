package app.loopa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Разбор EroProfile (фрагменты реальных страниц 2026-11-19).
 */
class EroProfileTest {
    private val tile = """<div class="grid-tile-video"><a href="/m/videos/view/Wife-Has-to-Pee-on-Walk" class="preview redirect-link"><img src="https://static.eroprofile.com/ui/img/tr.gif" data-src="https://s.cdn.eroprofile.com/videos/p/1903/19028251.jpg?validfrom=1795071600&amp;validto=1795082400&amp;hash=TFJxj1HebjsjQvfv%2FFR%2B3uwQuk4%3D" alt="Wife Has to Pee on Walk" class="lazyload"><div class="rating"><span class="mat-ico">&#xe8dc;</span> <span>100%</span></div><div class="duration">01:02:11</div></a><div class="title cap-line">Sch&ouml;n Wife Has to Pee</div><a href="/m/videos/search?niche=8">Swingers / Gangbang</a><div class="text-sm cap-line">12 hours ago <span class="colGray">by</span> <a href="/Spazzer" class="redirect-link">Spazzer</a></div></div>"""
    private val tile2 = """<div class="grid-tile-video"><a href="/m/videos/view/Rubber-and-tug" class="preview redirect-link"><img src="x.gif" data-src="https://s.cdn.eroprofile.com/videos/p/1903/19029156.jpg?a=1&amp;b=2" alt=""></a><div class="duration">00:34</div><div class="title cap-line">Rubber and tug</div><a href="/m/videos/search?niche=12">Amateurs</a><div class="text-sm cap-line">2 days ago <span class="colGray">by</span> <a href="/nakedinontario" class="redirect-link">nakedinontario</a></div></div>"""
    private val noLink = """<div class="grid-tile-video"><div class="title cap-line">без ссылки</div></div>"""

    @Test fun eroProfileParsesTiles() {
        val now = 1_795_000_000L
        val l = EroProfile.parseList("<html>$tile$noLink$tile2</html>", now)
        assertEquals(2, l.size)
        val g = l[0]
        assertEquals("eo:Wife-Has-to-Pee-on-Walk", g.id)
        assertEquals("eroprofile", g.site)
        assertEquals("Spazzer", g.user)
        assertEquals("Schön Wife Has to Pee", g.description)
        assertEquals(3731.0, g.duration, 0.0)
        assertEquals(listOf("swingers", "gangbang"), g.tags)
        assertEquals(now - 12 * 3600, g.created)
        assertTrue(g.thumb.contains("validto=1795082400&hash="))
        assertTrue(Sources.isLazy(g.hd))
        assertEquals(g.hd, g.sd)
        assertEquals("loopa://eroprofile/Wife-Has-to-Pee-on-Walk/hd", g.hd)
        assertEquals(now - 2 * 86400, l[1].created)
        assertEquals("https://s.cdn.eroprofile.com/videos/p/1903/19029156.jpg?a=1&b=2", l[1].thumb)
    }

    @Test fun eroProfileParsesVideoPage() {
        val html = """<video id="vidVideoViewPlayer" poster="x" controls>
            <source src="https://l.cdn.eroprofile.com/videos/m/1903/19028251.m4v?validfrom=1795071600&amp;validto=1795082400&amp;burst=2560k&amp;rate=192k&amp;hash=tzvpBRMYpGYasn7N8hFNlPFPthY%3D" type="video/mp4"></video>"""
        assertEquals("https://l.cdn.eroprofile.com/videos/m/1903/19028251.m4v?validfrom=1795071600&validto=1795082400&burst=2560k&rate=192k&hash=tzvpBRMYpGYasn7N8hFNlPFPthY%3D",
            EroProfile.parseVideo(html))
        assertNull(EroProfile.parseVideo("<html>нет видео</html>"))
    }

    @Test fun eroProfilePaging() {
        val html = """<a href="/m/videos/popular?pnum=1">1</a><a href="/m/videos/popular?pnum=2">2</a>"""
        assertTrue(EroProfile.hasNext(html, 1))
        assertFalse(EroProfile.hasNext(html, 2))
        assertEquals(93.0, EroProfile.seconds("01:33"), 0.0)
    }

    private fun post(id: Int, url: String?, extra: String = "", removed: Boolean = false) =
        """{"post":{"id":$id,"name":"Hot wife with toys, #fun","url":${if (url == null) "null" else "\"$url\""},"removed":$removed,"deleted":false,"published":"2026-11-19T08:48:21Z","thumbnail_url":"https://lm.example/pictrs/image/t.jpg"$extra},
            "community":{"id":1,"name":"amateur_nsfw"},"creator":{"id":2,"name":"someone"},"counts":{"comments":5,"score":42}}"""

    private fun feed(vararg p: String) = """{"posts":[${p.joinToString(",")}]}"""
}
