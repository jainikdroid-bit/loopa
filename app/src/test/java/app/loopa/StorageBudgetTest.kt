package app.loopa

import app.loopa.StorageBudget.Item
import app.loopa.StorageBudget.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Единый предел кэша и сохранённых роликов: кого удалять первым. */
class StorageBudgetTest {
    private fun c(id: String, size: Long, at: Long) = Item(id, Kind.CACHE, size, at)
    private fun s(id: String, size: Long, at: Long) = Item(id, Kind.SAVED, size, at)
    private fun ids(l: List<Item>) = l.map { it.id }

    @Test fun nothingToDeleteWithinLimit() {
        assertTrue(StorageBudget.victims(listOf(c("a", 40, 1), s("b", 60, 2)), 100).isEmpty())
    }

    @Test fun oldestGoesFirstAcrossCacheAndFiles() {
        val items = listOf(c("c1", 30, 50), s("f1", 40, 10), c("c2", 20, 30), s("f2", 50, 40), c("c3", 10, 60))
        // всего 150, предел 100: уйдёт самый давний файл f1 (10) и ещё c2 (30) — 150-40=110 > 100, 110-20=90
        assertEquals(listOf("f1", "c2"), ids(StorageBudget.victims(items, 100)))
    }

    @Test fun stopsAsSoonAsFits() {
        val items = listOf(s("old", 70, 1), c("mid", 20, 2), c("new", 20, 3))
        assertEquals(listOf("old"), ids(StorageBudget.victims(items, 50)))
    }

    @Test fun keptItemIsCountedButNeverDeleted() {
        val items = listOf(s("playing", 60, 1), c("a", 30, 2), c("b", 30, 3))
        // предел 70: ролик на экране самый давний, но его не трогаем — уходят кэш-куски
        assertEquals(listOf("a", "b"), ids(StorageBudget.victims(items, 70, setOf("playing"))))
    }

    @Test fun returnsAllDeletableWhenStillOver() {
        val items = listOf(s("k", 90, 1), c("a", 5, 2))
        assertEquals(listOf("a"), ids(StorageBudget.victims(items, 10, setOf("k"))))
    }

    @Test fun equalTimeDropsCacheBeforeFile() {
        val items = listOf(s("f", 50, 5), c("c", 50, 5))
        assertEquals(listOf("c"), ids(StorageBudget.victims(items, 60)))
    }

    @Test fun emptyItemsAreSkipped() {
        assertEquals(listOf("big"), ids(StorageBudget.victims(listOf(c("zero", 0, 1), s("big", 80, 2)), 50)))
    }
}
