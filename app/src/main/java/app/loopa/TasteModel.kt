package app.loopa

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import kotlin.math.exp
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * Факторизационная машина (Rendle, 2010) — «маленькая нейросеть вкуса», которая учится на телефоне по ходу
 * просмотра. Чистая математика без Android — проверяется тестами.
 *
 *   ŷ = w0 + Σ wᵢ·xᵢ + Σ_{i<j} ⟨vᵢ, vⱼ⟩·xᵢ·xⱼ,   p = σ(ŷ) — вероятность «понравится».
 * Сумма по парам считается за O(k·n): ½·Σ_f [(Σᵢ vᵢf·xᵢ)² − Σᵢ vᵢf²·xᵢ²]. Признаки — строки («tag:x», «user:y»),
 * хешируются в [bits]-битные корзины (коллизии допустимы). Каждый признак — вектор vᵢ из [k] чисел, поэтому модель
 * учит не только «нравится тег a» и «нравится тег b», но и «a вместе с b» — даже для пары, которой ещё не встречала.
 * Обучение — онлайн: логистическая потеря с весом примера, AdaGrad по каждой координате, L2 на w и v.
 * Начальные v — N(0; 0,01), детерминированно от номера корзины и компоненты: после сброса или переноса файла
 * всё воспроизводится.
 */
