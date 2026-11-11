package app.loopa

import app.loopa.ShareLink.Link
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShareLinkTest {
    @Test fun `ссылка RedGIFs на страницу ролика`() {
        assertEquals(Link.RedGifs("BoldTinyHare"), ShareLink.parse("https://www.redgifs.com/watch/BoldTinyHare"))
        assertEquals(Link.RedGifs("boldtinyhare"), ShareLink.parse("https://redgifs.com/ifr/boldtinyhare?x=1"))
    }

    @Test fun `прямая ссылка на файл RedGIFs`() {
        assertEquals(Link.RedGifs("BoldTinyHare"), ShareLink.parse("https://media.redgifs.com/BoldTinyHare-mobile.mp4"))
        assertEquals(Link.RedGifs("BoldTinyHare"), ShareLink.parse("https://media.redgifs.com/BoldTinyHare.mp4"))
    }

    @Test fun `пост Reddit с адресом и короткий redd it`() {
        val u = "https://www.reddit.com/r/gifs/comments/1abc2de/title_here/"
        assertEquals(Link.RedditPost("1abc2de", u), ShareLink.parse(u))
        assertEquals(Link.RedditPost("1abc2de", null), ShareLink.parse("https://redd.it/1abc2de"))
        assertEquals(Link.RedditPost("1abc2de", "https://old.reddit.com/comments/1abc2de"), ShareLink.parse("https://old.reddit.com/comments/1abc2de"))
    }

    @Test fun `видео v redd it и ссылка-поделиться`() {
        assertEquals(Link.VReddit("abc123xyz"), ShareLink.parse("https://v.redd.it/abc123xyz"))
        val s = "https://www.reddit.com/r/gifs/s/AbCdEf1234"
        assertEquals(Link.Short(s), ShareLink.parse(s))
    }

    @Test fun `чужие ссылки и страницы без ролика не берутся`() {
        assertNull(ShareLink.parse("https://example.com/watch/abc"))
        assertNull(ShareLink.parse("https://www.reddit.com/r/gifs/"))
        assertNull(ShareLink.parse("https://www.redgifs.com/users/someone"))
        assertNull(ShareLink.parse("https://i.redd.it/abc123.jpg"))
    }

    @Test fun `в тексте поделиться ссылка находится среди слов`() {
        val t = "Смотри какая штука https://www.redgifs.com/watch/BoldTinyHare, это огонь"
        assertEquals(Link.RedGifs("BoldTinyHare"), ShareLink.find(t))
        assertNull(ShareLink.find("ссылки нет"))
    }
}
