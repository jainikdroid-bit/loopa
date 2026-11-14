package app.loopa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Разбор выдачи и страницы ролика xVideos/XNXX на коротких фрагментах реальных ответов (2026-11). */
class XVideosTest {
    private val xvCard = """
<div id="video_omaaiba1ac8" data-id="91923130" data-eid="omaaiba1ac8"  data-is-channel="1" class="frame-block thumb-block  " id="anc-tst-first_video-btn"><div class="thumb-inside"><div class="thumb"><a href="/video.omaaiba1ac8/intense_surprise_amateur_threesome"><img src="https://assets-cdn77.xvideos-cdn.com/img/lightbox/lightbox-blank.gif"
data-src="https://thumb-cdn77.xvideos-cdn.com/ce0ac7d4/6/xv_30_t.jpg"
data-idcdn="21"
id="pic_91923130"
/><span class="top-right-tags has-cc"><span class="video-hd-mark">1080p</span></span></a></div></div><div class="thumb-under"><p class="title"><a href="/video.omaaiba1ac8/intense_surprise_amateur_threesome" title="Intense Surprise amateur threesome">Intense Surprise amateur threesome <span class="duration">16 min</span></a></p><p class="metadata"><span class="bg"><span class="duration">16 min</span><span><a href="/primiciax"><span class="name">Primiciax</span></a><span><span class="sprfluous"> - </span> 3.7M <span class="sprfluous">Views</span></span><span class="sprfluous"> - </span></span></span></p></div><script>xv.thumbs.prepareVideo('omaaiba1ac8');</script></div>
"""

    /** В выдаче с сортировкой по дате в пути и превью стоит заглушка THUMBNUM, а в названии — сущности. */
    private val xvSorted = """
<div id="video_oludcbob581" data-id="92593333" data-eid="oludcbob581"  data-is-channel="1" class="frame-block thumb-block  "><div class="thumb-inside"><div class="thumb"><a href="/video.oludcbob581/THUMBNUM/el_dueno_del_apartamento"><img src="https://assets-cdn77.xvideos-cdn.com/img/lightbox/lightbox-blank.gif"
data-src="https://thumbs-gcore.xvideos-cdn.com/30d3374d/6/xv_THUMBNUM_t.jpg"
id="pic_92593333"
/></a></div></div><div class="thumb-under"><p class="title"><a href="/video.oludcbob581/THUMBNUM/el_dueno_del_apartamento" title="El due&ntilde;o del apartamento &amp; m&aacute;s">El due&ntilde;o <span class="duration">1 h 2 min</span></a></p><p class="metadata"><span class="bg"><span class="duration">1 h 2 min</span><span><a href="/emilisex12"><span class="name">Emilisex12</span></a><span><span class="sprfluous"> - </span> 3.5k <span class="sprfluous">Views</span></span><span class="text-disabled"><span class="sprfluous"> - </span> 12 hours ago</span></span></span></p></div></div>
"""

    private val xnCard = """
<div id="video_15hy0ff2" data-id="69701487" data-eid="15hy0ff2"  data-is-channel="1" class="thumb-block  with-uploader"><div class="thumb-inside"><div class="thumb"><a href="/video-15hy0ff2/amateur_couple_sex_on_sofa"><img src="https://assets-cdn77.xnxx-cdn.com/img/lightbox/lightbox-blank.gif"
data-src="https://thumb-cdn77.xnxx-cdn.com/195e3068/0/xn_15_t.jpg"
data-src-avif=""
id="pic_69701487" /></a></div></div><div class="uploader"><a href="/porn-maker/arrestme1"><span class="name">arrestme</span></a></div><div class="thumb-under"><p><a href="/video-15hy0ff2/amateur_couple_sex_on_sofa" title="amateur couple sex on sofa">amateur couple sex on sofa</a></p><p class="metadata"><span class="right">
916.1k <span class="icon-f icf-eye"></span><span class="superfluous">99%</span></span>
6min
<span class="video-hd"><span class="superfluous"> - </span>
1440p
</span></p></div><script>xv.thumbs.prepareVideo('15hy0ff2');</script></div>
"""

    @Test fun `карточка xVideos`() {
        val g = XParse.list(xvCard, "xv", "xvideos").single()
        assertEquals("xv:omaaiba1ac8", g.id)
        assertEquals("https://thumb-cdn77.xvideos-cdn.com/ce0ac7d4/6/xv_30_t.jpg", g.thumb)
        assertEquals("Intense Surprise amateur threesome", g.description)
        assertEquals(960.0, g.duration, 0.0)
        assertEquals(3_700_000L, g.views)
        assertEquals("Primiciax", g.user)
        assertEquals("xvideos", g.site)
        assertEquals(Sources.lazy("xvideos", "/video.omaaiba1ac8/intense_surprise_amateur_threesome", true), g.hd)
        assertEquals(Sources.lazy("xvideos", "/video.omaaiba1ac8/intense_surprise_amateur_threesome", false), g.sd)
    }

