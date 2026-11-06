package app.loopa

import android.content.Context
import android.content.SharedPreferences
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import kotlin.math.ln

/**
 * Лента «Для вас»: RedGIFs + Reddit по профилю [Interests].
 *
 * Кандидаты:
 *  - RedGIFs: рекомендации самого RedGIFs (если вошли) и подборка по любимому тегу (случайный из верхних, по весу);
 *  - Reddit: «горячее» смеси из ~6 сабреддитов — сабреддиты из профиля, найденные по верхним тегам и словам
 *    (поиск сабреддитов Reddit, 18+ вперёд, кэш на сутки), подписки; плюс один случайный для разведки. Свои рекомендации
 *    Reddit 18+ не показывает, поэтому подбираем сами. Лента смеси кончилась — набираем новую смесь.
 * Ранжирование внутри каждого источника: сглаженный вес по профилю + немного популярности + шум (чтобы не застревать
 * в одном и том же). Просмотренные раньше и повторы (тот же ролик RedGIFs из поста Reddit) выкидываем.
 * Смешивание: доля Reddit [share] равномерно по ленте.
 */
object ForYou {
    private val pool = Executors.newFixedThreadPool(3)
    private const val WAIT_RD = 6_000L
    private var prefs: SharedPreferences? = null
    private var rgPage = 0
    private val tagPage = HashMap<String, Int>()
    private var subs: List<String> = emptyList()
    private var after: String? = null
    private var redditDry = 0
    /** Чередование источников Reddit: смесь сабреддитов / поиск постов по всему Reddit по интересу. */
    private var turn = 0
    private val searchAfter = HashMap<String, String?>()
    private var rr = 0
    private val subCursor = HashMap<String, String?>()
    /** Адреса уже показанных в этой ленте — один ролик и с RedGIFs, и из Reddit не должен прийти дважды. */
    private val shown = HashSet<String>()

    val SHARES = listOf(0.25 to "Мало (1 из 4)", 0.34 to "Треть", 0.5 to "Поровну", 0.67 to "Много (2 из 3)")

    fun init(ctx: Context) { if (prefs == null) prefs = ctx.applicationContext.getSharedPreferences("loopa", Context.MODE_PRIVATE) }
    var mixReddit: Boolean
        get() = prefs?.getBoolean("fy_reddit", true) ?: true
        set(v) { prefs?.edit()?.putBoolean("fy_reddit", v)?.apply() }
    var share: Double
        get() = prefs?.getFloat("fy_share", 0.5f)?.toDouble() ?: 0.5
        set(v) { prefs?.edit()?.putFloat("fy_share", v.toFloat())?.apply() }
    val reddit get() = mixReddit && Reddit.hadUser
    /** Почему Reddit не подмешался (для строки статуса); null — всё в порядке. */
    @Volatile var redditNote: String? = null; private set

    @Synchronized fun reset() { pendingRd = null; redditNote = null; turn = 0; rr = 0; subCursor.clear(); searchAfter.clear(); rgPage = 0; tagPage.clear(); subs = emptyList(); after = null; redditDry = 0; shown.clear() }

    /** Следующая порция (в фоне). Пусто — источники кончились. Ошибка — только если не ответил ни один. */
    /** Порция Reddit, не успевшая к прошлой выдаче, — войдёт в следующую. */
    @Volatile private var pendingRd: java.util.concurrent.Future<List<Gif>>? = null

    fun page(): List<Gif> {
        val rgF = pool.submit<List<Gif>> { rg() }
        // Reddit (страница сайта) бывает медленным — RedGIFs его не ждёт: не успел за WAIT_RD после RedGIFs — в следующую порцию
        val rdF = pendingRd ?: if (reddit && redditDry < 3) pool.submit<List<Gif>> { redditBatch() } else null
        val a = runCatching { rgF.get() }
        val b = rdF?.let { f ->
            try { runCatching { f.get(WAIT_RD, java.util.concurrent.TimeUnit.MILLISECONDS) }.also { pendingRd = null } }
            catch (e: Exception) { null }
        }?.let { r -> if (r.exceptionOrNull() is java.util.concurrent.TimeoutException) { pendingRd = rdF; null } else r }
        b?.exceptionOrNull()?.let { e -> val c = unwrap(e); redditNote = "Reddit: " + (c.message ?: c.javaClass.simpleName); android.util.Log.w("Loopa", "Для вас: Reddit не ответил", c) }
            ?: run { if (b != null) redditNote = if (b.getOrNull().isNullOrEmpty()) redditNote ?: "Reddit: пусто" else null }
        if (a.isFailure && (b == null || b.isFailure)) throw unwrap(a.exceptionOrNull()!!)
        return synchronized(this) { mix(rank(a.getOrDefault(emptyList())), rank(b?.getOrNull().orEmpty()), if (b == null) 0.0 else share) }
    }

    private fun unwrap(e: Throwable) = (e as? ExecutionException)?.cause ?: e

    private fun rg(): List<Gif> {
        val out = ArrayList<Gif>()
        if (Api.loggedIn) out += Api.page("", "trending", synchronized(this) { ++rgPage }, mode = "foryou").gifs
        val tag = Interests.pickTag()
        if (tag != null) {
            val p = synchronized(this) { (tagPage[tag] ?: 0) + 1 }.also { synchronized(this) { tagPage[tag] = it } }
            out += runCatching { Api.page(tag, "trending", p, byTag = true).gifs }.getOrElse { if (out.isEmpty()) throw it else emptyList() }
        } else if (!Api.loggedIn) out += Api.page("", "trending", synchronized(this) { ++rgPage }).gifs
        return out
    }

