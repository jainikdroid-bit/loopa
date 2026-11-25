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
 * Профиль интересов — только на телефоне (files/interests.json). Веса по осям: теги роликов, слова из названий и
 * описаний, сабреддиты, авторы, запросы целиком («q») и сайты роликов («site»: redgifs, reddit, erome…). Учится на действиях:
 *  - лайк +5 (снят — −5), скачивание +4;
 *  - просмотр — по суммарному времени на экране (с повторами и возвратами; «wt»: id → секунды, до 3000 роликов):
 *    при каждом уходе добавляется прирост [Ranker.watchWeight] (0 до 3 с, дальше логарифм 1,2·ln(1+(t−3)/8), потолок 6:
 *    10 с ≈ 0,75, 30 с ≈ 1,8, 2 мин ≈ 3,3), поэтому 20-минутный ролик получает очки за реальное время, а не за досмотр;
 *  - пропуск (первый просмотр < 2,5 с у ролика длиннее 6 с) −0,4;
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
 *
 * Сеть вкуса (14.11, [TagNet]; с 1.15 — из всех интересов): узлы — типизированные id «тип:термин» по всем осям,
 * связи — сочетания тегов (ось «pair») и связи между типами (ось «link», ключ «idA|idB»: тег — автор, сабреддит, сайт;
 * автор — сабреддит; слово названия — тег; запрос целиком — тег понравившегося ролика). Ролик оценивается не только
 * своими весами, но и весами тех, с кем нравится вместе; слова запроса связываются между собой и с тегами ролика,
 * понравившегося в течение 30 минут после поиска. Пропуск того, что сеть считала «во вкусе», штрафуется сильнее (до ×2).
 * Сеть растёт по мере использования: пределы по осям — [MAX_TERMS], старое затухает и вымывается.
 *
 * Связи (1.21): сочетания учатся по [PAIR_TAGS] конкретным тегам ролика, а общие теги ([Api.isGeneric]) связываются
 * с конкретными с весом ×[GENERIC_PAIR_K] (в активации их вес сглажен, сеть они не раздувают); слово названия связывается
 * с тегами (до [LINK_KW_WORDS] слов × до [LINK_KW_TAGS] тегов). Связи учатся на любом положительном сигнале —
 * лайк, скачивание, поиск и время просмотра (прирост [Ranker.watchWeight] идёт через ту же [bump]).
 *
 * Закреплённый вес (1.21, ось «pin», id «тип:термин» → вес): ползунок «Мой вес» в графе задаёт узлу абсолютный свой вес
 * вместо выученного — он не затухает и не зависит от повторов и редкости, а распространение по сети ([TagNet.activate])
 * идёт как обычно. 0 — узел почти не участвует в «Для вас», но остаётся в графе. «Сбросить» снимает закрепление.
 * Прежние множители («man», до 1.21) при первом запуске переводятся в закреплённые веса ([pinsFromBoosts]).
 */
object Interests {
    private const val DECAY_PER_DAY = 0.97
    /** Сколько терминов держим по осям: сеть растёт, пока пользуешься, но не бесконечно. */
    private val MAX_TERMS = mapOf("tag" to 1500, "kw" to 800, "user" to 600, "sub" to 300, "q" to 200, "site" to 20, "pair" to 6000, "link" to 8000)
    private const val MAX_SEEN = 5000
    /** Сколько роликов помним по времени просмотра. */
    private const val MAX_WATCH = 3000
    private const val DAY = 86_400_000L
    private val AXES = listOf("tag", "kw", "sub", "user", "pair", "q", "site", "link")
    /** Оси-узлы сети (pair и link — связи). */
    private val NODE_AXES = listOf("tag", "kw", "sub", "user", "q", "site")
    /** Счётчики: подкрепления термина (по осям) и встречаемость тегов/слов в просмотренном. */
    private val COUNTS = listOf("cnt", "df")
    private const val PRUNE = 0.15
    /** Один термин из разных написаний: регистр, пробелы, дефисы, подчёркивания. */
    fun key(t: String) = t.lowercase().replace(Regex("[\\s_-]+"), " ").trim()
    /** Сколько конкретных тегов ролика берём в сочетания (пары растут квадратично). */
    private const val PAIR_TAGS = 12
    /** Сколько общих тегов связываем с конкретными и с каким весом относительно пары конкретных. */
    private const val PAIR_GENERIC_TAGS = 4
    const val GENERIC_PAIR_K = 0.3

