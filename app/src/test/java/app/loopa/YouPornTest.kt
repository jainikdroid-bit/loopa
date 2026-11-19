package app.loopa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** YouPorn и Tube8 ([Aylo.parseHtmlList]): плитки ленты и страница ролика на сокращённых кусках живых ответов (19.11.2026). */
class YouPornTest {
    /** Реклама (div, не article), плитка любителя YouPorn и плитка канала с просмотрами «1.2M». */
    private val list = """<link rel="next" href="https://www.youporn.com/category/amateur/?page=2" />
        <div class="video-box xa5o0svnqlaos"><ins class='adsbytrafficjunky' data-site-id='31'></ins></div>
        <article class="video-box mobile js_video-box js-pop"
             data-video-id="310415681"
             data-uploader-id="388202491"
             data-uploader-type="amateur"
             data-uploader-name="KaYLaLiCiOuSS"
             aria-label="Short Finger Fuck &amp; Work &#039;n&#039; Play"
        >
            <a href="/watch/310415681/" class=" tm_video_link video-box-image"
                data-webm='https://ev-ph.ypncdn.com/videos/360P_360K_69692895_fb.mp4?validfrom=1&amp;hash=2vR7%2BbL'
                data-poster='https://pix-cdn77.ypncdn.com/c6531/videos/202611/18/69692895/original_69692895.mov/plain/rs:fit:416:234/vts:20?hash=gGyq&amp;validto=1795082093'
            >
                <img class="thumb-image js_lazy" data-src="https://pix-cdn77.ypncdn.com/other.jpg" src="data:image/png;base64,iVBOR">
                <div class="video-properties" aria-hidden="true">
                    <div class="video-duration tm_video_duration">
                        <span>
    00:27
</span>
                    </div>
                </div>
            </a>
            <div class="view-rating-container">
                <span class='info-views'><span class="sr-only">Views: </span>135</span>
                <span class='info-views'><span class="sr-only">Rating: </span>80%</span>
            </div>
        </article>
        <article class="video-box mobile js_video-box js-pop" data-video-id="212340131" data-uploader-name="Brazzers" aria-label="Blonde">
            <a href="/watch/212340131/" data-poster='https://pix.ypncdn.com/b.jpg'>
                <div class="video-duration tm_video_duration"><span>1:02:03</span></div>
            </a>
            <span class='info-views'><span class="sr-only">Views: </span>1.2M</span>
            <span class='info-views'><span class="sr-only">Rating: </span>90%</span>
        </article>
        <article class="video-box" data-video-id="5" data-uploader-name="без превью"></article>"""

    @Test fun `плитки YouPorn - поля ролика`() {
        val l = Aylo.parseHtmlList(list, "youporn", "yp")
        assertEquals(2, l.size)
        val g = l[0]
        assertEquals("yp:310415681", g.id)
        assertEquals("youporn", g.site)
        assertEquals("Short Finger Fuck & Work 'n' Play", g.description)
        assertEquals("KaYLaLiCiOuSS", g.user)
        assertEquals(27.0, g.duration, 0.0)
        assertEquals(135L, g.views)
        assertEquals(108L, g.likes)
        assertTrue(g.hasAudio)
        assertTrue(g.tags.isEmpty())
        // атрибут &amp; в адресе превью раскрыт, а превью — именно data-poster (не data-src)
        assertEquals("https://pix-cdn77.ypncdn.com/c6531/videos/202611/18/69692895/original_69692895.mov/plain/rs:fit:416:234/vts:20?hash=gGyq&validto=1795082093", g.thumb)
        assertEquals("loopa://youporn/310415681/hd", g.hd)
        assertEquals("loopa://youporn/310415681/sd", g.sd)
    }

    @Test fun `плитки - часы, миллионы и тот же разбор для Tube8`() {
        val g = Aylo.parseHtmlList(list, "tube8", "t8")[1]
        assertEquals("t8:212340131", g.id)
        assertEquals(3723.0, g.duration, 0.0)
        assertEquals(1_200_000L, g.views)
        assertEquals(1_080_000L, g.likes)
        assertEquals("loopa://tube8/212340131/hd", g.hd)
    }

