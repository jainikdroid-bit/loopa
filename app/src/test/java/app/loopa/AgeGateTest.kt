package app.loopa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.regex.Pattern

/** Узкие регулярные выражения возрастных окон ([AgeGate]): JS и Java-регулярки здесь совместимы, проверяем Java. */
class AgeGateTest {
    private fun re(s: String) = Pattern.compile(s, Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE)
    private val strong = re(AgeGate.STRONG)
    private val weak = re(AgeGate.WEAK)
    private val gate = re(AgeGate.GATE)
    private val boxText = re(AgeGate.BOX_TEXT)
    private val skip = re(AgeGate.SKIP)
    private fun Pattern.has(s: String) = matcher(s).find()

    @Test fun strongButtonsAboutAge() {
        listOf("I am 18 or older", "I'm over 18", "Enter - I am 18+", "18+", "Мне есть 18 лет", "Мне уже 18", "Ich bin 18", "J'ai 18 ans", "Tengo 18 años")
            .forEach { assertTrue(it, strong.has(it)) }
        listOf("Log in", "Continue", "Page 180", "Top 118 videos", "OK", "Войти", "Да").forEach { assertFalse(it, strong.has(it)) }
    }

    @Test fun weakButtonsOnlyShortConfirmations() {
        listOf("Enter", "ENTER site", "Yes", "yes, I am", "I agree", "I Agree to terms", "Accept").forEach { assertTrue(it, weak.has(it)) }
        // «войти», continue, ok, да — не нажимаем никогда
        listOf("Войти", "Continue", "OK", "Okay", "Да", "Continue with Google", "Yesterday", "Entertainment", "Продолжить").forEach { assertFalse(it, weak.has(it)) }
    }

    @Test fun gateContainerNeedsAgeWords() {
        listOf("age-gate", "ageGate modal", "age_verification", "AgeVerify", "age", "modal age", "disclaimer", "ageDisclaimer", "adult warning", "site-adult")
            .forEach { assertTrue(it, gate.has(it)) }
        // обычные контейнеры, диалоги и слова, где «age» внутри другого слова
        listOf("dialog", "modal", "message", "page-wrapper", "package", "imagebox", "login-form", "cookie-consent", "warning", "verify-email", "gateway-ads")
            .forEach { assertFalse(it, gate.has(it)) }
    }

    @Test fun containerTextMustMentionEighteenOrAdult() {
        assertTrue(boxText.has("You must be 18 or older to enter"))
        assertTrue(boxText.has("This site contains adult content"))
        assertTrue(boxText.has("Сайт для взрослых"))
        assertFalse(boxText.has("We use cookies to improve the page"))
        assertFalse(boxText.has("Sign in to continue, 2018 edition"))
    }

    @Test fun skipsLoginAndSocialButtons() {
        listOf("Continue with Google", "Sign in with Apple", "Log in", "Sign up", "Войти через Facebook", "Регистрация", "Enter your email", "Enter password")
            .forEach { assertTrue(it, skip.has(it)) }
        listOf("I am 18 or older", "Enter", "Yes, I'm over 18", "Мне есть 18").forEach { assertFalse(it, skip.has(it)) }
    }

    @Test fun jsEmbedsAllPatternsAndGuards() {
        val js = AgeGate.JS
        assertTrue(js.contains("password"))
        assertTrue(js.contains("age[-_]?gate"))
        assertFalse(js.contains("dialog"))   // role=dialog больше не признак возрастного окна
        assertFalse(js.contains("\${"))      // подстановки раскрыты
    }

    @Test fun scriptRunsOnlyOnOurHosts() {
        val ours = listOf("pornhub.com", "reddit.com", "redgifs.com")
        assertTrue(AgeGate.hostAllowed("www.pornhub.com", ours))
        assertTrue(AgeGate.hostAllowed("old.reddit.com", ours))
        assertTrue(AgeGate.hostAllowed("API.redgifs.com", ours))
        listOf("accounts.google.com", "appleid.apple.com", "twitter.com", "x.com", "notpornhub.com", "pornhub.com.evil.org", "", null)
            .forEach { assertFalse(it.toString(), AgeGate.hostAllowed(it, ours)) }
        assertEquals(false, AgeGate.hostAllowed("example.com", emptyList()))
    }
}
