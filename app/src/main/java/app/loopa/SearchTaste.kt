package app.loopa

import android.content.SharedPreferences
import android.os.SystemClock
import org.json.JSONObject
import kotlin.math.sqrt
import kotlin.math.tanh

/** Query-specific online linear ranker. Common features cancel in a pairwise update. */
internal class QueryProfile {
    val weights = LinkedHashMap<String, Double>()

    fun score(features: Map<String, Double>) = features.entries.sumOf { (key, value) -> (weights[key] ?: 0.0) * value }

    fun choose(chosen: Map<String, Double>, passed: Map<String, Double>, strength: Double) {
        val step = strength * FmModel.sigmoid(score(passed) - score(chosen))
        for (key in chosen.keys + passed.keys) {
            val delta = (chosen[key] ?: 0.0) - (passed[key] ?: 0.0)
            if (delta != 0.0) weights[key] = ((weights.remove(key) ?: 0.0) + step * delta).coerceIn(-3.0, 3.0)
        }
        trim()
    }

    /** Не больше [MAX_SINGLES] весов признаков и [MAX_PAIRS] парных на запрос (~100): уходят давно не менявшиеся. */
    fun trim() {
        val groups = weights.keys.groupBy { it.startsWith("pair:") }
        for ((pairs, keys) in groups) {
            val limit = if (pairs) MAX_PAIRS else MAX_SINGLES
            keys.take((keys.size - limit).coerceAtLeast(0)).forEach(weights::remove)
        }
    }

    companion object {
        const val MAX_SINGLES = 60
        const val MAX_PAIRS = 40
    }
}

internal object SearchTaste {
    private const val STORE = TasteStore.SEARCH
    private var prefs: SharedPreferences? = null
    private val profiles = LinkedHashMap<String, QueryProfile>()
    private data class Pass(val id: String, val at: Long, val features: Map<String, Double>)
    private val passes = LinkedHashMap<String, Pass>()
    private val counts = LinkedHashMap<Pair<String,String>, Int>()
    private val spaces = Regex("\\s+")
    fun key(text: String) = text.trim().lowercase().replace(spaces, " ").take(160)

    @Synchronized fun init(p: SharedPreferences) {
        if (prefs != null) return
        prefs = p
        runCatching {
            val rows = org.json.JSONArray(p.getString(STORE, "[]"))
            for (i in 0 until rows.length()) {
                val row = rows.getJSONObject(i)
                val weights = row.getJSONObject("w")
                val profile = QueryProfile()
                weights.keys().forEach { k -> profile.weights[k] = weights.getDouble(k) }
                profile.trim()
                profiles[row.getString("q")] = profile
            }
        }
    }

    private fun save() {
        val rows = org.json.JSONArray()
        profiles.forEach { (q, p) -> rows.put(JSONObject().put("q", q).put("w", JSONObject(p.weights))) }
        prefs?.edit()?.putString(STORE, rows.toString())?.apply()
    }

    /** Keep existing single-feature weights, and add a separately normalized group of interactions. */
    internal fun crossed(singles: Map<String, Double>): Map<String, Double> {
        val keys = singles.keys
        val terms = keys.filter { it.startsWith("tag:") }.take(6) +
            keys.filter { it.startsWith("kw:") }.take(3) +
            keys.filter { it.startsWith("user:") || it.startsWith("site:") || it.startsWith("dur:") || it.startsWith("sub:") }
        val pairs = ArrayList<String>()
        val sorted = terms.sorted()
        for (i in sorted.indices) for (j in i + 1 until sorted.size) {
            val a = sorted[i]
            val b = sorted[j]
            if (a.startsWith("tag:") || b.startsWith("tag:") || a.startsWith("kw:") || b.startsWith("kw:")) {
                pairs += "pair:${a.length}:$a$b"
            }
        }
        if (pairs.isEmpty()) return singles
        val weight = 1.0 / sqrt(pairs.size.toDouble())
        return singles.toMutableMap().apply { pairs.forEach { put(it, weight) } }
    }

    private fun features(g: Gif): Map<String, Double> {
        val values = TasteModel.features(g)
        val scale = sqrt(values.sumOf { it.second.toDouble() * it.second }).coerceAtLeast(1.0)
        return crossed(values.associate { (name, x) -> name to x / scale })
    }

    private val scores = object : LinkedHashMap<Pair<String, String>, Double>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Pair<String, String>, Double>) = size > 2048
    }
    @Synchronized fun hasProfile(q: String) = TrainingLab.learning && q in profiles

    @Synchronized fun bonus(q: String, g: Gif): Double {
        if (!TrainingLab.learning) return 0.0
        val profile = profiles[q] ?: return 0.0
        return scores.getOrPut(q to g.id) { 2 * tanh(profile.score(features(g))) }
    }

    @Synchronized fun pass(q: String, g: Gif, quick: Boolean = false) {
        if (!TrainingLab.learning) return
        val id = q to g.id
        val count = ((counts.remove(id) ?: 0) + 1).coerceAtMost(4)
        counts[id] = count
        while (counts.size > 512) counts.remove(counts.keys.first())
        if (quick || count >= 4) {
            passes.remove(q)
            passes[q] = Pass(g.id, SystemClock.elapsedRealtime(), features(g))
            while (passes.size > 32) passes.remove(passes.keys.first())
        }
    }

    @Synchronized fun opened(q: String, id: String) {
        counts.remove(q to id)
        if (passes[q]?.id == id) passes.remove(q)
    }

    @Synchronized fun choose(q: String, g: Gif, strength: Double) {
        if (!TrainingLab.learning) return
        val pass = passes.remove(q)?.takeIf { it.id != g.id && SystemClock.elapsedRealtime() - it.at <= 600_000L }
        val profile = profiles.remove(q) ?: QueryProfile()
        profile.choose(features(g), pass?.features.orEmpty(), strength)
        scores.clear()
        profiles[q] = profile
        while (profiles.size > 48) profiles.remove(profiles.keys.first())
        save()
    }

    @Synchronized fun reset() {
        scores.clear()
        profiles.clear()
        counts.clear()
        passes.clear()
        prefs?.edit()?.remove(STORE)?.apply()
    }
}
