package app.loopa

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** Состояние источника: OK, нужно действие пользователя (пройти капчу, возрастное окно), временная ошибка. */
enum class SourceState { OK, NEEDS_ACTION, ERROR }

/** Почему источник не отдаёт ролики. */
enum class BlockReason { CAPTCHA, AGE_GATE, DENIED }

/** Состояние источника на момент [at] (мс); [reason] — только у NEEDS_ACTION. */
data class SourceStatus(val state: SourceState, val reason: BlockReason? = null, val at: Long = 0L)

/** Что случилось с запросом к источнику. */
sealed class HealthEvent {
    object Success : HealthEvent()
    class Blocked(val reason: BlockReason) : HealthEvent()
    object Temporary : HealthEvent()
}

/**
 * Здоровье источников: сайты, которые не дают роликов из-за капчи, возрастной заглушки или блокировки, не выпадают молча —
 * состояние показывает значок «!» на ленте, экран [SourceCheckActivity] и пометка в настройках.
 *
 * Как распознаётся блокировка ([track]):
 *  - не 2xx: 403 — NEEDS_ACTION (капча, если в теле страница проверки Cloudflare, иначе «доступ закрыт»); 503 — только если
 *    в теле страница проверки (обычный 503 — перегрузка сайта, временная ошибка); 429 (слишком частые запросы) — временная
 *    ошибка, а не повод просить пользователя;
 *  - 200, но разбор дал 0 роликов, а в HTML (его видит [Net.text]) страница проверки Cloudflare или капча — CAPTCHA;
 *    возрастная заглушка — AGE_GATE (только для ленты без запроса: у поиска пустая выдача — обычное дело);
 *  - обрыв, таймаут, 5xx — ERROR: временное, пользователю не показывается и уже стоящее NEEDS_ACTION не снимает;
 *  - непустая страница (или найденный адрес видео) — OK.
 */
object SourceHealth {
    private const val PREFIX = "src_health_"
    private val states = ConcurrentHashMap<String, SourceStatus>()
    private var app: Context? = null
    private val ui by lazy { Handler(Looper.getMainLooper()) }
    /** Слушатели смены состояния — вызываются на главном потоке. */
    val listeners = CopyOnWriteArrayList<() -> Unit>()

    // ---- чистые части (юнит-тесты) ----

    /** Только признаки страницы проверки Cloudflare; голое слово «captcha» не годится — оно бывает в обычных страницах (форма входа, подвал). */
    private val CAPTCHA = Regex("cf-chl|just a moment|cf-turnstile|attention required! \\| cloudflare", RegexOption.IGNORE_CASE)
    private val AGE = Regex(
        "age[-_ ]?(gate|verif\\w*|disclaimer|check|warning|confirm\\w*)|(are you|you must be|must be)\\s+(at least\\s+|over\\s+)?18|" +
            "i\\s*(am|'m)\\s+(over\\s+|at least\\s+)?18|18\\s*\\+?\\s*(years|or older)|over\\s+18|adult\\s+(content|warning)",
        RegexOption.IGNORE_CASE)

    /** Страница проверки Cloudflare или капча. */
    fun looksCaptcha(html: String?) = !html.isNullOrEmpty() && CAPTCHA.containsMatchIn(html)
    /** Возрастная заглушка. */
    fun looksAgeGate(html: String?) = !html.isNullOrEmpty() && AGE.containsMatchIn(html)
    /** Страница не та, что ждали: капча/проверка или возрастная заглушка. */
    fun looksBlocked(html: String?) = looksCaptcha(html) || looksAgeGate(html)

    /** Причина пустой страницы 200: [plain] — запрос без поиска (популярное), только тогда заглушка возраста считается. */
    fun emptyReason(html: String?, plain: Boolean): BlockReason? = when {
        looksCaptcha(html) -> BlockReason.CAPTCHA
        plain && looksAgeGate(html) -> BlockReason.AGE_GATE
        else -> null
    }