class FmModel(val bits: Int = 15, val k: Int = 8, lazyFactors: Boolean = false) {
    private val n = 1 shl bits
    private val mask = n - 1
    private var w0 = 0f
    private var gw0 = 0f
    private val w = FloatArray(n)
    private val gw = FloatArray(n)
    private val v = FloatArray(n * k)
    private val gv = FloatArray(n * k)
    /** Сколько примеров обучено (с учётом только числа, не весов). */
    var examples = 0L; private set
    /** Из них примеров по роликам (не по одиночным признакам): по ним считается доля модели в оценке. */
    var videoExamples = 0L; private set
    /** Корзина → имя признака для последних [MAX_NAMES] признаков (порядок — по давности): для читаемой сводки. */
    private val names = object : LinkedHashMap<Int, String>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, String>) = size > MAX_NAMES
    }

    /** [lazyFactors] — не считать начальные v в конструкторе (их затрёт загрузка файла; нет файла — [initFactors] вручную). */
    init { if (!lazyFactors) initFactors() }

    @Synchronized fun initFactors() {
        for (i in 0 until n) for (f in 0 until k) v[i * k + f] = (gauss(i.toLong() * k + f) * INIT_STD).toFloat()
    }

    /** Детерминированное N(0;1) от номера: два числа splitmix64 → Бокс-Мюллер. */
    private fun gauss(seed: Long): Double {
        val a = (mix(seed * 2 + 1) ushr 11).toDouble() / (1L shl 53).toDouble()
        val b = (mix(seed * 2 + 2) ushr 11).toDouble() / (1L shl 53).toDouble()
        return sqrt(-2.0 * kotlin.math.ln(a.coerceAtLeast(1e-12))) * kotlin.math.cos(2 * Math.PI * b)
    }

    private fun mix(x0: Long): Long {
        var x = x0 + -0x61c8864680b583ebL
        x = (x xor (x ushr 30)) * -0x40a7b892e31b1a47L
        x = (x xor (x ushr 27)) * -0x6b2fb644ecceee15L
        return x xor (x ushr 31)
    }

    /** Стабильный хеш строки (FNV-1a + финальное перемешивание) → корзина. Не зависит от версии JVM, в отличие от hashCode. */
    fun bucket(name: String): Int {
        var h = -0x7ee3623b   // 2166136261
        for (c in name) { h = (h xor c.code) * 16777619 }
        h = h xor (h ushr 16); h *= -0x7a143595; h = h xor (h ushr 13); h *= -0x3d4d51cb; h = h xor (h ushr 16)
        return h and mask
    }

    /** Логит ŷ признаков (имя → значение). Пустой набор — w0. */
    fun logit(features: List<Pair<String, Float>>): Double = synchronized(this) {
        val idx = IntArray(features.size) { bucket(features[it].first) }
        logitAt(idx, FloatArray(features.size) { features[it].second })
    }

    private fun logitAt(idx: IntArray, x: FloatArray): Double {
        var y = w0.toDouble()
        for (t in idx.indices) y += w[idx[t]] * x[t]
        for (f in 0 until k) {
            var s = 0.0; var s2 = 0.0
            for (t in idx.indices) { val a = v[idx[t] * k + f] * x[t]; s += a; s2 += a * a }
            y += 0.5 * (s * s - s2)
        }
        return y
    }

    fun predict(features: List<Pair<String, Float>>): Double = sigmoid(logit(features))

    /** Evidence from this video's features, relative to the model's featureless baseline. */
    @Synchronized fun bonus(features: List<Pair<String, Float>>): Double {
        if (videoExamples == 0L || features.isEmpty()) return 0.0
        return (BONUS_SCALE * tanh((logit(features) - w0) / 2)).coerceAtLeast(-BONUS_NEG_MAX)
    }

    /** Pairwise logistic loss on the linear weights, using the complete FM prediction for the margin.
     * Shared features cancel. The original observations already trained the interactions and bias.
     */
    @Synchronized fun prefer(chosen: List<Pair<String, Float>>, skipped: List<Pair<String, Float>>) {
        if (chosen.isEmpty() || skipped.isEmpty()) return
        val delta = HashMap<Int, Float>()
        for ((name, x) in chosen) delta.merge(bucket(name), x, Float::plus)
        for ((name, x) in skipped) delta.merge(bucket(name), -x, Float::plus)
        val error = sigmoid(logit(skipped) - logit(chosen)).toFloat()
        for ((index, difference) in delta) {
            if (difference == 0f) continue
            val gradient = -error * difference
            gw[index] += gradient * gradient
            w[index] -= LR_W * gradient / (sqrt(gw[index]) + EPS)
        }
    }

    /**
     * Один шаг обучения: label 0 или 1, weight — вес примера (градиент умножается на него; AdaGrad сам подберёт шаг).
     * [video] — пример по ролику (считается для доли модели); у примера из одного признака пар нет — v не трогаем.
     */
    fun learn(features: List<Pair<String, Float>>, label: Float, weight: Float, video: Boolean = true) {
        if (features.isEmpty() || weight <= 0f || !weight.isFinite() || !label.isFinite() || label !in 0f..1f || features.any { !it.second.isFinite() }) return
        synchronized(this) {
            val idx = IntArray(features.size) { bucket(features[it].first) }
            val x = FloatArray(features.size) { features[it].second }
            idx.indices.forEach { names[idx[it]] = features[it].first }
            val g = ((sigmoid(logitAt(idx, x)) - label) * weight).toFloat()
            // w0
            gw0 += g * g; w0 -= LR_W * g / (sqrt(gw0) + EPS)
            // w и v по каждой координате
            val sums = FloatArray(k)
            for (f in 0 until k) { var s = 0f; for (t in idx.indices) s += v[idx[t] * k + f] * x[t]; sums[f] = s }
            for (t in idx.indices) {
                val i = idx[t]; val xi = x[t]
                val grad = g * xi + L2_W * w[i]
                gw[i] += grad * grad; w[i] -= LR_W * grad / (sqrt(gw[i]) + EPS)
                if (idx.size < 2) continue   // у одного признака нет пар: v не обновляем (иначе только затухание L2)
                for (f in 0 until k) {
                    val o = i * k + f
                    val vg = g * xi * (sums[f] - v[o] * xi) + L2_V * v[o]
                    gv[o] += vg * vg; v[o] -= LR_V * vg / (sqrt(gv[o]) + EPS)
                }
            }
            examples++; if (video) videoExamples++
        }
    }

    /**
     * Самые сильные сочетания признаков: пары с наибольшим ⟨vᵢ,vⱼ⟩ среди признаков, имена которых помним. Пары двух
     * признаков одной группы («tag:a + tag:b») и разных («tag:a + user:b») равноправны. До [limit] штук, по убыванию.
     */
    fun topPairs(limit: Int): List<Triple<String, String, Double>> = synchronized(this) {
        // сначала по длине вектора — пар между сотнями признаков уже достаточно
        val cand = names.entries.map { it.key to it.value }
            .sortedByDescending { (b, _) -> var s = 0.0; for (f in 0 until k) s += v[b * k + f] * v[b * k + f]; s }.take(TOP_CANDIDATES)
        val out = ArrayList<Triple<String, String, Double>>()
        for (a in cand.indices) for (b in a + 1 until cand.size) {
            val (ia, na) = cand[a]; val (ib, nb) = cand[b]
            if (ia == ib || gw[ia] == 0f || gw[ib] == 0f) continue
            var d = 0.0; for (f in 0 until k) d += v[ia * k + f] * v[ib * k + f]
            if (d > 0) out += Triple(na, nb, d)
        }
        out.sortedByDescending { it.third }.take(limit)
    }

    /** Обнулить: веса, накопители AdaGrad, счётчик и имена; v — снова детерминированное начальное. */
    fun reset() = synchronized(this) {
        w0 = 0f; gw0 = 0f; w.fill(0f); gw.fill(0f); gv.fill(0f); examples = 0; videoExamples = 0; names.clear(); initFactors()
    }

    // ---- хранение: версия формата, w0, w[], v[], накопители, имена ----
    // v2: после examples — videoExamples (long); v1 читаем (videoExamples = examples). Числа — big-endian, как у DataOutputStream.

    /** Копия состояния: под замком — только быстрые copyOf, всё тяжёлое (запись в байты) идёт уже без замка. */
    private class Snap(val ex: Long, val vex: Long, val w0: Float, val gw0: Float,
                       val w: FloatArray, val gw: FloatArray, val v: FloatArray, val gv: FloatArray, val names: List<Pair<Int, String>>)

    private fun snapshot(): Snap = synchronized(this) {
        Snap(examples, videoExamples, w0, gw0, w.copyOf(), gw.copyOf(), v.copyOf(), gv.copyOf(), names.entries.map { it.key to it.value })
    }

    fun toBytes(): ByteArray {
        val s = snapshot()
        val nb = ByteArrayOutputStream(1024)
        DataOutputStream(nb).use { o -> o.writeInt(s.names.size); s.names.forEach { (b, t) -> o.writeInt(b); o.writeUTF(t) } }
        val nameBytes = nb.toByteArray()
        val bb = ByteBuffer.allocate(HEADER_BYTES + 4 * (s.w.size + s.gw.size + s.v.size + s.gv.size) + nameBytes.size)
        bb.putInt(MAGIC).putInt(VERSION).putInt(bits).putInt(k).putLong(s.ex).putLong(s.vex).putFloat(s.w0).putFloat(s.gw0)
        for (a in arrayOf(s.w, s.gw, s.v, s.gv)) { bb.asFloatBuffer().put(a); bb.position(bb.position() + a.size * 4) }
        bb.put(nameBytes)
        return bb.array()
    }

    /**
     * Загрузить из [toBytes] (и из v1); false — файл чужой версии или размера, либо в числах NaN/Inf (модель при этом
     * не тронута). Разбор — без замка, под замком только быстрое копирование.
     */
    fun load(bytes: ByteArray): Boolean = runCatching {
        val bb = ByteBuffer.wrap(bytes)
        if (bb.getInt() != MAGIC) return false
        val ver = bb.getInt()
        if ((ver != 1 && ver != VERSION) || bb.getInt() != bits || bb.getInt() != k) return false
        val ex = bb.getLong(); val vex = if (ver >= 2) bb.getLong() else ex
        val nw0 = bb.getFloat(); val ngw0 = bb.getFloat()
        fun floats(len: Int) = FloatArray(len).also { bb.asFloatBuffer().get(it); bb.position(bb.position() + len * 4) }
        val nw = floats(n); val ngw = floats(n); val nv = floats(n * k); val ngv = floats(n * k)
        if (!nw0.isFinite() || !ngw0.isFinite() || !finite(nw) || !finite(ngw) || !finite(nv) || !finite(ngv)) return false
        val nn = LinkedHashMap<Int, String>()
        DataInputStream(ByteArrayInputStream(bytes, bb.position(), bb.remaining())).use { i ->
            repeat(i.readInt().coerceIn(0, MAX_NAMES)) { nn[i.readInt() and mask] = i.readUTF() }
        }
        synchronized(this) {
            w0 = nw0; gw0 = ngw0; nw.copyInto(w); ngw.copyInto(gw); nv.copyInto(v); ngv.copyInto(gv)
            names.clear(); names.putAll(nn); examples = ex; videoExamples = vex
        }
        true
    }.getOrDefault(false)

    private fun finite(a: FloatArray): Boolean { for (x in a) if (!x.isFinite()) return false; return true }

    companion object {
        const val MAX_NAMES = 2000
        private const val MAGIC = 0x54464D31   // «TFM1»
        private const val VERSION = 2
        /** Байт до массивов: 4 int + 2 long + 2 float. */
        private const val HEADER_BYTES = 16 + 16 + 8
        /** Масштаб добавки модели (единицы [Interests.score]) и нижний предел её отрицательной части. */
        const val BONUS_SCALE = 8.0
        const val BONUS_NEG_MAX = 0.8
        private const val INIT_STD = 0.01
        private const val LR_W = 0.15f
        private const val LR_V = 0.08f
        private const val L2_W = 1e-4f
        private const val L2_V = 1e-3f
        private const val EPS = 1e-6f
        private const val TOP_CANDIDATES = 300
        fun sigmoid(x: Double) = 1.0 / (1.0 + exp(-x.coerceIn(-30.0, 30.0)))
    }
}

