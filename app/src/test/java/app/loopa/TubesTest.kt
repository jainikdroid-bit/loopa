package app.loopa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Разбор YouJizz и PornOne без сети; фрагменты — из живых ответов сайтов (2026-11-19). */
class TubesTest {
    // ---- YouJizz ----

    private val yjList = """
        <ul class="pagination"><li><a class="pagination-next" href="/categories/amateur-2.html">Next &raquo;</a></li></ul>
        <div class="clearfix" >
        <div class="video-thumb" data-videoId="106340811">
    <div class="default video-item">
        <div class="frame-wrapper">
            <a data-video-id="106340811" class="frame video" href="/videos/real-amateur-shared-wives%21-106340811.html" target="_self" data-clip="//cdne-mobile.youjizz.com/videos/e/b/clip.mp4?validfrom=1&amp;validto=2">
                <img class="img-responsive lazy" src="https://cdne-static.youjizz.com/app/1/images/spacer.gif" data-original="//cdne-pics.youjizz.com/e/b/4/c/4/eb4c40f833-124.jpg" alt="" />
                <span class="i-hd" data-i18n="video.videothumb.hd">HD</span>
            </a>
        </div>
        <div class="video-title">
            <a href='/videos/real-amateur-shared-wives%21-106340811.html' class="agego-content-title">Real Amateur Shared Wives!</a>
        </div>
        <div class="video-content-wrapper text-center">
            <span class="time"><i class="fa fa-clock-o"></i>&nbsp;10:38</span>
            <span class="views"><i class="fa fa-eye"></i>&nbsp;<span class="format-views">612390</span></span>
        </div>
    </div>
</div>
        <div class="video-thumb" data-videoId="11755061">
    <div class="default video-item">
        <div class="frame-wrapper">
            <a data-video-id="11755061" class="frame video" href="/videos/hottest-amateur-ever-banged-3-times-11755061.html" target="_self" data-clip="//x">
                <img class="img-responsive lazy" src="x.gif" data-original="//cdne-pics.youjizz.com/a/7/e/0/d/a7e0-329.jpg" alt="" />
            </a>
        </div>
        <div class="video-title"><a href='/videos/hottest-amateur-ever-banged-3-times-11755061.html' class="agego-content-title">Tom &amp; Jerry&#039;s</a></div>
        <div class="video-content-wrapper text-center">
            <span class="time"><i class="fa fa-clock-o"></i>&nbsp;1:02:03</span>
            <span class="views"><i class="fa fa-eye"></i>&nbsp;<span class="format-views">1234</span></span>
        </div>
    </div></div>
    """

    @Test fun youjizzList() {
        val page = YouJizz.parseList(yjList, 1)
        assertEquals(2, page.gifs.size)
        assertEquals(2, page.pages)
        val g = page.gifs[0]
        assertEquals("yj:106340811", g.id)
        assertEquals("youjizz", g.site)
        assertEquals("https://cdne-pics.youjizz.com/e/b/4/c/4/eb4c40f833-124.jpg", g.thumb)
        assertEquals("Real Amateur Shared Wives!", g.description)
        assertEquals(638.0, g.duration, 0.0)
        assertEquals(612390L, g.views)
        assertEquals("loopa://youjizz/%2Fvideos%2Freal-amateur-shared-wives%2521-106340811.html/hd", g.hd)
        assertTrue(g.sd.endsWith("/sd"))
        assertTrue(Sources.isLazy(g.hd))
        val g2 = page.gifs[1]
        assertEquals(3723.0, g2.duration, 0.0)
        assertEquals("Tom & Jerry's", g2.description)
    }

    @Test fun youjizzLastPage() {
        assertEquals(5, YouJizz.parseList(yjList.replace("pagination-next", "pagination-x"), 5).pages)
        assertTrue(YouJizz.parseList("<html></html>", 1).gifs.isEmpty())
    }

