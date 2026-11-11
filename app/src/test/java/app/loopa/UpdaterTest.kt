package app.loopa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdaterTest {
    @Test fun `версия новее по числам, а не по строке`() {
        assertTrue(Updater.isNewer("1.11.2", "1.12"))
        assertTrue(Updater.isNewer("1.9", "1.10"))
        assertTrue(Updater.isNewer("1.11", "v1.11.1"))
        assertFalse(Updater.isNewer("1.11.2", "1.11.2"))
        assertFalse(Updater.isNewer("1.12", "1.11.9"))
        assertFalse(Updater.isNewer("1.10.2", "v1.10"))
    }

    @Test fun `хеш файла берётся из SHA256SUMS по имени`() {
        val a = "a".repeat(64); val b = "B".repeat(64)
        val sums = "$a  Loopa-1.7.apk\n$b *Loopa-1.12.apk\n"
        assertEquals(a, Updater.sumFor(sums, "Loopa-1.7.apk"))
        assertEquals("b".repeat(64), Updater.sumFor(sums, "Loopa-1.12.apk"))
        assertNull(Updater.sumFor(sums, "Loopa-9.apk"))
        assertNull(Updater.sumFor("мусор без хеша  Loopa-1.7.apk", "Loopa-1.7.apk"))
    }

    @Test fun `качаем только файлы релизов нашего репозитория`() {
        assertTrue(Updater.trusted("https://github.com/jainikdroid-bit/loopa/releases/download/v1.12/Loopa-1.12.apk"))
        assertFalse(Updater.trusted("https://github.com/other/loopa/releases/download/v1/Loopa.apk"))
        assertFalse(Updater.trusted("https://evil.example/jainikdroid-bit/loopa/releases/download/x.apk"))
        assertFalse(Updater.trusted("https://github.com/jainikdroid-bit/loopa/releases/download/../../x.apk"))
    }
}