/**
 * Модель вкуса на телефоне (files/taste_fm.bin): [FmModel] с признаками роликов и записью на диск. Дополняет сеть
 * вкуса ([Interests], [TagNet]): сеть складывает веса тегов и их соседей, а модель учит именно сочетания признаков
 * (тег + тег, тег + автор, тег + длительность …) и обучается на тех же сигналах.
 *
 * Признаки ролика ([features]): «tag:x» (все теги, общие тоже — их вес сам выучится малым), «user:x», «site:x», «sub:x»,
 * «kw:x» (до [KW_WORDS] слов названия), «dur:<10|<30|<120|<600|600+» и «audio». Значение признака — 1/√n, где n — число
 * признаков своей группы: ролик с тридцатью тегами не перевесит ролик с тремя.
 *
 * Сигналы ([Interests] вызывает [learn]): лайк — 1 (вес 3), снят лайк — 0 (вес 2), скачивание — 1 (вес 3), прирост
 * времени просмотра — 1 (вес = прирост), быстрый пропуск — 0 (вес 1 × неожиданность), показ в сетке «Для вас» без открытия — 0
 * (вес [IMPRESSION_WEIGHT]). Переход по тегу, автору,
 * сабреддиту или слову поиска — сигнал по одному признаку: [learnFeature] («псевдо-ролик» из него одного, 1, вес 1).
 */
