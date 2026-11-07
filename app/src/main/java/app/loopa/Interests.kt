package app.loopa

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.ln
import kotlin.math.pow

/**
 * Профиль интересов — только на телефоне (files/interests.json). Веса по четырём осям: теги роликов, слова из названий и
 * описаний, сабреддиты, авторы. Учится на действиях:
 *  - лайк +5 (снят — −5), скачивание +4, досмотр (≥80 %) +1,5 и пересмотр по кругу ещё +1,5, мельком +0,3;
 *  - пропуск (< 2,5 с у ролика длиннее 6 с) −0,4;
 *  - поиск, открытый тег или сабреддит +3; сам запрос целиком тоже запоминается (ось «q») — «Для вас» ищет по нему.
 * Сочетания тегов (ось «pair», «a,b» по алфавиту, без слишком общих тегов) учатся на тех же сигналах с весом 0,5 — так
 * видно, что нравится не «Riding» и не «Little People» по отдельности, а именно вместе.
 * Слова весят 0,5 от тега (в названиях много шума). Веса затухают на 3 % в сутки — вкусы меняются, старое уходит само.
 * Просмотренные (≥ 2 с) запоминаются, «Для вас» их не повторяет.
 *
 * Точность весов (07.11):
 *  - повторение усиливает: «cnt» — сколько положительных сигналов подкрепили термин; действующий вес
 *    умножается на 1 + 0,35·ln(1 + cnt) — то, что нравится раз за разом, весит больше разового;
 *  - редкость: «df» — в скольких просмотренных роликах был тег/слово; тег, что есть почти в каждом («amateur»),
 *    весит меньше, редкий и конкретный — больше (как IDF, 0,3…2,5);
 *  - склейка написаний: «Big Tits», «big  tits», «big-tits» — один термин ([key]);
 *  - лишнее уходит: термины слабее [PRUNE] после затухания удаляются, повторные пропуски копят минус.
 */
object Interests {
    private const val DECAY_PER_DAY = 0.97
    private const val MAX_TERMS = 400
    private const val MAX_SEEN = 5000
    private const val DAY = 86_400_000L
    private val AXES = listOf("tag", "kw", "sub", "user", "pair", "q")
    /** Счётчики: подкрепления термина (по осям) и встречаемость тегов/слов в просмотренном. */
    private val COUNTS = listOf("cnt", "df")
    private const val PRUNE = 0.15
    /** Один термин из разных написаний: регистр, пробелы, дефисы, подчёркивания. */
    fun key(t: String) = t.lowercase().replace(Regex("[\\s_-]+"), " ").trim()
    /** Сколько тегов ролика берём в сочетания (пары растут квадратично). */
    private const val PAIR_TAGS = 8

    private lateinit var file: File
    private var data = JSONObject()
    private val io by lazy { Handler(HandlerThread("interests").apply { start() }.looper) }
    private val seen = LinkedHashSet<String>()

    @Synchronized fun init(ctx: Context) {
        if (::file.isInitialized) return
        file = File(ctx.filesDir, "interests.json")
        data = runCatching { JSONObject(file.readText()) }.getOrDefault(JSONObject())
        AXES.forEach { if (!data.has(it)) data.put(it, JSONObject()) }
        COUNTS.forEach { c -> if (!data.has(c)) data.put(c, JSONObject()) }
        merge()
        if (!data.has("map")) data.put("map", JSONObject())
        data.optJSONArray("seen")?.let { a -> for (i in 0 until a.length()) seen += a.getString(i) }
        if (!data.has("at")) data.put("at", System.currentTimeMillis())
        decay()
    }

    // ---- признаки ролика ----

    private val STOP = setOf(
        "the", "and", "for", "with", "you", "your", "this", "that", "her", "his", "she", "him", "who", "what", "when", "from",
        "are", "was", "but", "not", "all", "just", "have", "has", "had", "how", "out", "get", "got", "can", "will", "one",
        "into", "about", "more", "like", "love", "my", "me", "its", "it's", "i'm", "im", "so", "too", "very", "some", "any",
        "new", "now", "time", "day", "first", "last", "ever", "best", "good", "make", "made", "want", "need", "let", "see",
        "look", "watch", "video", "gif", "gifs", "clip", "full", "link", "onlyfans", "only", "fans", "free", "click", "check",
        "comments", "comment", "sub", "reddit", "post", "posted", "oc", "pic", "pics", "here", "there", "then", "than",
        "это", "как", "что", "для", "она", "они", "его", "так", "все", "мой", "моя", "меня", "тебя", "вот", "уже", "когда")

