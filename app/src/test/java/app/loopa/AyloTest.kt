package app.loopa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pornhub и RedTube ([Aylo]): разбор выдачи API и mediaDefinitions/get_media на сокращённых кусках живых ответов. */
class AyloTest {
    private val phList = """{"videos":[{"duration":"28:50","views":226378407,"video_id":"ph5a7f1ba584481","rating":77.9508,"ratings":996373,
        "title":"Playing a New Game","url":"https:\/\/www.pornhub.com\/view_video.php?viewkey=ph5a7f1ba584481",
        "default_thumb":"https:\/\/ei.phncdn.com\/videos\/201802\/10\/154131272\/original\/(m=eafTGgaaaa)(mh=vR7TD4VPkdYtkmxB)12.jpg",
        "publish_date":"2018-02-22 20:41:12",
        "tags":[{"tag_name":"step fuck"},{"tag_name":"point of view"},{"tag_name":"Blonde"},{"tag_name":"18-25"},{"tag_name":"blonde"},{"tag_name":"a very very long tag name here"}],
        "pornstars":[{"pornstar_name":"Anastasia Knight"}],"categories":[{"category":"amateur"}]},
        {"duration":"1:02:03","views":5,"title":"без id"}]}"""

    private val rtList = """{"videos":[{"video":{"duration":"4:17","views":4,"video_id":"309236451","rating":"100","ratings":2,
        "title":"Big dick session","url":"https:\/\/www.redtube.com\/309236451",
        "default_thumb":"https:\/\/ei-ph.rdtcdn.com\/videos\/\/original\/(m=e0YH8f)(mh=yXFAYustF-LlVwlH)0.jpg",
        "publish_date":"2026-11-14 06:48:38","tags":[{"tag_name":"masturbate"},{"tag_name":"Verified Amateurs"}]}}],"count":300000}"""

    @Test fun `выдача Pornhub - поля ролика`() {
        val g = Aylo.parseList(phList, "pornhub", "ph").single()
        assertEquals("ph:ph5a7f1ba584481", g.id)
        assertEquals("pornhub", g.site)
        assertEquals(1730.0, g.duration, 0.0)
        assertEquals(226378407L, g.views)
        assertEquals("Playing a New Game", g.description)
        assertEquals("Anastasia Knight", g.user)
        assertEquals(listOf("step fuck", "blonde"), g.tags)
        assertEquals(776_680.0, g.likes.toDouble(), 2.0)
        assertTrue(g.created > 0)
        assertTrue(g.thumb.startsWith("https://ei.phncdn.com/"))
        assertEquals("loopa://pornhub/ph5a7f1ba584481/hd", g.hd)
        assertEquals("loopa://pornhub/ph5a7f1ba584481/sd", g.sd)
    }

    @Test fun `выдача RedTube - ролик в обёртке video`() {
        val g = Aylo.parseList(rtList, "redtube", "rt").single()
        assertEquals("rt:309236451", g.id)
        assertEquals(257.0, g.duration, 0.0)
        assertEquals(2L, g.likes)
        assertEquals(listOf("masturbate", "verified amateurs"), g.tags)
        assertEquals("loopa://redtube/309236451/hd", g.hd)
    }

    @Test fun `ответ-ошибка и пустая выдача - пустой список`() {
        assertTrue(Aylo.parseList("""{"code":"2001","message":"No Videos found!"}""", "pornhub", "ph").isEmpty())
        assertTrue(Aylo.parseList("""{"videos":[],"count":0}""", "redtube", "rt").isEmpty())
    }

    @Test fun `длительность мм-сс и ч-мм-сс`() {
        assertEquals(257.0, Aylo.seconds("4:17"), 0.0)
        assertEquals(3723.0, Aylo.seconds("1:02:03"), 0.0)
        assertEquals(0.0, Aylo.seconds(""), 0.0)
        assertEquals(0.0, Aylo.seconds("abc"), 0.0)
    }

    /** Сокращённая страница Pornhub: hls-записи и ссылка get_media; скобки и кавычки внутри строк не сбивают разбор. */
    private val phPage = """var flashvars_208684411 = {"video_title":"a [b] \"c\"","mediaDefinitions":[{"group":1,"height":1080,"width":1920,"defaultQuality":false,"format":"hls","videoUrl":"https:\/\/hv-h.phncdn.com\/hls\/videos\/master.m3u8?h=G69W%2B&e=1&f=1","quality":"1080"},{"group":1,"height":1080,"width":1920,"defaultQuality":false,"format":"mp4","videoUrl":"https:\/\/www.pornhub.com\/video\/get_media?s=eyJrIjoi&v=ph5c6c5cfc500ec&e=0&t=p","quality":"1080"}],"isVertical":"false"}; var other = [1,2];"""