object TasteModel {
    private const val BITS = 15
    private const val K = 8
    private const val KW_WORDS = 6
    private const val MAX_TAGS = 40
    /** С скольких примеров модель получает полную долю в оценке и какая это доля. */
    const val FULL_EXAMPLES = 300
    const val MAX_ALPHA = 0.6
    private const val SAVE_DELAY_MS = 5000L
    /** Вес примера «был на экране сетки и не открыт»: слабый — ролик мог просто не приглянуться, а мог не успеть. */
    const val IMPRESSION_WEIGHT = 0.15

    private val model = FmModel(BITS, K, lazyFactors = true)
    private var file: File? = null
    private val io by lazy { Handler(HandlerThread("taste-fm").apply { start() }.looper) }
    /** Файл прочитан (или его нет и модель начата с нуля): до этого доля модели в оценке 0, обучение не принимается. */
    @Volatile private var loaded = false

    /** Загрузка — в фоне, чтобы не держать запуск: пока не готово, [alpha] = 0. Начальные v считаем, только если файла нет. */
    @Synchronized fun init(ctx: Context) {
        if (file != null) return
        val f = File(ctx.filesDir, "taste_fm.bin"); file = f
        io.post {
            var ok = false
            if (f.exists()) {
                ok = runCatching { model.load(android.util.AtomicFile(f).readFully()) }.getOrDefault(false)
                if (!ok) android.util.Log.w("Loopa", "taste_fm.bin отвергнут (чужая версия, обрезан или NaN/Inf) — модель вкуса с нуля")
            }
            if (!ok) model.initFactors()
            loaded = true
        }
    }