    @Test fun `заглушка THUMBNUM, сущности и часы`() {
        val g = XParse.list(xvSorted, "xv", "xvideos").single()
        assertEquals("https://thumbs-gcore.xvideos-cdn.com/30d3374d/6/xv_15_t.jpg", g.thumb)
        assertEquals(Sources.lazy("xvideos", "/video.oludcbob581/el_dueno_del_apartamento", true), g.hd)
        assertEquals("El dueño del apartamento & más", g.description)
        assertEquals(3720.0, g.duration, 0.0)
        assertEquals(3_500L, g.views)
    }

    @Test fun `карточка XNXX`() {
        val g = XParse.list(xnCard, "xn", "xnxx").single()
        assertEquals("xn:15hy0ff2", g.id)
        assertEquals("amateur couple sex on sofa", g.description)
        assertEquals(360.0, g.duration, 0.0)
        assertEquals(916_100L, g.views)
        assertEquals("arrestme", g.user)
        assertEquals("xnxx", g.site)
        assertEquals(Sources.lazy("xnxx", "/video-15hy0ff2/amateur_couple_sex_on_sofa", false), g.sd)
    }

    @Test fun `несколько карточек делятся по границам`() {
        assertEquals(listOf("xv:omaaiba1ac8", "xv:oludcbob581"), XParse.list(xvCard + xvSorted, "xv", "xvideos").map { it.id })
    }

    @Test fun `длительность и просмотры`() {
        assertEquals(31.0, XParse.seconds("31 sec"), 0.0)
        assertEquals(3900.0, XParse.seconds("1h 5min"), 0.0)
        assertEquals(1080.0, XParse.seconds(" > 18min - 1080p"), 0.0)
        assertEquals(1_234L, XParse.count("1,234"))
        assertEquals(652_500L, XParse.count("652.5k"))
    }

    @Test fun `следующая страница есть, пока в пагинации есть её номер`() {
        val pag = """<div class="pagination "><ul><li><a class="active" href="">1</a></li><li><a href="/tags/amateur/1">2</a></li><li><a href="/tags/amateur/2">3</a></li></ul></div>"""
        assertTrue(XParse.hasNext(pag, 1))
        assertTrue(XParse.hasNext(pag, 2))
        assertFalse(XParse.hasNext(pag, 3))
        assertTrue(XParse.hasNext("""<div class="pagination "><ul><li><a href="/?k=a&amp;p=2">3</a></li></ul>""", 2))
    }

    @Test fun `страница ролика даёт High и Low`() {
        val html = """
html5player.setVideoTitle('amateur couple sex on sofa');
html5player.setVideoUrlLow('https://mp4-cdn77.xnxx-cdn.com/195e3068/0/video_240p.mp4?secure=jjKUJBKP==,1794652323');
html5player.setVideoUrlHigh('https://mp4-cdn77.xnxx-cdn.com/195e3068/0/video_360p.mp4?secure=dQ_daf0S==,1794652323');
html5player.setVideoHLS('https://hls-cdn77.xnxx-cdn.com/x/hls.m3u8');
"""
        val v = XParse.video(html)!!
        assertEquals("amateur couple sex on sofa", v.title)
        assertEquals("https://mp4-cdn77.xnxx-cdn.com/195e3068/0/video_360p.mp4?secure=dQ_daf0S==,1794652323", v.high)
        assertEquals("https://mp4-cdn77.xnxx-cdn.com/195e3068/0/video_240p.mp4?secure=jjKUJBKP==,1794652323", v.low)
    }

    @Test fun `только один адрес и страница без видео`() {
        assertNotNull(XParse.video("setVideoUrlHigh('https://x/a.mp4?s=1');")?.high)
        assertNull(XParse.video("setVideoUrlHigh('https://x/a.mp4?s=1');")?.low)
        assertNull(XParse.video("<html>нет такого ролика</html>"))
    }

    @Test fun `адреса страниц считаются с нуля`() {
        assertEquals("https://www.xvideos.com/tags/amateur", XVideos.url("", false, 1))
        assertEquals("https://www.xvideos.com/tags/amateur/2", XVideos.url("", false, 3))
        assertEquals("https://www.xvideos.com/?k=big+tits&p=0", XVideos.url("big tits", false, 1))
        assertEquals("https://www.xvideos.com/?k=big+tits&sort=uploaddate&p=1", XVideos.url("big tits", true, 2))
        assertEquals("https://www.xnxx.com/search/amateur", Xnxx.url("", false, 1))
        assertEquals("https://www.xnxx.com/search/big+tits/1", Xnxx.url("big tits", false, 2))
        assertEquals("https://www.xnxx.com/search/week/big+tits/1", Xnxx.url("big tits", true, 2))
    }
}