    @Test fun `mediaDefinitions страницы Pornhub`() {
        val defs = Aylo.parseMediaDefinitions(phPage)
        assertEquals(listOf("hls", "mp4"), defs.map { it.format })
        val mp4 = defs.single { it.format == "mp4" }
        assertEquals("https://www.pornhub.com/video/get_media?s=eyJrIjoi&v=ph5c6c5cfc500ec&e=0&t=p", mp4.url)
        assertEquals(1080, mp4.height)
    }

    @Test fun `страница без mediaDefinitions - пусто`() {
        assertTrue(Aylo.parseMediaDefinitions("<html>Service Unavailable</html>").isEmpty())
        assertTrue(Aylo.parseMediaDefinitions("\"mediaDefinitions\":[{\"a\":1").isEmpty())
    }

    @Test fun `mediaDefinitions RedTube - адреса от корня сайта`() {
        val page = """{"defaultQuality":[240,480,720,1080],"mediaDefinitions":[{"format":"hls","videoUrl":"\/media\/hls?s=eyJ2"},{"format":"mp4","videoUrl":"\/media\/mp4?s=eyJ2"}],"x":1}"""
        val mp4 = Aylo.parseMediaDefinitions(page).single { it.format == "mp4" }
        assertEquals("/media/mp4?s=eyJ2", mp4.url)
        assertEquals(0, mp4.height)
    }

    private val getMedia = """[{"group":1,"height":240,"width":426,"defaultQuality":true,"format":"mp4","videoUrl":"https:\/\/em.phncdn.com\/videos\/201902\/19\/208684411\/201216_0233_240P_1000K_208684411.mp4?validfrom=1&validto=2&rate=500k&hash=hYb%3D","quality":"240","segmentFormats":{"audio":"aac","video":"mp4"}},{"group":1,"height":480,"width":854,"defaultQuality":false,"format":"mp4","videoUrl":"https:\/\/em.phncdn.com\/480P.mp4?hash=ck%3D","quality":"480"},{"group":1,"height":720,"width":1280,"defaultQuality":false,"format":"mp4","videoUrl":"https:\/\/em.phncdn.com\/720P.mp4?hash=ic%3D","quality":"720"},{"group":1,"height":1080,"width":1920,"defaultQuality":false,"format":"mp4","videoUrl":"https:\/\/em.phncdn.com\/1080P.mp4?hash=M6%3D","quality":"1080"}]"""

    @Test fun `get_media - список MP4 по качествам`() {
        val l = Aylo.mediaList(getMedia)
        assertEquals(listOf(240, 480, 720, 1080), l.map { it.height })
        assertTrue(l.all { it.format == "mp4" })
        assertTrue(l[0].url.startsWith("https://em.phncdn.com/videos/201902/19/208684411/201216_0233_240P_1000K_208684411.mp4?validfrom=1&"))
    }

    @Test fun `get_media - пустой и битый ответ`() {
        assertTrue(Aylo.mediaList("[]").isEmpty())
        assertTrue(Aylo.mediaList("<html>").isEmpty())
    }

    @Test fun `выбор качества - hd 720, sd 480`() {
        val l = Aylo.mediaList(getMedia)
        assertEquals(720, Aylo.pick(l, true)!!.height)
        assertEquals(480, Aylo.pick(l, false)!!.height)
    }

    @Test fun `выбор качества без 720 и 480`() {
        fun m(vararg h: Int) = h.map { Aylo.Media(it, "mp4", "https://x/$it.mp4") }
        assertEquals(1080, Aylo.pick(m(240, 1080), true)!!.height)
        assertEquals(240, Aylo.pick(m(240, 1080), false)!!.height)
        assertEquals(240, Aylo.pick(m(240), true)!!.height)
        assertEquals(1080, Aylo.pick(m(1080, 1440), true)!!.height)
        assertEquals(1440, Aylo.pick(m(1440), true)!!.height)
        assertNull(Aylo.pick(emptyList(), true))
    }
}
