package app.loopa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Разбор выдачи и страницы ролика TNAFlix/EMPFlix на коротких фрагментах реальных ответов (2026-11-19). */
class TnaFlixTest {
    private val first = """
            <div data-vid="8461856" data-num="1" class="col-xs-6 col-md-4 col-lg-4 col-xl-3 mb-3" data-first-thumb="10">
                <a class="thumb thumb-chrome video-thumb bg-dark" href="https://www.tnaflix.com/amateur-porn/SIS.PORN.-Girl-with-shaved-pussy-takes-stepbrothers-dick/video8461856" data-trailer="https://sl112.tnaflix.com/84/61/8461856/trailer.mp4?secure=S2UuA7gSU51JC8Vx-r_5ig==,1795164834">
                                            <img fetchpriority="high" src="https://img.tnaflix.com/a16:8q80w356r/112/84/61/8461856/thumbs/10.jpg" alt="SIS.PORN. Girl" width="300" height="150" onerror="this.src='/assets/img/video_cover_placeholder.jpg'" />
                    <div class="thumb-icon video-duration">10:01</div>
                                            <div class="thumb-icon max-quality">1080p</div>
                                    </a>
                <a href="https://www.tnaflix.com/amateur-porn/SIS.PORN.-Girl-with-shaved-pussy-takes-stepbrothers-dick/video8461856" class="video-title text-break">
                                            SIS.PORN. Girl with shaved pussy takes stepbrothers dick                                    </a>
                <div class="d-flex">
                            <a href="https://www.tnaflix.com/profile/lafayette18" class="me-2 text-start text-truncate badge badge-video-info badge-unverified">
                            lafayette18                        </a>
                    <div class="my-auto text-truncate text-small d-flex">
                            <div class="me-2">
                                <i class="icon-eye"></i>585                            </div>
                            <div>
                                <i class="icon-thumb-up"></i>100%
                            </div>
                    </div>
                </div>
            </div>
"""

    /** Ленивая карточка: превью в data-src, в src заглушка; путь с %-кодами, часы в длительности, просмотры с K, нет метки качества. */
    private val lazyCard = """
            <div data-vid="12174189" data-num="31" class="col-xs-6 col-md-4 col-lg-4 col-xl-3 mb-3" data-first-thumb="12">
                <a class="thumb thumb-chrome video-thumb bg-dark" href="https://www.empflix.com/amateur-porn/Father%2C-My-Pussy-Is-Aching/video12174189" data-trailer="https://sl182.empflix.com/12/17/12174189/trailer.mp4?secure=i9Zb5Y==,1795164834">
                    <img  class="lazyload" src="/assets/img/video_cover_placeholder.jpg" data-src="https://img.empflix.com/a16:8q80w356r/182/12/17/12174189/thumbs/12.jpg" alt="x" width="300" height="150" />
                    <div class="thumb-icon video-duration">1:02:03</div>
                </a>
                <a href="https://www.empflix.com/amateur-porn/Father%2C-My-Pussy-Is-Aching/video12174189" class="video-title text-break">
                    Father, Tom &amp; Jerry&#39;s show                                    </a>
                <div class="d-flex">
                    <a href="https://www.empflix.com/profile/LoveHomePorn" class="me-2 text-start text-truncate badge badge-video-info">
                        LoveHomePorn                        </a>
                    <div class="my-auto text-truncate text-small d-flex">
                        <div class="me-2"><i class="icon-eye"></i>18.9K </div>
                    </div>
                </div>
            </div>
"""

    @Test fun `карточка с превью в src`() {
        val g = FlixParse.list(first, "tf", "tnaflix").single()
        assertEquals("tf:8461856", g.id)
        assertEquals("https://img.tnaflix.com/a16:8q80w356r/112/84/61/8461856/thumbs/10.jpg", g.thumb)
        assertEquals("SIS.PORN. Girl with shaved pussy takes stepbrothers dick", g.description)
        assertEquals(601.0, g.duration, 0.0)
        assertEquals(585L, g.views)
        assertEquals("lafayette18", g.user)
        assertEquals("tnaflix", g.site)
        assertEquals(1080, g.h)
        assertEquals(1920, g.w)
        assertTrue(g.hasAudio)
        val path = "/amateur-porn/SIS.PORN.-Girl-with-shaved-pussy-takes-stepbrothers-dick/video8461856"
        assertEquals(Sources.lazy("tnaflix", path, true), g.hd)
        assertEquals(Sources.lazy("tnaflix", path, false), g.sd)
    }

