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

    @Test fun `режим обновления - три значения, остальное - предлагать`() {
        assertEquals(Updater.MANUAL, Updater.parseMode("manual"))
        assertEquals(Updater.ASK, Updater.parseMode("ask"))
        assertEquals(Updater.AUTO, Updater.parseMode("auto"))
        assertEquals(Updater.ASK, Updater.parseMode(null))
        assertEquals(Updater.ASK, Updater.parseMode("что-то"))
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

    @Test fun invalidVersionsCannotBecomePaths() {
        assertTrue(Updater.validVersion("v1.34"))
        assertTrue(Updater.validVersion("1.34.2"))
        assertFalse(Updater.validVersion("../1.34"))
        assertFalse(Updater.validVersion("1.999999999999999999999"))
        assertFalse(Updater.validVersion("v1.34-test"))
        Updater.parts("99999999999999999999999")
    }

    @Test fun rejectAmbiguousAssetPaths() {
        val prefix = "https://github.com/jainikdroid-bit/loopa/releases/download/"
        assertFalse(Updater.trusted(prefix + "v1.34/%2e%2e.apk"))
        assertFalse(Updater.trusted(prefix + "v1.34/a.apk?other=1"))
        assertFalse(Updater.trusted(prefix + "v1.34/extra/a.apk"))
        assertFalse(Updater.trusted(prefix + "v1.34/a.apk#fragment"))
        assertTrue(Updater.trusted(prefix + "v1.34/SHA256SUMS"))
    }
}