    @Test fun youjizzUrls() {
        assertEquals("https://www.youjizz.com/categories/amateur-1.html", YouJizz.listUrl("", false, 1))
        assertEquals("https://www.youjizz.com/categories/amateur-3.html", YouJizz.listUrl("  ", false, 3))
        assertEquals("https://www.youjizz.com/search/recent_amateur-2.html", YouJizz.listUrl("", true, 2))
        assertEquals("https://www.youjizz.com/search/mature%20anal-1.html", YouJizz.listUrl("mature anal", false, 1))
        assertEquals("https://www.youjizz.com/search/recent_mature-4.html", YouJizz.listUrl("mature", true, 4))
    }

    private val yjVideo = """
    var dataEncodings = [{"quality":"240","filename":"\/\/cdne-mobile.youjizz.com\/videos\/e\/b\/x-426-240-303-h264.mp4?validfrom=1&validto=2&rate=58176&hash=a%3D","is_old_origin":"0","version":"2","name":"240p"},{"quality":"720","filename":"\/\/cdne-mobile.youjizz.com\/videos\/e\/b\/x-1280-720-1192-h264.mp4?validfrom=1&validto=2&rate=1&hash=b%3D","name":"720p"},{"quality":"1080","filename":"\/\/cdne-mobile.youjizz.com\/videos\/e\/b\/x-1920-1080-3115-h264.mp4?validfrom=1","name":"1080p"},{"quality":"720","filename":"\/\/abre-videos.youjizz.com\/_hls\/videos\/e\/b\/x-1280-720-1192-h264.mp4\/master.m3u8?validfrom=1","name":"720p"}];
    var encodings = [];
    """

    @Test fun youjizzEncodings() {
        val list = YouJizz.parseEncodings(yjVideo)
        assertEquals(listOf(240, 720, 1080), list.map { it.height })   // HLS-копия пропущена
        assertTrue(list[1].url.startsWith("https://cdne-mobile.youjizz.com/videos/e/b/x-1280-720"))
        assertTrue(list[1].url.contains("hash=b%3D"))
        assertTrue(YouJizz.pick(list, 720)!!.contains("1280-720"))
        assertTrue(YouJizz.pick(list, 480)!!.contains("426-240"))
        assertTrue(YouJizz.pick(list.drop(1), 360)!!.contains("1280-720"))   // всё выше потолка — самое лёгкое
        assertNull(YouJizz.pick(emptyList(), 720))
        assertTrue(YouJizz.parseEncodings("<html></html>").isEmpty())
        assertTrue(YouJizz.parseEncodings("var dataEncodings = [oops];").isEmpty())
    }

    // ---- PornOne ----

    private val p1List = """
        <a href="https://pornone.com/spanish/hot-teen-from-spain/281195995/#r163" class="popbop vidLinkFX  videocard linkage">
            <div class="thumbcont">
              <span class="hdcont">
                  <span class="durlabel"><img  class="inline mr-[5px] mb-0.7" alt="HD Video" width="18" height="10"  src="https://th-eu4.pornone.com/images/svg/hd.svg">03:36
                  </span><span ids="281195995"  thumb="152" class="context-menu"></span></span><img src="" data-src="https://th-eu4.pornone.com/t/71/281195995/b152.jpg" alt="x" width="270" height="158" class="lazy-loading imgvideo thumbimg" id="281195995" data-path="https://th-eu4.pornone.com/t/71/281195995/" loading="lazy"/></div>
                <div class="videoinfo">
                    <div class="titlecont">
                      <div class="videotitle ">Hot Teen &amp; Friend </div>
                      <div class="author portrait:text-white flex text-brownish-grey">
                          <span class="basis-1/2 truncate">
                            by <span class="font-semibold capitalize">DwaynePowers</span>
                          </span>
                          <span class="basis-1/2 text-right">
                            <i class="statsicon viewsIcon"></i>1.5K
                          </span>
                      </div>
                    </div>
                </div>
             </a>
        <a href="https://pornone.com/eager/x-y/281195109/#r1" class="popbop vidLinkFX  videocard linkage"><span class="durlabel">00:10</span><img src="https://th-eu4.pornone.com/t/9/281195109/d162.jpg"  alt="e" class=" imgvideo thumbimg" id="281195109"/><div class="videotitle ">Eager</div></a>
        <a href="https://pornone.com/ad/" class="customnative vrazka cursor-pointer  videocard linkage"><span class="durlabel">00:10</span><img data-src="https://x/ad.jpg"/></a>
        <a href="https://pornone.com/goth/amateur-homemade-goth-stripper-michelle/281195203/" class="popbop vidLinkFX  videocard linkage">
              <span class="durlabel">1:28:59
              </span><img src="" data-src="https://th-eu4.pornone.com/t/3/281195203/b153.jpg" alt="y" class="thumbimg"/>
                      <div class="videotitle ">Goth Michelle</div>
                          <span class="basis-1/2 text-right"><i class="statsicon viewsIcon"></i>78</span>
        </a>
        <link rel="next" href="https://pornone.com/amateur/3/">
    """

