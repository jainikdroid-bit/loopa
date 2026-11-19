package app.loopa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Распознавание капчи и возрастной заглушки, переходы состояний источников, склейка кук запроса и браузера. */
class SourceHealthTest {
    private val cloudflare = """<!DOCTYPE html><html lang="en-US"><head><title>Just a moment...</title>
        <script src="/cdn-cgi/challenge-platform/h/b/orchestrate/chl_page/v1"></script></head><body class="no-js"><div id="cf-chl-widget"></div></body></html>"""
    private val attention = """<title>Attention Required! | Cloudflare</title><div class="cf-error-details">Please complete the security check</div>"""
    private val recaptcha = """<div class="g-recaptcha" data-sitekey="x"></div><p>Please solve the CAPTCHA to continue</p>"""
    private val ageGate = """<div id="age-verification-modal" class="modal"><p>You must be 18 or older to enter.</p><button>I am 18</button></div>"""
    private val normal = """<html><body><div class="thumb"><a href="/video/1">Cute video</a></div><div class="thumb"><a href="/video/2">Another</a></div></body></html>"""

    @Test fun cloudflareAndCaptchaPagesLookBlocked() {
        assertTrue(SourceHealth.looksCaptcha(cloudflare))
        assertTrue(SourceHealth.looksCaptcha(attention))
        // голое слово «captcha» (форма входа, подвал, reCAPTCHA на обычной странице) — не страница проверки
        assertFalse(SourceHealth.looksCaptcha(recaptcha))
        assertTrue(SourceHealth.looksCaptcha("<div class=\"cf-turnstile\" data-sitekey=\"x\"></div>"))
        assertTrue(SourceHealth.looksBlocked("<div id='cf-chl-bypass'></div>"))
    }

    @Test fun ageGatePageLooksBlocked() {
        assertTrue(SourceHealth.looksAgeGate(ageGate))
        assertTrue(SourceHealth.looksBlocked("<h2>Adult content warning</h2>"))
        assertFalse(SourceHealth.looksCaptcha(ageGate))
    }

    @Test fun ordinaryPageAndEmptyAreNotBlocked() {
        assertFalse(SourceHealth.looksBlocked(normal))
        assertFalse(SourceHealth.looksBlocked(""))
        assertFalse(SourceHealth.looksBlocked(null))
    }

    @Test fun emptyPageReasonDependsOnQuery() {
        assertEquals(BlockReason.CAPTCHA, SourceHealth.emptyReason(cloudflare, plain = false))
        assertEquals(BlockReason.CAPTCHA, SourceHealth.emptyReason(cloudflare, plain = true))
        assertEquals(BlockReason.AGE_GATE, SourceHealth.emptyReason(ageGate, plain = true))
        // у поиска пустая выдача и слова про возраст в подвале — не повод тревожить
        assertNull(SourceHealth.emptyReason(ageGate, plain = false))
        assertNull(SourceHealth.emptyReason(normal, plain = true))
        assertNull(SourceHealth.emptyReason(null, plain = true))
    }

    private fun http(code: Int, body: String = "") = HttpError(code).apply { this.body = body }

    @Test fun httpErrorsMapToEvents() {
        assertEquals(BlockReason.DENIED, (SourceHealth.eventOf(http(403)) as HealthEvent.Blocked).reason)
        assertEquals(BlockReason.CAPTCHA, (SourceHealth.eventOf(http(403, cloudflare)) as HealthEvent.Blocked).reason)
        // слишком частые запросы — временное: пользователю нечего проходить
        assertTrue(SourceHealth.eventOf(http(429)) is HealthEvent.Temporary)
        assertTrue(SourceHealth.eventOf(http(429, cloudflare)) is HealthEvent.Temporary)
        assertEquals(BlockReason.CAPTCHA, (SourceHealth.eventOf(http(503, cloudflare)) as HealthEvent.Blocked).reason)
        // обычный 503 и 500 — перегрузка сайта, временное
        assertTrue(SourceHealth.eventOf(http(503)) is HealthEvent.Temporary)
        assertTrue(SourceHealth.eventOf(http(500)) is HealthEvent.Temporary)
        // 404 — про этот адрес, а не про доступ к источнику
        assertNull(SourceHealth.eventOf(http(404)))
    }

    @Test fun networkErrorsAreTemporaryAndOthersIgnored() {
        assertTrue(SourceHealth.eventOf(java.net.SocketTimeoutException("t")) is HealthEvent.Temporary)
        assertTrue(SourceHealth.eventOf(java.io.IOException("reset")) is HealthEvent.Temporary)
        assertNull(SourceHealth.eventOf(IllegalStateException("разбор")))
    }

    @Test fun stateTransitions() {
        val ok = SourceStatus(SourceState.OK, null, 1)
        val blocked = SourceHealth.next(ok, HealthEvent.Blocked(BlockReason.CAPTCHA), 2)
        assertEquals(SourceState.NEEDS_ACTION, blocked.state)
        assertEquals(BlockReason.CAPTCHA, blocked.reason)
        // временная ошибка не снимает «нужно действие»
        assertEquals(blocked, SourceHealth.next(blocked, HealthEvent.Temporary, 3))
        // успех — снова OK, причина сброшена
        val back = SourceHealth.next(blocked, HealthEvent.Success, 4)
        assertEquals(SourceStatus(SourceState.OK, null, 4), back)
        // из OK временная ошибка — ERROR; успех снимает и её
        val err = SourceHealth.next(ok, HealthEvent.Temporary, 5)
        assertEquals(SourceState.ERROR, err.state)
        assertEquals(SourceState.OK, SourceHealth.next(err, HealthEvent.Success, 6).state)
        // первое событие без состояния
        assertEquals(SourceState.NEEDS_ACTION, SourceHealth.next(null, HealthEvent.Blocked(BlockReason.AGE_GATE), 7).state)
    }

    @Test fun statusSurvivesEncoding() {
        val s = SourceStatus(SourceState.NEEDS_ACTION, BlockReason.AGE_GATE, 123456789L)
        assertEquals(s, SourceHealth.decode(SourceHealth.encode(s)))
        val ok = SourceStatus(SourceState.OK, null, 5)
        assertEquals(ok, SourceHealth.decode(SourceHealth.encode(ok)))
        assertNull(SourceHealth.decode("мусор"))
    }

    @Test fun mergedCookiesKeepOwnValuesAndAddBrowserOnes() {
        assertEquals("cf_clearance=abc; age_verified=1", WebAccess.mergeCookies("age_verified=1", "cf_clearance=abc"))
        // одноимённая кука запроса главнее куки браузера
        assertEquals("b=2; a=own", WebAccess.mergeCookies("a=own", "a=web; b=2"))
        assertEquals("a=1", WebAccess.mergeCookies("a=1", null))
        assertEquals("a=1; b=2", WebAccess.mergeCookies(null, "a=1; b=2"))
        assertNull(WebAccess.mergeCookies("", null))
    }

    @Test fun domainMatching() {
        assertTrue(WebAccess.inDomain("api.fikfap.com", "fikfap.com"))
        assertTrue(WebAccess.inDomain("fikfap.com", "fikfap.com"))
        assertFalse(WebAccess.inDomain("notfikfap.com", "fikfap.com"))
        assertEquals("erome.com", WebAccess.bare("www.EROME.com"))
    }
}