    /** Слова названия/описания: латиница и кириллица, 3–20 букв, без служебных и хэштег-решёток. */
    fun words(s: String): Set<String> = s.lowercase().split(Regex("[^\\p{L}']+"))
        .map { it.trim('\'') }.filter { it.length in 3..20 && it !in STOP }.toSet()

    private fun features(g: Gif): Map<String, Collection<String>> = mapOf(
        "tag" to g.tags.filter { !it.startsWith("r/") }.map(::key).distinct(),
        "kw" to words(g.description),
        "sub" to listOfNotNull(g.sub.lowercase().takeIf { it.isNotEmpty() }),
        "user" to listOfNotNull(g.user.lowercase().takeIf { it.isNotEmpty() && it != "[deleted]" }),
        "pair" to pairs(g.tags),
    )

    /** Сочетания тегов ролика: пары из конкретных (не общих) тегов, «a,b» по алфавиту. */
    fun pairs(tags: List<String>): List<String> {
        val t = tags.filter { !it.startsWith("r/") && !Api.isGeneric(it) }.map(::key).filter { it.isNotEmpty() && ',' !in it }
            .distinct().take(PAIR_TAGS).sorted()
        return t.indices.flatMap { i -> (i + 1 until t.size).map { j -> t[i] + "," + t[j] } }
    }

    // ---- сигналы ----

    private fun bump(g: Gif, w: Double) = synchronized(this) {
        features(g).forEach { (axis, terms) -> terms.forEach { add(axis, it, w * when (axis) { "kw", "pair" -> 0.5; else -> 1.0 }) } }
        save()
    }

    @Synchronized private fun add(axis: String, term: String, w: Double) {
        if (term.isBlank()) return
        val o = data.getJSONObject(axis)
        o.put(term, o.optDouble(term, 0.0) + w)
        // подкрепление: положительный сигнал (лайк, досмотр, поиск) — счётчик повторов термина
        if (w > 0.5) count("cnt", "$axis|$term", 1.0)
    }

    @Synchronized private fun count(c: String, k: String, d: Double) { val o = data.getJSONObject(c); o.put(k, o.optDouble(k, 0.0) + d) }

    /** Действующий вес: сырой × повторы × редкость (для тегов и слов). */
    @Synchronized private fun eff(axis: String, term: String, raw: Double): Double {
        if (raw == 0.0) return 0.0
        val cnt = data.getJSONObject("cnt").optDouble("$axis|$term", 0.0)
        val rep = 1 + 0.35 * ln(1 + cnt)
        // редкость поднимает только то, что повторялось (≥ 3 раз): 08.11 тег, встреченный один раз («gearshift»),
        // с редкостью ×2.5 вышел в главные интересы — RedGIFs по нему находит 0–1 ролик, «Для вас» пустела
        val idf = if (axis == "tag" || axis == "kw") {
            val df = data.getJSONObject("df").optDouble("$axis|$term", 0.0)
            val r = (1 + ln((seen.size + 10.0) / (df + 10.0))).coerceIn(0.5, 1.8)
            if (cnt >= 3) r else r.coerceAtMost(1.0)
        } else 1.0
        return if (raw > 0) raw * rep * idf else raw * rep   // минус редкостью не ослабляем: пропуски — это пропуски
    }

    /** Старые ключи (до склейки написаний) — слить в один термин. */
    @Synchronized private fun merge() {
        listOf("tag", "pair").forEach { axis ->
            val o = data.getJSONObject(axis); val m = JSONObject()
            o.keys().forEach { k -> val n = if (axis == "pair") k.split(",").joinToString(",") { key(it) } else key(k); m.put(n, m.optDouble(n, 0.0) + o.getDouble(k)) }
            data.put(axis, m)
        }
    }

    fun like(g: Gif, on: Boolean) = bump(g, if (on) 5.0 else -5.0)
    fun download(g: Gif) = bump(g, 4.0)