    /** Сколько примеров выучено. */
    val examples get() = model.examples

    /** Доля модели в оценке вкуса: 0 без примеров по роликам (и пока файл не загружен), линейно до [MAX_ALPHA] к [FULL_EXAMPLES]. */
    fun alpha(): Double = if (!loaded) 0.0 else MAX_ALPHA * (model.videoExamples.toDouble() / FULL_EXAMPLES).coerceIn(0.0, 1.0)

    fun bucketOfDuration(sec: Double) = when {
        sec < 10 -> "dur:<10"; sec < 30 -> "dur:<30"; sec < 120 -> "dur:<120"; sec < 600 -> "dur:<600"; else -> "dur:600+"
    }

    /** Признаки ролика с нормировкой 1/√n по группам (теги, слова — много; остальные — по одному). */
    fun features(g: Gif): List<Pair<String, Float>> {
        val out = ArrayList<Pair<String, Float>>(24)
        fun group(prefix: String, items: Collection<String>) {
            if (items.isEmpty()) return
            val x = (1.0 / sqrt(items.size.toDouble())).toFloat()
            items.forEach { out += "$prefix:$it" to x }
        }
        group("tag", g.tags.filter { !it.startsWith("r/") }.map(Interests::key).filter { it.isNotEmpty() }.distinct().take(MAX_TAGS))
        group("user", listOfNotNull(g.user.lowercase().takeIf { it.isNotEmpty() && it != "[deleted]" }))
        group("site", listOf(Interests.siteOf(g)))
        group("sub", listOfNotNull(g.sub.lowercase().takeIf { it.isNotEmpty() }))
        group("kw", Interests.words(g.description).take(KW_WORDS))
        if (g.duration > 0) out += bucketOfDuration(g.duration) to 1f
        if (g.hasAudio) out += "audio" to 1f
        return out
    }

    /** Вероятность «понравится» (0,5 у пустой модели). */
    fun predict(g: Gif): Double = model.predict(features(g))

    /**
     * Добавка к оценке вкуса ([FmModel.bonus]): итоговая оценка — `сеть + α·добавка`. Относительно скользящего среднего
     * по недавним роликам: «во вкусе» — плюс (до [FmModel.BONUS_SCALE]), хуже обычного — небольшой минус (не ниже −0,8).
     */
    fun bonus(g: Gif): Double = if (!loaded) 0.0 else model.bonus(features(g))

    private data class Skipped(val id: String, val at: Long, val features: List<Pair<String, Float>>)
    private val skipped = ArrayDeque<Skipped>()