    /** Каждый второй раз — поиск по всему Reddit по любимому тегу/слову (новые сабреддиты сами попадают в профиль). */
    private fun redditBatch(): List<Gif> {
        if (turn++ % 2 == 1) {
            val term = Interests.pickTag() ?: Interests.top("kw", 5).randomOrNull()?.first
            if (term != null && !(searchAfter.containsKey(term) && searchAfter[term] == null)) {
                // поиск постов API может резать (403) — тогда просто смесь сабреддитов
                runCatching { Reddit.searchPosts(term, searchAfter[term]) }.onSuccess { (g, next) ->
                    synchronized(this) { searchAfter[term] = next }
                    android.util.Log.i("Loopa", "Для вас: поиск по Reddit «$term» — ${g.size}")
                    if (g.isNotEmpty()) return g
                }.onFailure { synchronized(this) { searchAfter[term] = null } }
            }
        }
        return mixBatchMany()
    }

    private fun mixBatch(): List<Gif> {
        if (subs.isEmpty()) subs = chooseSubs()
        if (subs.isEmpty()) { redditDry = 3; redditNote = "Reddit: не нашлось сабреддитов по интересам"; android.util.Log.w("Loopa", "Для вас: нет сабреддитов"); return emptyList() }
        // по одному сабреддиту за раз, по кругу (страница сайта смеси через «+» не открывает); у каждого — свой курсор
        val sub = synchronized(this) { subs[rr++ % subs.size] }
        if (subCursor.containsKey(sub) && subCursor[sub] == null) {             // этот кончился
            synchronized(this) { subs = subs - sub; if (subs.isEmpty()) subCursor.clear() }
            return if (subs.isEmpty()) emptyList() else mixBatch()
        }
        android.util.Log.i("Loopa", "Для вас: Reddit r/$sub (смесь ${subs.joinToString("+")})")
        val (gifs, next) = Reddit.page(sub, "hot", subCursor[sub])
        synchronized(this) {
            subCursor[sub] = next
            redditDry = if (gifs.isEmpty()) redditDry + 1 else 0
            // почти одни картинки (меньше 3 видео со страницы) — этот сабреддит больше не берём, место — следующему
            if (gifs.size < 3) { lowYield += sub.lowercase(); subs = subs - sub }
        }
        android.util.Log.i("Loopa", "Для вас: r/$sub дал ${gifs.size} видео")
        return gifs
    }

    /** Сабреддиты, где видео почти нет, — на эту сессию. */
    private val lowYield = HashSet<String>()

    /** До 10 видео за заход: несколько сабреддитов подряд (каждый — своя страница). */
    private fun mixBatchMany(): List<Gif> {
        val out = ArrayList<Gif>()
        var tries = 0
        while (out.size < 10 && tries++ < 3) {
            val g = runCatching { mixBatch() }.getOrElse { if (out.isEmpty()) throw it else emptyList() }
            out += g
            if (subs.isEmpty()) break
        }
        return out
    }

    /** Смесь сабреддитов: профиль + найденные по тегам и словам + подписки; верхние 5 по весу и один случайный. */
    private fun chooseSubs(): List<String> {
        val w = HashMap<String, Double>()
        fun add(s: String, x: Double) { val k = s.lowercase(); w[k] = (w[k] ?: 0.0) + x }
        Interests.top("sub", 10).forEach { (s, x) -> add(s, x) }
        var lookups = 0
        (Interests.top("tag", 5) + Interests.top("kw", 3)).forEach { (term, x) ->
            val found = Interests.mapped(term) ?: if (lookups++ < 3) runCatching { Reddit.searchSubs(term) }
                .onFailure { android.util.Log.w("Loopa", "поиск сабреддитов «$term»", it) }.getOrNull()?.also { Interests.map(term, it) } else null
            found?.forEach { add(it, x * 0.6) }
        }
        runCatching { Reddit.subscribedNsfw() }.onFailure { android.util.Log.w("Loopa", "подписки Reddit", it) }.getOrDefault(emptyList()).forEach { add(it, 1.0) }
        // аудио- и текстовые сабреддиты видео не дают; закрытые/карантин (403) — не брать
        // только сабреддиты 18+: обычные (из старых поисков, подписок) видео 18+ не дают
        val ranked = w.entries.sortedByDescending { it.value }.map { it.key }.filter { !Reddit.isBad(it) && it !in lowYield && !it.contains("audio") && !it.contains("writing") }
            .take(12).filter { Reddit.isNsfw(it) }
        val pick = ranked.take(5).toMutableList()
        ranked.drop(5).randomOrNull()?.let { pick += it }   // разведка: вдруг понравится
        return pick
    }

    /** Порядок внутри источника: вкус, популярность, шум; без просмотренного и повторов. */
    private fun rank(l: List<Gif>): List<Gif> = l
        .filter { !Interests.isSeen(it.id) && shown.add(it.hd) }
        .map { it to (Interests.damp(Interests.score(it)) + 0.08 * ln(1.0 + it.likes.coerceAtLeast(0)) + Math.random() * 0.5) }
        .sortedByDescending { it.second }.map { it.first }

    /** Чередование: на каждой позиции берём Reddit, если его пока меньше доли [share]; остатки — в конец. */
    private fun mix(a: List<Gif>, b: List<Gif>, share: Double): List<Gif> {
        val out = ArrayList<Gif>(a.size + b.size)
        var i = 0; var j = 0
        while (i < a.size || j < b.size) {
            val wantB = j < b.size && (i >= a.size || j < (out.size + 1) * share)
            if (wantB) out += b[j++] else out += a[i++]
        }
        return out
    }
}