    /** Сколько смотрели: fraction — доля длительности (больше 1 — пошёл по кругу), sec — секунды на экране. */
    fun watched(g: Gif, fraction: Double, sec: Double) {
        if (sec >= 2) synchronized(this) {
            // встречаемость тегов и слов среди просмотренного — для веса по редкости (один раз на ролик)
            if (seen.add(g.id)) features(g).forEach { (axis, terms) -> if (axis == "tag" || axis == "kw") terms.forEach { count("df", "$axis|$it", 1.0) } }
            trimSeen()
        }
        when {
            sec < 2.5 && g.duration > 6 -> bump(g, -0.4)
            fraction >= 1.8 -> bump(g, 3.0)
            fraction >= 0.8 -> bump(g, 1.5)
            sec >= 3 -> bump(g, 0.3)
            else -> if (sec >= 2) save()
        }
    }

    /** Поиск: слова запроса — как теги и как слова. */
    fun search(q: String) = synchronized(this) {
        words(q).forEach { add("tag", key(it), 3.0); add("kw", it, 1.5) }
        q.trim().lowercase().takeIf { it.length >= 3 }?.let { add("q", it, 3.0) }
        save()
    }
    fun openTag(t: String) = synchronized(this) { add("tag", key(t), 3.0); save() }
    fun openSub(s: String) = synchronized(this) { s.split("+").filter { it.isNotBlank() && !it.startsWith("u/") }.forEach { add("sub", it.lowercase(), 3.0) }; save() }

    fun reset() = synchronized(this) {
        AXES.forEach { data.put(it, JSONObject()) }; COUNTS.forEach { data.put(it, JSONObject()) }; data.put("map", JSONObject()); seen.clear(); save()
    }

    // ---- использование ----

    fun isSeen(id: String) = synchronized(this) { id in seen }

    /** Насколько ролик в твоём вкусе (0 — ничего не известно); отрицательные — «пропускал такое». */
    fun score(g: Gif): Double = synchronized(this) {
        var s = 0.0
        features(g).forEach { (axis, terms) ->
            val o = data.getJSONObject(axis)
            val k = when (axis) { "kw" -> 0.6; "sub" -> 1.2; "user" -> 0.8; "pair" -> 0.7; else -> 1.0 }
            terms.forEach { s += k * eff(axis, it, o.optDouble(it, 0.0)) }
        }
        s
    }

    /** Верх оси по весу. */
    fun top(axis: String, n: Int): List<Pair<String, Double>> = synchronized(this) {
        val o = data.getJSONObject(axis)
        o.keys().asSequence().map { it to eff(axis, it, o.getDouble(it)) }.filter { it.second > 0.3 }.sortedByDescending { it.second }.take(n).toList()
    }

    val empty get() = AXES.all { top(it, 1).isEmpty() }

    /** Тег для подборки RedGIFs: случайный из верхних с вероятностью по весу (не только самый любимый); skip — исчерпанные. */
    fun pickTag(skip: Set<String> = emptySet()): String? = weighted(top("tag", 30).filter { it.first !in skip }.take(20))

    /**
     * Сочетание тегов «a,b» для подборки: из выученных сочетаний, а пока их мало — пары из верхних конкретных тегов
     * (вес — среднее геометрическое весов тегов, чуть ниже выученных).
     */
    fun pickPair(skip: Set<String> = emptySet()): String? {
        val learned = top("pair", 25).filter { it.first !in skip }
        val tags = top("tag", 16).filter { !Api.isGeneric(it.first) && ',' !in it.first }.take(8)
        val made = tags.indices.flatMap { i -> (i + 1 until tags.size).map { j ->
            listOf(tags[i].first, tags[j].first).sorted().joinToString(",") to 0.7 * kotlin.math.sqrt(tags[i].second * tags[j].second)
        } }.filter { it.first !in skip }
        return weighted((learned + made).groupBy { it.first }.map { (k, v) -> k to v.maxOf { it.second } }.sortedByDescending { it.second }.take(18))
    }

    /** Прошлый поиск (запрос целиком) — по весу; skip — исчерпанные. */
    fun pickQuery(skip: Set<String> = emptySet()): String? = weighted(top("q", 15).filter { it.first !in skip })

