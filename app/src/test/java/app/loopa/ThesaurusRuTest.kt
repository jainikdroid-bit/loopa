package app.loopa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThesaurusRuTest {
    @Test fun `русский запрос переводится`() {
        assertEquals("big tits blonde", Thesaurus.translate("Большие сиськи блондинка"))
        assertEquals("mature russian", Thesaurus.translate("зрелая русская"))
    }

    @Test fun `латинский и неузнанный запрос - null`() {
        assertNull(Thesaurus.translate("big tits"))
        assertNull(Thesaurus.translate("абвгд"))
    }

    @Test fun `служебные слова отбрасываются, латиница остаётся`() {
        assertEquals("girl shower", Thesaurus.translate("девушка в душе"))
        assertEquals("big ass pov", Thesaurus.translate("большая задница pov"))
    }

    @Test fun `короткие слова только целиком`() {
        assertNull(Thesaurus.translate("попытка"))
        assertEquals("ass", Thesaurus.translate("попа"))
    }

    @Test fun `оценка русского запроса по английским тегам`() {
        val g = Gif("a", "", "a", "", 0, 0, 10.0, "", listOf("Big Tits", "Blonde"), false, 0, 0, "", "", site = "")
        assertTrue(Relevance.level(Relevance.score(g, "большие сиськи", emptyList(), emptyList())) >= 4)
        val other = Gif("b", "", "b", "", 0, 0, 10.0, "", listOf("cat"), false, 0, 0, "", "", site = "")
        assertEquals(0.0, Relevance.score(other, "большие сиськи", emptyList(), emptyList()), 0.0)
    }
}
