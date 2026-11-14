package app.loopa

import org.json.JSONArray
import org.json.JSONObject

/**
 * Граф интересов для экрана «3D-граф»: узлы — теги сети вкуса ([TagNet]), связи — сочетания «a,b», сообщества — группы
 * тегов, что связаны друг с другом сильнее, чем с остальными. Чистые функции без Android — проверяются тестами.
 */
object InterestGraph {
    /** Сколько тегов показывать: больше на телефоне не прочесть и не отрисовать. */
    const val MAX_NODES = 150
    /** Ниже этой активации тег в граф не попадает (как в [Interests.topNet]). */
    const val MIN_ACT = 0.3
    /** Предел шагов распространения меток. */
    const val MAX_ITER = 20
    /** Палитра сообществ — COMMUNITY_COLORS из graphify, по кругу. */
    val COLORS = listOf("#4E79A7", "#F28E2B", "#E15759", "#76B7B2", "#59A14F", "#EDC948", "#B07AA1", "#FF9DA7", "#9C755F", "#BAB0AC")

    class Node(val id: String, val own: Double, val act: Double, val boost: Double = 1.0) { val suggested get() = own <= MIN_ACT }
    class Link(val a: String, val b: String, val w: Double)

    /** Узлы: до [MAX_NODES] тегов с наибольшей активацией (> [MIN_ACT]); порядок — по убыванию активации, затем по имени. */
    /** Теги с ручной поправкой ([boost]) остаются в графе, даже если ползунком их почти выключили: иначе не вернуть. */
    fun nodes(own: Map<String, Double>, act: Map<String, Double>, boost: Map<String, Double> = emptyMap()): List<Node> =
        act.entries.filter { (it.value > MIN_ACT || it.key in boost) && ',' !in it.key }
            .sortedWith(compareByDescending<Map.Entry<String, Double>> { it.value }.thenBy { it.key })
            .take(MAX_NODES).map { Node(it.key, own[it.key] ?: 0.0, it.value, boost[it.key] ?: 1.0) }

    /**
     * Связи между выбранными узлами: вес > 0, у узла не больше [TagNet.FANOUT] — берём сильнейшие, пока у обоих концов
     * есть место.
     */
    fun links(nodes: List<Node>, pairs: Map<String, Double>): List<Link> {
        val ids = nodes.mapTo(HashSet()) { it.id }
        val all = ArrayList<Link>()
        for ((k, w) in pairs) {
            if (w <= 0) continue
            val i = k.indexOf(',')
            if (i <= 0 || i == k.length - 1) continue
            val a = k.substring(0, i); val b = k.substring(i + 1)
            if (a != b && a in ids && b in ids) all += Link(a, b, w)
        }
        all.sortWith(compareByDescending<Link> { it.w }.thenBy { it.a }.thenBy { it.b })
        val deg = HashMap<String, Int>()
        val out = ArrayList<Link>()
        for (l in all) {
            if ((deg[l.a] ?: 0) >= TagNet.FANOUT || (deg[l.b] ?: 0) >= TagNet.FANOUT) continue
            deg[l.a] = (deg[l.a] ?: 0) + 1; deg[l.b] = (deg[l.b] ?: 0) + 1
            out += l
        }
        return out
    }

    /**
     * Сообщества — взвешенное распространение меток: каждый узел берёт метку, у которой больше суммарный вес связей
     * соседей. Порядок обхода фиксирован (узлы уже по убыванию активации), при равенстве побеждает метка тяжелее —
     * результат всегда один и тот же. Возвращает метку-индекс узла для каждого узла.
     */
    fun communities(nodes: List<Node>, links: List<Link>): IntArray {
        val idx = HashMap<String, Int>().also { m -> nodes.forEachIndexed { i, n -> m[n.id] = i } }
        val adj = List(nodes.size) { ArrayList<Pair<Int, Double>>() }
        for (l in links) { val a = idx.getValue(l.a); val b = idx.getValue(l.b); adj[a] += b to l.w; adj[b] += a to l.w }
        val label = IntArray(nodes.size) { it }
        repeat(MAX_ITER) {
            var changed = false
            for (i in nodes.indices) {
                if (adj[i].isEmpty()) continue
                val sum = HashMap<Int, Double>()
                for ((j, w) in adj[i]) sum[label[j]] = (sum[label[j]] ?: 0.0) + w
                // метка с наибольшим весом; при равенстве — с меньшим номером (тяжелее по активации)
                val best = sum.entries.sortedWith(compareByDescending<Map.Entry<Int, Double>> { it.value }.thenBy { it.key }).first().key
                if (best != label[i]) { label[i] = best; changed = true }
            }
            if (!changed) return label
        }
        return label
    }

    /** JSON {nodes:[{id,own,act,suggested,c}], links:[{source,target,w}], communities:[{id,name,color,size}]}. */
    fun build(own: Map<String, Double>, pairs: Map<String, Double>, act: Map<String, Double>, boost: Map<String, Double> = emptyMap()): String {
        val ns = nodes(own, act, boost)
        val ls = links(ns, pairs)
        val label = communities(ns, ls)
        // номера сообществ — по суммарной активации (убывание), при равенстве — по самой тяжёлой метке
        val groups = label.indices.groupBy { label[it] }.values
            .sortedWith(compareByDescending<List<Int>> { g -> g.sumOf { ns[it].act } }.thenBy { it.min() })
        val cid = IntArray(ns.size)
        val comms = JSONArray()
        groups.forEachIndexed { c, g ->
            g.forEach { cid[it] = c }
            comms.put(JSONObject().put("id", c).put("name", ns[g.min()].id).put("color", COLORS[c % COLORS.size]).put("size", g.size))
        }
        val nodesJson = JSONArray()
        ns.forEachIndexed { i, n ->
            nodesJson.put(JSONObject().put("id", n.id).put("own", n.own).put("act", n.act).put("suggested", n.suggested && n.boost >= 1.0).put("boost", n.boost).put("c", cid[i]))
        }
        val linksJson = JSONArray()
        ls.forEach { linksJson.put(JSONObject().put("source", it.a).put("target", it.b).put("w", it.w)) }
        return JSONObject().put("nodes", nodesJson).put("links", linksJson).put("communities", comms).toString()
    }
}