    /** Любимый автор (у кого нет роликов на RedGIFs — например, пользователь Reddit, — того ForYou пропустит). */
    fun pickUser(skip: Set<String> = emptySet()): String? = weighted(top("user", 15).filter { it.first !in skip && it.second >= 3.0 })

    private fun weighted(l: List<Pair<String, Double>>): String? {
        if (l.isEmpty()) return null
        var r = Math.random() * l.sumOf { it.second }
        for ((t, w) in l) { r -= w; if (r <= 0) return t }
        return l.last().first
    }

    /** Сабреддиты, найденные для слова/тега (кэш на сутки); null — ещё не искали или устарело. */
    fun mapped(term: String): List<String>? = synchronized(this) {
        val m = data.getJSONObject("map").optJSONObject(term) ?: return null
        if (System.currentTimeMillis() - m.optLong("at") > DAY) return null
        val a = m.optJSONArray("subs") ?: return emptyList()
        List(a.length()) { a.getString(it) }
    }

    fun map(term: String, subs: List<String>) = synchronized(this) {
        data.getJSONObject("map").put(term, JSONObject().put("at", System.currentTimeMillis()).put("subs", JSONArray(subs))); save()
    }

    // ---- хранение ----

    /** Затухание: раз в 6 часов умножить веса на 0,97^дней; почти нулевые — убрать, длинные хвосты — обрезать. */
    @Synchronized private fun decay() {
        val now = System.currentTimeMillis()
        val days = (now - data.optLong("at", now)) / DAY.toDouble()
        if (days < 0.25) return
        val f = DECAY_PER_DAY.pow(days)
        AXES.forEach { axis ->
            val o = data.getJSONObject(axis)
            val kept = o.keys().asSequence().map { it to o.getDouble(it) * f }.filter { kotlin.math.abs(it.second) >= PRUNE }
                .sortedByDescending { kotlin.math.abs(it.second) }.take(MAX_TERMS).toList()
            data.put(axis, JSONObject().apply { kept.forEach { (k, v) -> put(k, v) } })
        }
        // подкрепления затухают так же; встречаемость — нет (статистика просмотренного), но с потолком
        data.getJSONObject("cnt").let { o -> data.put("cnt", JSONObject().apply { o.keys().forEach { k -> val v = o.getDouble(k) * f; if (v >= 0.3) put(k, v) } }) }
        data.getJSONObject("df").let { o -> if (o.length() > 4000) data.put("df", JSONObject().apply {
            o.keys().asSequence().map { it to o.getDouble(it) }.sortedByDescending { it.second }.take(3000).forEach { (k, v) -> put(k, v) } }) }
        data.put("at", now)
    }

    @Synchronized private fun trimSeen() { while (seen.size > MAX_SEEN) seen.remove(seen.first()) }

    /** Запись в фоне и не чаще раза в 2 с. */
    private fun save() {
        io.removeCallbacksAndMessages(null)
        io.postDelayed({
            val text = synchronized(this) { decay(); data.put("seen", JSONArray(seen.toList())); data.toString() }
            runCatching { File(file.path + ".tmp").apply { writeText(text) }.renameTo(file) }
        }, 2000)
    }

    /** Сводка для окна «Мои интересы». */
    fun summary(): String = buildString {
        fun line(title: String, axis: String, pre: String) {
            val t = top(axis, 12)
            if (t.isNotEmpty()) append(title).append(": ").append(t.joinToString(", ") { pre + it.first.replace(",", " + $pre") }).append("\n\n")
        }
        line(tr("Теги"), "tag", "#"); line(tr("Сочетания"), "pair", "#"); line(tr("Запросы"), "q", ""); line(tr("Слова"), "kw", ""); line(tr("Сабреддиты"), "sub", "r/"); line(tr("Авторы"), "user", "")
        if (isEmpty()) append(tr("Пока пусто: лайкайте, досматривайте и ищите — «Для вас» подстроится."))
    }.trim()

    /** Сглаживание веса (логарифм): десяток лайков одного тега не должен перевешивать всё остальное. */
    fun damp(x: Double) = if (x >= 0) ln(1 + x) else -ln(1 - x)
}