    /** Событие по исключению запроса; null — не про доступ и не про сеть (ошибка разбора и т. п.), состояние не трогаем. */
    fun eventOf(e: Throwable): HealthEvent? = when {
        e is HttpError -> when {
            e.code == 403 -> HealthEvent.Blocked(if (looksCaptcha(e.body)) BlockReason.CAPTCHA else BlockReason.DENIED)
            e.code == 429 -> HealthEvent.Temporary   // слишком частые запросы — пройдёт само, пользователю нечего делать
            e.code == 503 && looksCaptcha(e.body) -> HealthEvent.Blocked(BlockReason.CAPTCHA)
            e.code >= 500 -> HealthEvent.Temporary
            else -> null
        }
        e is IOException -> HealthEvent.Temporary
        else -> null
    }

    /** Переход состояния: успех — OK; блокировка — NEEDS_ACTION; временная ошибка не снимает NEEDS_ACTION. */
    fun next(cur: SourceStatus?, ev: HealthEvent, now: Long): SourceStatus = when (ev) {
        is HealthEvent.Success -> SourceStatus(SourceState.OK, null, now)
        is HealthEvent.Blocked -> SourceStatus(SourceState.NEEDS_ACTION, ev.reason, now)
        is HealthEvent.Temporary ->
            if (cur?.state == SourceState.NEEDS_ACTION) cur else SourceStatus(SourceState.ERROR, null, now)
    }

    internal fun encode(s: SourceStatus) = "${s.state.name},${s.reason?.name.orEmpty()},${s.at}"
    internal fun decode(v: String): SourceStatus? {
        val p = v.split(',')
        if (p.size != 3) return null
        val st = SourceState.values().firstOrNull { it.name == p[0] } ?: return null
        return SourceStatus(st, BlockReason.values().firstOrNull { it.name == p[1] }, p[2].toLongOrNull() ?: 0L)
    }

    // ---- учёт ----

    fun init(ctx: Context) {
        if (app != null) return
        app = ctx.applicationContext
        val p = ctx.applicationContext.getSharedPreferences("loopa", Context.MODE_PRIVATE)
        for ((k, v) in p.all) if (k.startsWith(PREFIX) && v is String) decode(v)?.let { states[k.removePrefix(PREFIX)] = it }
    }

    fun status(id: String): SourceStatus = states[id] ?: SourceStatus(SourceState.OK)

    /** Включённые пользователем источники, которым нужно действие. */
    fun needsAction(): List<Source> = Sources.ALL.filter { status(it.id).state == SourceState.NEEDS_ACTION && ForYou.uses(it) }

    fun record(id: String, ev: HealthEvent) {
        val cur = states[id]
        val n = next(cur, ev, System.currentTimeMillis())
        states[id] = n
        // сохраняем и оповещаем, только если что-то изменилось (успехи идут постоянно)
        if (cur?.state == n.state && cur.reason == n.reason) return
        app?.getSharedPreferences("loopa", Context.MODE_PRIVATE)?.edit()?.putString(PREFIX + id, encode(n))?.apply()
        ui.post { listeners.toList().forEach { it() } }
    }

    /** Тело последнего ответа, прочитанного на этом потоке внутри [track]. */
    private val body = ThreadLocal<Array<String?>?>()
    fun noteBody(text: String) { body.get()?.set(0, text.take(60_000)) }

    private fun <T> guarded(id: String, isEmpty: (T) -> Boolean, plain: Boolean, block: () -> T): T {
        val prev = body.get(); val holder = arrayOfNulls<String>(1); body.set(holder)
        try {
            val r = block()
            if (isEmpty(r)) emptyReason(holder[0], plain)?.let { record(id, HealthEvent.Blocked(it)) }
            else record(id, HealthEvent.Success)
            return r
        } catch (e: Exception) {
            eventOf(e)?.let { record(id, it) }
            throw e
        } finally { body.set(prev) }
    }

    /** Страница источника: пустая — проверить, не капча ли; непустая — OK. [plain] — запрос без поискового текста. */
    fun track(s: Source, plain: Boolean, block: () -> Page): Page = guarded(s.id, { it.gifs.isEmpty() }, plain, block)

    /** Любой другой запрос к источнику (адрес видео): результат есть — OK. */
    fun <T> trackValue(id: String, block: () -> T?): T? = guarded(id, { it == null }, false, block)
}

/** Страница источника с учётом состояния ([SourceHealth.track]). */
fun Source.tracked(q: String, fresh: Boolean, p: Int): Page = SourceHealth.track(this, q.isBlank()) { page(q, fresh, p) }