    @Test fun pornoneList() {
        val page = PornOne.parseList(p1List, 2)
        assertEquals(3, page.gifs.size)   // рекламная карточка пропущена
        assertEquals(3, page.pages)
        assertEquals("https://th-eu4.pornone.com/t/9/281195109/d162.jpg", page.gifs[1].thumb)
        val g = page.gifs[0]
        assertEquals("p1:281195995", g.id)
        assertEquals("pornone", g.site)
        assertEquals("https://th-eu4.pornone.com/t/71/281195995/b152.jpg", g.thumb)
        assertEquals("Hot Teen & Friend", g.description)
        assertEquals("DwaynePowers", g.user)
        assertEquals(216.0, g.duration, 0.0)
        assertEquals(1500L, g.views)
        assertTrue(g.hd.startsWith("loopa://pornone/%2Fspanish%2Fhot-teen-from-spain%2F281195995%2F/"))
        val g2 = page.gifs[2]
        assertEquals("p1:281195203", g2.id)
        assertEquals(5339.0, g2.duration, 0.0)
        assertEquals(78L, g2.views)
        assertEquals("", g2.user)
    }

    @Test fun pornoneLastPage() {
        assertEquals(7, PornOne.parseList(p1List.replace("rel=\"next\"", "rel=\"x\""), 7).pages)
        assertTrue(PornOne.parseList("<html></html>", 1).gifs.isEmpty())
    }

    @Test fun pornoneUrls() {
        assertEquals("https://pornone.com/amateur/views/", PornOne.listUrl("", false, 1))
        assertEquals("https://pornone.com/amateur/views/3/", PornOne.listUrl("", false, 3))
        assertEquals("https://pornone.com/amateur/", PornOne.listUrl("", true, 1))
        assertEquals("https://pornone.com/amateur/2/", PornOne.listUrl("", true, 2))
        assertEquals("https://pornone.com/search/?q=mature+wife", PornOne.listUrl("mature wife", false, 1))
        assertEquals("https://pornone.com/search/newest/2/?q=mature", PornOne.listUrl("mature", true, 2))
    }

    @Test fun pornoneVideo() {
        val src = "https://s3112.pornone.com/vid2/2zu_Tw/1795082146/3/281195203/281195203_720x406_500k.mp4?lang=en"
        assertEquals(src, PornOne.parseVideo("""<video><source src="$src" type="video/mp4" label="480p" default res="480"/></video>"""))
        assertEquals(src, PornOne.parseVideo("""{"encodingFormat": "MP4", "contentUrl": "$src", "x": 1}"""))
        assertNull(PornOne.parseVideo("<html></html>"))
        assertFalse(PornOne.parseVideo("""<source src="$src&amp;a=1" type="video/mp4"/>""")!!.contains("&amp;"))
    }

    // ---- общее ----

    @Test fun clock() {
        assertEquals(638.0, clockSeconds("10:38"), 0.0)
        assertEquals(3723.0, clockSeconds("1:02:03"), 0.0)
        assertEquals(45.0, clockSeconds("45"), 0.0)
        assertEquals(0.0, clockSeconds(""), 0.0)
        assertEquals(0.0, clockSeconds("abc"), 0.0)
    }
}
