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
        // «поделиться» с профиля пользователя: /u/имя/s/код и /user/имя/s/код
        val u = "https://www.reddit.com/u/Ok_Association_8703/s/NrVwjF44Jr"
        assertEquals(Link.Short(u), ShareLink.parse(u))
        assertEquals(Link.Short("https://reddit.com/user/x_y/s/AbC123"), ShareLink.parse("https://reddit.com/user/x_y/s/AbC123"))
        assertEquals(Link.Short(u), ShareLink.find("Смотри $u"))
    }

    @Test fun `чужие ссылки и страницы без ролика не берутся`() {
        assertNull(ShareLink.parse("https://example.com/watch/abc"))
        assertNull(ShareLink.parse("https://www.reddit.com/r/gifs/"))
        assertNull(ShareLink.parse("https://i.redd.it/abc123.jpg"))
    }

    @Test fun `страницы RedGIFs - поиск, автор и тег - открываются в ленте`() {
        val s = ShareLink.parse("https://www.redgifs.com/search/gifs?query=Gear+shift+&order=score")
        assertEquals(Link.RedGifsSearch("Gear shift"), s)
        assertEquals("query" to "Gear shift", ShareLink.screen(s!!))
        assertEquals(Link.RedGifsSearch("тест"), ShareLink.parse("https://redgifs.com/search?query=%D1%82%D0%B5%D1%81%D1%82"))
        assertEquals(Link.RedGifsUser("someone"), ShareLink.parse("https://www.redgifs.com/users/someone"))
        assertEquals(Link.RedGifsTag("gear shift"), ShareLink.parse("https://www.redgifs.com/gifs/gear-shift"))
        assertNull(ShareLink.parse("https://www.redgifs.com/search/gifs?order=score"))
        assertNull(ShareLink.screen(Link.RedGifs("x")))
    }

    @Test fun `русские слова сразу после ссылки в ссылку не попадают`() {
        assertEquals(Link.RedGifsSearch("Gear shift"), ShareLink.find("https://www.redgifs.com/search/gifs?query=Gear+shift+&order=scoreтоже"))
    }

    @Test fun `в тексте поделиться ссылка находится среди слов`() {
        val t = "Смотри какая штука https://www.redgifs.com/watch/BoldTinyHare, это огонь"
        assertEquals(Link.RedGifs("BoldTinyHare"), ShareLink.find(t))
        assertNull(ShareLink.find("ссылки нет"))
    }
}