    @Test fun `пустая и чужая страница - пустой список`() {
        assertTrue(Aylo.parseHtmlList("", "youporn", "yp").isEmpty())
        assertTrue(Aylo.parseHtmlList("<html><title>404 Not Found</title></html>", "tube8", "t8").isEmpty())
    }

    @Test fun `следующая страница по link rel=next`() {
        assertTrue(Aylo.hasNext(list))
        assertFalse(Aylo.hasNext("<article class=\"video-box\" data-video-id=\"1\"></article>"))
    }

    @Test fun `адрес страницы ленты`() {
        assertEquals("https://x/cat/", Aylo.paged("https://x/cat/", 1))
        assertEquals("https://x/cat/?page=3", Aylo.paged("https://x/cat/", 3))
        assertEquals("https://x/s/?q=a&page=2", Aylo.paged("https://x/s/?q=a", 2))
    }

    @Test fun `числа с суффиксами K M`() {
        assertEquals(135L, Aylo.count("135"))
        assertEquals(401_000L, Aylo.count("401K"))
        assertEquals(1_500_000L, Aylo.count("1.5M"))
        assertEquals(1_000_000L, Aylo.count("1M"))
        assertEquals(1234L, Aylo.count("1,234"))
        assertEquals(0L, Aylo.count("n/a"))
    }

    /** Страница /watch/{id}/: mp4-запись ведёт на индекс /media/mp4/?s=…, адрес абсолютный (в отличие от RedTube). */
    private val page = """..."defaultQuality":[480,240,720,1080],"mediaDefinitions":[{"format":"hls","videoUrl":"https:\/\/www.youporn.com\/media\/hls\/?s=eyJ2","remote":true,"segmentFormats":{"video":"fmp4","audio":"aac"}},{"format":"mp4","videoUrl":"https:\/\/www.youporn.com\/media\/mp4\/?s=eyJ2a2V5","remote":true,"segmentFormats":{"video":"mp4","audio":"aac"}}],"video_unavailable_country":"false",..."""

    @Test fun `страница YouPorn - индекс MP4`() {
        val mp4 = Aylo.parseMediaDefinitions(page).single { it.format == "mp4" }
        assertEquals("https://www.youporn.com/media/mp4/?s=eyJ2a2V5", mp4.url)
        assertTrue(mp4.url.contains("/media/mp4"))
    }

    /** Индекс /media/mp4/: качества от 1080 до 240 (у Tube8 1080 нет), ссылки на em-ph.*cdn.com. */
    private val index = """[{"defaultQuality":false,"format":"mp4","quality":"1080","videoUrl":"https:\/\/em-ph.ypncdn.com\/1080P_4000K_69692895.mp4?validfrom=1&validto=2&hash=e4D%2BW"},{"defaultQuality":true,"format":"mp4","quality":"720","videoUrl":"https:\/\/em-ph.ypncdn.com\/720P_4000K_69692895.mp4?hash=hFH%2B"},{"defaultQuality":false,"format":"mp4","quality":"480","videoUrl":"https:\/\/em-ph.ypncdn.com\/480P_2000K_69692895.mp4?hash=wJO"},{"defaultQuality":false,"format":"mp4","quality":"240","videoUrl":"https:\/\/em-ph.ypncdn.com\/240P_1000K_69692895.mp4?hash=abc"}]"""

    @Test fun `индекс MP4 - hd 720 и sd 480`() {
        val l = Aylo.mediaList(index)
        assertEquals(listOf(1080, 720, 480, 240), l.map { it.height })
        assertEquals("https://em-ph.ypncdn.com/720P_4000K_69692895.mp4?hash=hFH%2B", Aylo.pick(l, true)!!.url)
        assertEquals("https://em-ph.ypncdn.com/480P_2000K_69692895.mp4?hash=wJO", Aylo.pick(l, false)!!.url)
    }
}