    @Test fun `ленивая карточка, сущности, часы и K`() {
        val g = FlixParse.list(lazyCard, "ef", "empflix").single()
        assertEquals("ef:12174189", g.id)
        assertEquals("https://img.empflix.com/a16:8q80w356r/182/12/17/12174189/thumbs/12.jpg", g.thumb)
        assertEquals("Father, Tom & Jerry's show", g.description)
        assertEquals(3723.0, g.duration, 0.0)
        assertEquals(18_900L, g.views)
        assertEquals("LoveHomePorn", g.user)
        assertEquals(1280, g.w)
        assertEquals(720, g.h)
        assertEquals(Sources.lazy("empflix", "/amateur-porn/Father%2C-My-Pussy-Is-Aching/video12174189", false), g.sd)
    }

    @Test fun `несколько карточек делятся по границам`() {
        assertEquals(listOf("tf:8461856", "tf:12174189"), FlixParse.list(first + lazyCard, "tf", "tnaflix").map { it.id })
        assertTrue(FlixParse.list("<html>пусто</html>", "tf", "tnaflix").isEmpty())
    }

    @Test fun `следующая страница`() {
        assertTrue(FlixParse.hasNext("""<li class="page-item pagination-next"><a class="page-link" href="/amateur-porn/featured/2">"""))
        assertFalse(FlixParse.hasNext("""<li class="page-item active"><span>3</span></li>"""))
    }

    private val video = """
<video crossorigin playsinline controls preload="none" data-vid="8461856" data-duration="601" id="video-player">
  <source src="https://sl112.tnaflix.com/84/61/8461856/a-1080p.mp4?rs=522k&rb=4650k&secure=bX1XOUP5h2==,1795089264" type="video/mp4" size="1080">
  <source src="https://sl112.tnaflix.com/84/61/8461856/a-720p.mp4?rs=269k&amp;rb=2397k&amp;secure=Ir3tpi==,1795089264" type="video/mp4" size="720">
  <source src="https://sl112.tnaflix.com/84/61/8461856/a-360p.mp4?rs=118k&rb=1046k&secure=VU41ZW==,1795089264" type="video/mp4" size="360">
  <source src="https://sl112.tnaflix.com/84/61/8461856/a-144p.mp4?rs=118k&rb=1046k&secure=xjhgWS==,1795089264" type="video/mp4" size="144">
</video>
"""

    @Test fun `страница ролика даёт MP4 по качествам`() {
        val f = FlixParse.files(video)
        assertEquals(listOf(144, 360, 720, 1080), f.map { it.first })
        assertEquals("https://sl112.tnaflix.com/84/61/8461856/a-1080p.mp4?rs=522k&rb=4650k&secure=bX1XOUP5h2==,1795089264", f.last().second)
        assertEquals("https://sl112.tnaflix.com/84/61/8461856/a-720p.mp4?rs=269k&rb=2397k&secure=Ir3tpi==,1795089264", f[2].second)
        assertEquals(360, FlixParse.light(f)?.first)
    }

    @Test fun `лёгкое качество и страница без видео`() {
        assertEquals(480, FlixParse.light(listOf(240 to "a", 480 to "b", 720 to "c"))?.first)
        assertEquals(720, FlixParse.light(listOf(720 to "c", 1080 to "d"))?.first)
        assertNull(FlixParse.light(emptyList()))
        assertTrue(FlixParse.files("<html>нет такого ролика</html>").isEmpty())
    }

    @Test fun `длительность`() {
        assertEquals(601.0, FlixParse.clock("10:01"), 0.0)
        assertEquals(3723.0, FlixParse.clock("1:02:03"), 0.0)
        assertEquals(45.0, FlixParse.clock("45"), 0.0)
        assertEquals(0.0, FlixParse.clock(""), 0.0)
    }

    @Test fun `адреса страниц`() {
        assertEquals("https://www.tnaflix.com/amateur-porn/featured", TnaFlix.url("", false, 1))
        assertEquals("https://www.tnaflix.com/amateur-porn/featured/3", TnaFlix.url("", false, 3))
        assertEquals("https://www.tnaflix.com/amateur-porn/most-recent/2", TnaFlix.url("", true, 2))
        assertEquals("https://www.empflix.com/amateur-porn/new", EmpFlix.url("", true, 1))
        assertEquals("https://www.empflix.com/search?what=big+tits", EmpFlix.url("big tits", false, 1))
        assertEquals("https://www.tnaflix.com/search?what=big+tits&page=2", TnaFlix.url("big tits", true, 2))
    }

    @Test fun `подписи сайтов`() {
        assertEquals("tnaflix", TnaFlix.id); assertEquals("TF", TnaFlix.badge); assertEquals("TNAFlix", TnaFlix.title)
        assertEquals("empflix", EmpFlix.id); assertEquals("EF", EmpFlix.badge); assertEquals("EMPFlix", EmpFlix.title)
        assertTrue(TnaFlix.default && EmpFlix.default)
    }
}
