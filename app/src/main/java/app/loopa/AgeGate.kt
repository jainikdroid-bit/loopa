package app.loopa

import android.webkit.CookieManager
import android.webkit.WebView

/**
 * Возрастные окна сайтов («I am 18», «Enter», «Мне есть 18») в окнах входа и невидимом браузере: проходятся сами.
 * Владелец приложения — взрослый и согласие дал при установке (приложение 18+), окно лишь повторяет вопрос.
 *  - куки «возраст подтверждён» — заранее, где они известны ([COOKIES]): окно не появляется вовсе;
 *  - остальное — скрипт [JS] после загрузки страницы ~10 с ищет кнопку возрастного окна и нажимает её. Узко и строго:
 *     · только на хостах наших сайтов ([hostAllowed]: источники из [Sources.ALL] и их поддомены, reddit, redgifs, erome,
 *       eporner) — на google, apple, twitter, accounts.* и прочих страницах входа скрипт не запускается вовсе;
 *     · кнопка лежит внутри контейнера, похожего на возрастное окно (класс/id: age, age-gate, age-verif, disclaimer,
 *       adult — [GATE]), и в тексте этого контейнера явно есть «18» или «adult» ([BOX_TEXT]);
 *     · надпись кнопки — про возраст («I am 18», «18+», «мне есть 18», [STRONG]) либо короткая «Enter/Yes/I agree/Accept»
 *       ([WEAK]) — но только внутри такого окна; «Войти», «Continue», «OK», «Да» не нажимаются никогда;
 *     · кнопки форм с полем пароля, входа через соцсети и регистрации пропускаются ([SKIP]).
 *  Регулярные выражения — строки-константы: их же проверяют юнит-тесты через java.util.regex (JS и Java здесь совместимы:
 *  без lookbehind и \p{..}, границы слов — только у латиницы и цифр).
 */
object AgeGate {
    /** Домен → куки «возраст подтверждён». */
    private val COOKIES = mapOf(
        "https://www.pornhub.com" to listOf("age_verified=1", "accessAgeDisclaimerPH=1"),
        "https://www.youporn.com" to listOf("age_verified=1"),
        "https://www.redtube.com" to listOf("age_verified=1"),
        "https://www.tube8.com" to listOf("age_verified=1"),
        "https://www.reddit.com" to listOf("over18=1"),
        "https://old.reddit.com" to listOf("over18=1"),
    )

    /** Домены, где скрипт вообще запускается, помимо сайтов из [Sources.ALL]. */
    private val EXTRA_DOMAINS = listOf("reddit.com", "redgifs.com", "erome.com", "eporner.com")

    private var flushed = false

    /** Поставить куки (вызывать перед открытием страниц в WebView); на диск — один раз за запуск. */
    @Synchronized fun cookies() {
        val cm = runCatching { CookieManager.getInstance() }.getOrNull() ?: return
        for ((url, list) in COOKIES) list.forEach { cm.setCookie(url, "$it; path=/; max-age=31536000") }
        if (!flushed) { flushed = true; cm.flush() }
    }

    /** Хост из списка [domains] или его поддомен (www., m., api.). */
    fun hostAllowed(host: String?, domains: Collection<String>): Boolean {
        val h = host?.lowercase()?.removePrefix("www.").orEmpty()
        return h.isNotEmpty() && domains.any { d -> h == d || h.endsWith(".$d") }
    }

    /** Пройти возрастное окно на загруженной странице (onPageFinished) — только на наших сайтах. */
    fun pass(v: WebView) {
        val host = runCatching { android.net.Uri.parse(v.url).host }.getOrNull() ?: return
        if (WebAccess.isSite(host) || hostAllowed(host, EXTRA_DOMAINS)) v.evaluateJavascript(JS, null)
    }

    // ---- регулярные выражения (без флагов: в JS и Java добавляется «без учёта регистра») ----

    /** Надпись кнопки сама про возраст. */
    const val STRONG = """\b18\b|18\s*\+|over\s*18|j'ai\s*18|tengo\s*18|мне\s*(есть|уже)|старше\s*18|ich\s*bin\s*18"""
    /** Короткая «подтверждающая» надпись — годится только внутри возрастного окна. */
    const val WEAK = """^(enter|yes|i\s*agree|accept)\b"""
    /** Класс или id контейнера похож на возрастное окно. */
    const val GATE = """\bage\b|age[-_]?gate|age[-_]?verif|disclaimer|\badult\b"""
    /** Текст окна подтверждает, что оно про возраст/взрослый контент. */
    const val BOX_TEXT = """\b18\b|\badult\b|взросл"""
    /** Кнопки, которые не нажимаем: вход через соцсети, регистрация, ввод почты и пароля. */
    const val SKIP = """google|apple|facebook|twitter|reddit|sign|log\s*in|e-?mail|password|регистр|войти|пароль"""

    /** Строка в литерал JS (в двойных кавычках). */
    private fun lit(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    val JS: String = """(function(){
if(window.__ageGate)return;window.__ageGate=1;
var strong=new RegExp(${lit(STRONG)},'i'),weak=new RegExp(${lit(WEAK)},'i'),gate=new RegExp(${lit(GATE)},'i'),
boxText=new RegExp(${lit(BOX_TEXT)},'i'),skip=new RegExp(${lit(SKIP)},'i');
function box(e){for(var p=e.parentElement;p&&p!==document.body;p=p.parentElement){var c=(p.className&&p.className.baseVal!==undefined?p.className.baseVal:p.className)||'';if(gate.test(c+' '+(p.id||'')))return p}return null}
function tryClick(){var els=document.querySelectorAll('button,a,input[type=button],input[type=submit],[role=button]');
for(var i=0;i<els.length;i++){var e=els[i];if(!e.offsetParent&&e.getClientRects().length===0)continue;
var t=(e.innerText||e.value||'').trim();if(!t||t.length>40||skip.test(t))continue;
var f=e.closest&&e.closest('form');if(f&&f.querySelector('input[type=password]'))continue;
if(!strong.test(t)&&!weak.test(t))continue;
var b=box(e);if(!b||!boxText.test((b.innerText||'').slice(0,3000)))continue;
e.click();return true}return false}
var n=0;var iv=setInterval(function(){if(tryClick()||++n>20)clearInterval(iv)},500);})();"""
}