    /** Сколько после поиска лайк/досмотр связывает теги запроса с тегами ролика. */
    private const val QUERY_LINK_MS = 30 * 60_000L
    /** Сила связи «запрос → тег ролика» от силы сигнала. */
    private const val QUERY_LINK = 0.3
    /** Сила связей между типами от силы сигнала; слово названия — тег слабее. */
    private const val LINK = 0.3
    private const val LINK_KW = 0.15
    /** Сколько слов названия и тегов берём в связи слово — тег. */
    private const val LINK_KW_WORDS = 6
    private const val LINK_KW_TAGS = 6
    /** Закреплённый вес не выше этого (защита от мусора в файле). */
    const val PIN_MAX = 1000.0

    private lateinit var file: File
    private var data = JSONObject()
    /** Активация сети вкуса ([TagNet]) — пересчитывается после изменения весов. */
    private var net: Map<String, Double>? = null
    /** Теги последнего поиска и когда искали — для связей «запрос → понравившийся ролик». */
    private var lastQuery: List<String> = emptyList()
    /** Запрос целиком (для узла «q» и связи с тегами понравившегося ролика); null — последним был не поиск. */
    private var lastQueryFull: String? = null
    private var lastQueryAt = 0L
    private val io by lazy { Handler(HandlerThread("interests").apply { start() }.looper) }
    private val seen = LinkedHashSet<String>()
    /** Суммарное время просмотра роликов, с: id → секунды (порядок — по давности, старые вытесняются). Хранится как «wt». */
    private val watchSec = LinkedHashMap<String, Double>()