    fun noteSkip(g: Gif) {
        if (!loaded) return
        val item = Skipped(g.id, android.os.SystemClock.elapsedRealtime(), features(g))
        synchronized(skipped) {
            skipped.removeAll { it.id == g.id }
            skipped.addLast(item)
            if (skipped.size > 8) skipped.removeFirst()
        }
    }

    fun forgetSkip(id: String) { synchronized(skipped) { skipped.removeAll { it.id == id } } }

    /** Обучить на ролике: label 1 — понравилось, 0 — нет; weight — вес примера. */
    fun learn(g: Gif, label: Float, weight: Double, compare: Boolean = false) {
        if (!loaded) return
        val chosen = features(g)
        model.learn(chosen, label, weight.toFloat())
        if (compare && label == 1f) {
            val now = android.os.SystemClock.elapsedRealtime()
            val alternative = synchronized(skipped) {
                skipped.removeAll { it.id == g.id || now - it.at > 10 * 60_000L }
                skipped.removeLastOrNull()
            }
            alternative?.let { model.prefer(chosen, it.features) }
        }
        scheduleSave()
    }

    /** Ролик был на экране сетки «Для вас» достаточно долго и не открыт: слабый отрицательный пример ([IMPRESSION_WEIGHT]). */
    fun learnImpression(g: Gif) = learn(g, 0f, IMPRESSION_WEIGHT)

    /** Сигнал по одному признаку («tag:x», «user:x», «sub:x») — псевдо-ролик из него одного: открыл тег/автора/сабреддит/запрос. Долю модели не растит. */
    fun learnFeature(name: String, label: Float = 1f, weight: Double = 1.0) {
        if (!loaded || name.substringAfter(':', "").isBlank()) return
        model.learn(listOf(name to 1f), label, weight.toFloat(), video = false); scheduleSave()
    }

    /** Сброс: если файл ещё грузится — после загрузки (очередь одного потока), иначе сразу. */
    fun reset() {
        synchronized(skipped) { skipped.clear() }
        if (loaded) { model.reset(); scheduleSave() } else io.post { model.reset(); loaded = true; scheduleSave() }
    }

    /** Читаемое имя признака для сводки. */
    fun display(name: String): String {
        val kind = name.substringBefore(':'); val rest = name.substringAfter(':', "")
        return when (kind) { "tag" -> "#$rest"; "sub" -> "r/$rest"; "user" -> "@$rest"; "kw" -> "«$rest»"; "dur" -> rest + "s"; else -> if (rest.isEmpty()) kind else rest }
    }

    /** Строки для «Мои интересы»: число примеров и верх сочетаний; пусто, пока ничему не учили. */
    fun summary(): String {
        if (model.examples == 0L) return ""
        val head = tr("Модель вкуса, примеров: %s", model.examples.toString())
        val pairs = model.topPairs(5)
        if (pairs.isEmpty()) return head
        return head + "\n" + tr("Сильные сочетания модели") + ": " + pairs.joinToString(", ") { display(it.first) + " + " + display(it.second) }
    }

    @Volatile private var savePending = false

    /** Запись в фоне и не чаще раза в 5 с (если запись уже запланирована, новую не ставим — иначе при частых сигналах она не наступила бы). */
    private fun scheduleSave() {
        val f = file ?: return
        if (savePending) return
        savePending = true
        io.postDelayed({
            savePending = false
            val bytes = model.toBytes()   // под замком модели — только копия массивов; запись в байты и на диск — здесь, в фоне
            runCatching {
                val atomic = android.util.AtomicFile(f)
                val stream = atomic.startWrite()
                try { stream.write(bytes); atomic.finishWrite(stream) }
                catch (e: Exception) { atomic.failWrite(stream); throw e }
            }.onFailure { android.util.Log.w("Loopa", "Model checkpoint failed", it) }
        }, SAVE_DELAY_MS)
    }
}