    @Synchronized fun init(ctx: Context) {
        if (::file.isInitialized) return
        TasteModel.init(ctx)
        file = File(ctx.filesDir, "interests.json")
        data = runCatching { android.util.AtomicFile(file).openRead().bufferedReader().use { JSONObject(it.readText()) } }.getOrDefault(JSONObject())
        net = null
        AXES.forEach { if (!data.has(it)) data.put(it, JSONObject()) }
        COUNTS.forEach { c -> if (!data.has(c)) data.put(c, JSONObject()) }
        merge()
        if (!data.has("map")) data.put("map", JSONObject())
        if (!data.has("pin")) data.put("pin", JSONObject())
        data.optJSONObject("wt")?.let { o -> o.keys().forEach { k -> watchSec[k] = o.optDouble(k, 0.0) } }
        data.optJSONArray("seen")?.let { a -> for (i in 0 until a.length()) seen += a.getString(i) }
        migrateMan()   // после «seen»: действующий вес зависит от числа просмотренных
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

    /** Сайт ролика как узел сети: сторонние — свой id, остальные — «reddit» или «redgifs». */
    internal fun siteOf(g: Gif) = g.site.ifEmpty { if (g.reddit) "reddit" else "redgifs" }

    private fun features(g: Gif): Map<String, Collection<String>> = mapOf(
        "tag" to g.tags.filter { !it.startsWith("r/") }.map(::key).distinct(),
        "kw" to words(g.description),
        "sub" to listOfNotNull(g.sub.lowercase().takeIf { it.isNotEmpty() }),
        "user" to listOfNotNull(g.user.lowercase().takeIf { it.isNotEmpty() && it != "[deleted]" }),
        "site" to listOf(siteOf(g)),
        "pair" to pairs(g.tags),
    )

    /**
     * Сочетания тегов ролика, «a,b» по алфавиту: пары из первых [PAIR_TAGS] конкретных тегов и пары «общий — конкретный»
     * (до [PAIR_GENERIC_TAGS] общих; общий с общим не связываем). [generic] — что считать общим тегом.
     */
    fun pairs(tags: List<String>, generic: (String) -> Boolean = Api::isGeneric): List<String> {
        val all = tags.filter { !it.startsWith("r/") }.map(::key).filter { it.isNotEmpty() && ',' !in it }.distinct()
        val concrete = all.filter { !generic(it) }.take(PAIR_TAGS)
        val common = all.filter { generic(it) }.take(PAIR_GENERIC_TAGS)
        val out = ArrayList<String>()
        for (i in concrete.indices) {
            for (j in i + 1 until concrete.size) TagNet.pairKey(concrete[i], concrete[j])?.let { out += it }
            common.forEach { c -> TagNet.pairKey(concrete[i], c)?.let { out += it } }
        }
        return out
    }

    /** Вес сочетания относительно пары конкретных: с общим тегом — [GENERIC_PAIR_K], иначе 1. */
    fun pairFactor(pair: String, generic: (String) -> Boolean = Api::isGeneric) = if (pair.split(',').any(generic)) GENERIC_PAIR_K else 1.0

    // ---- сигналы ----

    private fun bump(g: Gif, w: Double) = synchronized(this) {
        if (!TrainingLab.learning) return@synchronized
        val f = features(g)
        f.forEach { (axis, terms) -> terms.forEach { add(axis, it, w * when (axis) { "kw" -> 0.5; "pair" -> 0.5 * pairFactor(it); else -> 1.0 }) } }
        if (w > 0) linkTypes(f, w)
        // понравилось вскоре после поиска — связать слова запроса с тегами ролика: сеть учит, какие теги RedGIFs
        // отвечают твоим словам (свой словарь вместо общего)
        if (w > 0 && lastQuery.isNotEmpty() && System.currentTimeMillis() - lastQueryAt < QUERY_LINK_MS) {
            val tags = f["tag"].orEmpty().filter { !Api.isGeneric(it) }.take(PAIR_TAGS)
            lastQuery.forEach { q -> tags.forEach { t -> TagNet.pairKey(q, t)?.let { add("pair", it, w * QUERY_LINK) } } }
            lastQueryFull?.let { q -> tags.forEach { t -> TagNet.linkKey("q:$q", "tag:$t")?.let { add("link", it, w * QUERY_LINK) } } }
        }
        save()
    }

    /** Связи между типами в одном ролике: тег — автор / сабреддит / сайт, автор — сабреддит, слово названия — тег. */
    private fun linkTypes(f: Map<String, Collection<String>>, w: Double) {
        val tags = f["tag"].orEmpty().filter { !Api.isGeneric(it) }.take(PAIR_TAGS)
        val others = listOf("user", "sub", "site").flatMap { ax -> f[ax].orEmpty().map { "$ax:$it" } }
        fun link(a: String, b: String, k: Double) { TagNet.linkKey(a, b)?.let { add("link", it, w * k) } }
        tags.forEach { t -> others.forEach { o -> link("tag:$t", o, LINK) } }
        val user = f["user"].orEmpty().firstOrNull(); val sub = f["sub"].orEmpty().firstOrNull()
        if (user != null && sub != null) link("user:$user", "sub:$sub", LINK)
        f["kw"].orEmpty().take(LINK_KW_WORDS).forEach { k -> tags.take(LINK_KW_TAGS).forEach { t -> link("kw:$k", "tag:$t", LINK_KW) } }
    }

    @Synchronized private fun add(axis: String, term: String, w: Double) {
        if (term.isBlank()) return
        net = null
        val o = data.getJSONObject(axis)
        o.put(term, o.optDouble(term, 0.0) + w)
        // подкрепление: положительный сигнал (лайк, досмотр, поиск) — счётчик повторов термина
        if (w > 0.5 && axis != "link") count("cnt", "$axis|$term", 1.0)
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

    /** Ролики, показанные в сетке «Для вас» и учтённые как «не открыл» (раз на ролик, пока приложение живо). */
    private val impressed = LinkedHashSet<String>()

    /**
     * Ролик долго был на экране сетки «Для вас» целиком и не открыт — слабый отрицательный пример для модели вкуса
     * ([TasteModel.IMPRESSION_WEIGHT]), чтобы она училась не только на плюсах. Не чаще раза на ролик; открытые и
     * просмотренные (есть в watchSec/seen) не считаем.
     */
    fun impression(g: Gif) {
        if (!TrainingLab.learning) return
        val fresh = synchronized(this) {
            if (g.id in watchSec || g.id in seen || !impressed.add(g.id)) false
            else { while (impressed.size > 3000) impressed.remove(impressed.first()); true }
        }
        if (fresh) TasteModel.learnImpression(g)
    }

    fun like(g: Gif, on: Boolean) {
        if (!TrainingLab.learning) return
        if (!on) { TrainingLab.event("like_removed"); return }
        Feed.searchFor(g)?.let { SearchTaste.choose(it, g, 2.0) }
        bump(g, 5.0)
        TasteModel.learn(g, 1f, 3.0, compare = Feed.mode == "foryou", kind = "like")
    }
    fun download(g: Gif) { Feed.searchFor(g)?.let { SearchTaste.choose(it, g, 2.0) }; bump(g, 4.0); TasteModel.learn(g, 1f, 3.0, compare = Feed.mode == "foryou") }
    /** Лайк, поставленный на Reddit раньше (импорт): слабее свежего — вкус мог измениться. */
    fun imported(g: Gif) { bump(g, 2.5); TasteModel.learn(g, 1f, 1.5) }

    /**
     * Сколько смотрели: sec — секунды на экране за этот заход. Они копятся по ролику ([watchSec]); при каждом уходе
     * к интересам добавляется прирост [Ranker.watchWeight] (новое всего) − (старое всего), так что повторы и возвраты
     * к ролику считаются, а длинный ролик получает очки за реальное время, а не за досмотр до конца.
     */
    fun watched(g: Gif, sec: Double, allowSkip: Boolean = true) {
        if (!TrainingLab.learning || !sec.isFinite() || sec <= 0) return
        val before: Double
        val after: Double
        synchronized(this) {
            if (sec >= 2) {
                // встречаемость тегов и слов среди просмотренного — для веса по редкости (один раз на ролик)
                if (seen.add(g.id)) features(g).forEach { (axis, terms) -> if (axis == "tag" || axis == "kw") terms.forEach { count("df", "$axis|$it", 1.0) } }
                trimSeen()
            }
            before = watchSec.remove(g.id) ?: 0.0
            after = before + sec
            watchSec[g.id] = after   // в конец очереди: свежее вытесняется последним
            while (watchSec.size > MAX_WATCH) watchSec.remove(watchSec.keys.first())
        }
        val gain = Ranker.watchWeight(after) - Ranker.watchWeight(before)
        when {
            // пропуск того, что сеть считала «во вкусе», — её ошибка: штраф до двух раз сильнее (только первый просмотр)
            allowSkip && before == 0.0 && sec < 2.5 && g.duration > 6 -> {
                val surprise = TagNet.surprise(score(g) * Ranker.norm(g.tags.size))
                Feed.searchFor(g)?.let { SearchTaste.pass(it, g, quick = true) }
                if (Feed.mode == "foryou") TasteModel.noteSkip(g)
                bump(g, -0.4 * surprise); TasteModel.learn(g, 0f, surprise, kind = "quick_exit")
            }
            gain > 0.0 -> {
                if (before < 10 && after >= 10) Feed.searchFor(g)?.let { SearchTaste.choose(it, g, 1.0) }
                bump(g, gain); TasteModel.learn(g, 1f, gain, compare = before < 10 && after >= 10 && Feed.mode == "foryou", kind = "watch") }
            else -> if (sec >= 2) save()
        }
    }

    /** Поиск: слова запроса — как теги и как слова. */
    fun search(q: String) = synchronized(this) {
        if (!TrainingLab.learning) return@synchronized
        val ws = words(q).map(::key).filter { it.isNotEmpty() }.distinct()
        ws.forEach { add("tag", it, 3.0); add("kw", it, 1.5) }
        // слова одного запроса связаны между собой (как теги одного ролика)
        ws.take(PAIR_TAGS).let { t -> t.indices.forEach { i -> (i + 1 until t.size).forEach { j -> TagNet.pairKey(t[i], t[j])?.let { add("pair", it, 1.5) } } } }
        lastQuery = ws.filter { !Api.isGeneric(it) }.take(4); lastQueryAt = System.currentTimeMillis()
        lastQueryFull = q.trim().lowercase().takeIf { it.length >= 3 }
        lastQueryFull?.let { add("q", it, 3.0) }
        ws.take(4).forEach { TasteModel.learnFeature("tag:$it") }
        save()
    }
    fun openTag(t: String) = synchronized(this) { if (!TrainingLab.learning) return@synchronized; add("tag", key(t), 3.0); TasteModel.learnFeature("tag:" + key(t)); lastQuery = listOf(key(t)).filter { it.isNotEmpty() }; lastQueryFull = null; lastQueryAt = System.currentTimeMillis(); save() }
    fun openSub(s: String) = synchronized(this) { if (!TrainingLab.learning) return@synchronized; s.split("+").filter { it.isNotBlank() && !it.startsWith("u/") }.forEach { add("sub", it.lowercase(), 3.0); TasteModel.learnFeature("sub:" + it.lowercase()) }; save() }

    /**
     * Открыта лента автора (в сетке, в плеере, из графа): +3 автору, связь «автор — сайт» и сигнал модели вкуса по
     * признаку «user:имя». site — сайт ролика («redgifs», «reddit», «erome»…), пусто — не знаем.
     */
    fun openAuthor(user: String, site: String) = synchronized(this) {
        if (!TrainingLab.learning) return@synchronized
        val u = user.trim().lowercase().removePrefix("u/")
        if (u.isEmpty() || u == "[deleted]") return@synchronized
        add("user", u, 3.0)
        if (site.isNotBlank()) TagNet.linkKey("user:$u", "site:${site.trim().lowercase()}")?.let { add("link", it, 3.0 * LINK) }
        TasteModel.learnFeature("user:$u")
        save()
    }

    fun reset() = synchronized(this) {
        SearchTaste.reset()
        ForYou.clearTasteHistory()
        AXES.forEach { data.put(it, JSONObject()) }; COUNTS.forEach { data.put(it, JSONObject()) }; data.put("map", JSONObject()); data.put("pin", JSONObject()); seen.clear(); watchSec.clear(); impressed.clear(); net = null; lastQuery = emptyList(); lastQueryFull = null; TasteModel.reset(); save()
    }

    // ---- использование ----

    fun isSeen(id: String) = synchronized(this) { id in seen }

    /** Насколько ролик в твоём вкусе (0 — ничего не известно); отрицательные — «пропускал такое». */
    fun score(g: Gif): Double = synchronized(this) {
        if (!TrainingLab.learning) return@synchronized 0.0
        val a = TasteModel.alpha()
        val own = netScore(g)
        // сеть вкуса (веса осей и соседей) плюс α·добавка факторизационной машины (сочетания признаков); без примеров — одна сеть
        // (без умножения сети на 1−α: добавка центрирована по недавним роликам, сеть не занижаем)
        if (a == 0.0) own else own + a * TasteModel.bonus(g)
    }

    /** Оценка одной только сети вкуса ([TagNet] по осям), без модели [TasteModel]. */
    private fun netScore(g: Gif): Double {
        var s = 0.0
        val act = activation()
        features(g).forEach { (axis, terms) ->
            val k = when (axis) { "kw" -> 0.6; "sub" -> 1.2; "user" -> 0.8; "pair" -> 0.7; "site" -> 0.5; else -> 1.0 }
            terms.forEach { t ->
                s += when (axis) {
                    // сочетание — не узел сети, берём свой вес
                    "pair" -> k * eff(axis, t, data.getJSONObject(axis).optDouble(t, 0.0))
                    // сайт — один на сотни роликов, его вес копится без предела: сглаживаем, иначе он перевесит остальное
                    "site" -> k * damp(act["site:$t"] ?: 0.0)
                    // остальное — по активации сети: свой вес + вес частых соседей
                    else -> k * (act["$axis:$t"] ?: 0.0)
                }
            }
        }
        return s
    }

    /** Связи сети: сочетания тегов («a,b» → «tag:a|tag:b») и связи между типами; вес — действующий. */
    @Synchronized private fun netLinks(): Map<String, Double> {
        val out = HashMap<String, Double>()
        val pairs = data.getJSONObject("pair")
        pairs.keys().forEach { k ->
            val i = k.indexOf(',')
            if (i > 0) TagNet.linkKey("tag:" + k.substring(0, i), "tag:" + k.substring(i + 1))?.let { out[it] = (out[it] ?: 0.0) + eff("pair", k, pairs.getDouble(k)) }
        }
        val links = data.getJSONObject("link")
        links.keys().forEach { k -> out[k] = (out[k] ?: 0.0) + eff("link", k, links.getDouble(k)) }
        return out
    }

    /** Узлы сети по всем осям: «тип:термин» → действующий вес. */
    @Synchronized private fun netNodes(): Map<String, Double> {
        val out = HashMap<String, Double>()
        NODE_AXES.forEach { axis ->
            val o = data.getJSONObject(axis)
            // сайт есть почти у каждого ролика — его вес растёт без меры и через связи раздул бы все теги поровну: сглаживаем
            o.keys().forEach { k -> val v = eff(axis, k, o.getDouble(k)); out["$axis:$k"] = if (axis == "site") damp(v) else v }
        }
        return out
    }

    /** Активация сети вкуса ([TagNet]): узлы — действующие веса всех осей (закреплённые — как заданы), связи — сочетания и связи типов; кэш до следующего изменения. */
    @Synchronized private fun activation(): Map<String, Double> = net ?: TagNet.activate(ownNodes(), netLinks()).also { net = it }

    /** Свои веса узлов: выученные ([netNodes]), у закреплённых — заданный вес. */
    @Synchronized private fun ownNodes(): Map<String, Double> = applyPins(netNodes(), pins())

    /** JSON графа интересов для экрана «3D-граф» ([InterestGraph]): те же веса, что у [activation]. */
    @Synchronized fun graphJson(): String = InterestGraph.build(ownNodes(), netLinks(), activation(), pins())

    /** Свои веса с учётом закреплений: закреплённый узел берёт заданный вес (даже если выученного нет). */
    fun applyPins(own: Map<String, Double>, pins: Map<String, Double>): Map<String, Double> = if (pins.isEmpty()) own else own + pins

    /** Множители прежних версий («man») → закреплённые веса: выученный свой вес × множитель; узлы без веса отбрасываются. */
    fun pinsFromBoosts(boosts: Map<String, Double>, own: Map<String, Double>): Map<String, Double> {
        val out = HashMap<String, Double>()
        for ((id0, m) in boosts) {
            val id = if (':' in id0) id0 else "tag:" + key(id0)
            val w = own[id] ?: continue
            out[id] = (w * m).coerceIn(0.0, PIN_MAX)
        }
        return out
    }

    /** Один раз при запуске: множители «man» (до 1.21) → закрепления «pin», «man» очищается. */
    @Synchronized private fun migrateMan() {
        val man = data.optJSONObject("man") ?: return
        val boosts = man.keys().asSequence().associateWith { man.getDouble(it) }
        data.remove("man")
        if (boosts.isEmpty()) return
        val pin = data.getJSONObject("pin")
        pinsFromBoosts(boosts, netNodes()).forEach { (id, w) -> if (!pin.has(id)) pin.put(id, w) }
        net = null
    }

    /** Закреплённый вес узла («тип:термин») или null — вес выученный. Голый тег тоже принимается. */
    @Synchronized fun pinned(id: String): Double? = data.getJSONObject("pin").let { o -> pinId(id).takeIf { o.has(it) }?.let { o.getDouble(it) } }
    /** Закрепить свой вес узла (ползунок «Мой вес» в 3D-графе): абсолютное значение 0…[PIN_MAX]; не затухает, пока не сброшен. */
    @Synchronized fun setWeight(id: String, w: Double) {
        if (w.isNaN()) return
        data.getJSONObject("pin").put(pinId(id), w.coerceIn(0.0, PIN_MAX))
        net = null; save()
    }
    /** Снять закрепление: вес узла снова выученный. */
    @Synchronized fun resetWeight(id: String) { data.getJSONObject("pin").remove(pinId(id)); net = null; save() }
    private fun pinId(id: String) = if (':' in id) id else "tag:" + key(id)
    @Synchronized private fun pins(): Map<String, Double> = data.getJSONObject("pin").let { o -> o.keys().asSequence().associateWith { o.getDouble(it) } }

    /** Верх оси по весу. */
    fun top(axis: String, n: Int): List<Pair<String, Double>> = synchronized(this) {
        val pin = pins()
        // закреплённый вес заменяет выученный (и добавляет узел, которого в оси ещё нет)
        val o = data.getJSONObject(axis)
        val learned = o.keys().asSequence().filter { "$axis:$it" !in pin }.map { it to eff(axis, it, o.getDouble(it)) }
        val fixed = pin.entries.asSequence().filter { it.key.startsWith("$axis:") }.map { it.key.substringAfter(':') to it.value }
        (learned + fixed).filter { it.second > 0.3 }.sortedByDescending { it.second }.take(n).toList()
    }

    /** Верх тегов по активации сети — вместе с теми, что сами не лайкались, но всегда рядом с любимыми. */
    fun topNet(n: Int): List<Pair<String, Double>> = synchronized(this) {
        activation().entries.asSequence().filter { it.value > 0.3 && it.key.startsWith("tag:") }.map { it.key.removePrefix("tag:") to it.value }
            .sortedByDescending { it.second }.take(n).toList()
    }

    val empty get() = AXES.all { top(it, 1).isEmpty() }

    /** Тег для подборки RedGIFs: случайный из верхних по сети с вероятностью по весу (не только самый любимый); skip — исчерпанные. */
    fun pickTag(skip: Set<String> = emptySet()): String? = weighted(topNet(30).filter { it.first !in skip }.take(20))

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
        net = null
        AXES.forEach { axis ->
            val o = data.getJSONObject(axis)
            val kept = o.keys().asSequence().map { it to o.getDouble(it) * f }.filter { kotlin.math.abs(it.second) >= PRUNE }
                .sortedByDescending { kotlin.math.abs(it.second) }.take(MAX_TERMS.getValue(axis)).toList()
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
    private var saveQueued = false
    @Synchronized private fun save() {
        if (saveQueued) return
        saveQueued = true
        io.postDelayed({
            val text = synchronized(this) {
                saveQueued = false
                decay(); data.put("seen", JSONArray(seen.toList()))
                data.put("wt", JSONObject().also { o -> watchSec.forEach { (k, v) -> o.put(k, v) } })
                data.toString()
            }
            val atomic = android.util.AtomicFile(file)
            runCatching {
                val stream = atomic.startWrite()
                try { stream.write(text.toByteArray(Charsets.UTF_8)); atomic.finishWrite(stream) }
                catch (e: Exception) { atomic.failWrite(stream); throw e }
            }.onFailure { android.util.Log.w("Loopa", "Interest checkpoint failed", it) }
        }, 2000)
    }

    /** Сводка для окна «Мои интересы». */
    fun summary(): String = buildString {
        fun line(title: String, axis: String, pre: String) {
            val t = top(axis, 12)
            if (t.isNotEmpty()) append(title).append(": ").append(t.joinToString(", ") { pre + it.first.replace(",", " + $pre") }).append("\n\n")
        }
        line(tr("Теги"), "tag", "#")
        // что подсказала сеть: теги, которых нет среди своих верхних, но они рядом с любимыми
        val own = top("tag", 12).map { it.first }.toSet()
        topNet(30).map { it.first }.filter { it !in own }.take(8).takeIf { it.isNotEmpty() }?.let { append(tr("Рядом с любимыми")).append(": ").append(it.joinToString(", ") { "#$it" }).append("\n\n") }
        line(tr("Сочетания"), "pair", "#"); line(tr("Запросы"), "q", ""); line(tr("Слова"), "kw", ""); line(tr("Сабреддиты"), "sub", "r/"); line(tr("Авторы"), "user", "")
        TasteModel.summary().takeIf { it.isNotEmpty() }?.let { append(it).append("\n\n") }
        if (isEmpty()) append(tr("Пока пусто: лайкайте, досматривайте и ищите — «Для вас» подстроится."))
    }.trim()

    /** Сглаживание веса (логарифм): десяток лайков одного тега не должен перевешивать всё остальное. */
    fun damp(x: Double) = if (x >= 0) ln(1 + x) else -ln(1 - x)
}
