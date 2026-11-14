package app.loopa

/**
 * Сеть вкуса: узлы — все интересы (теги, авторы, сабреддиты, слова названий, запросы целиком, сайты), связи — то, что
 * нравилось вместе. Чистые функции — проверяются тестами.
 *
 * Идея взята у графовых рекомендателей (Pinterest Pixie: блуждания по графу «пин — доска») и у правила Хебба
 * («что срабатывает вместе — связывается»), упрощённо под телефон:
 *  - узел — типизированный id «тип:термин» (tag, user, sub, kw, q, site); вес узла — то, что выучил [Interests] по его
 *    оси: чем чаще термин встречался в лайках, скачиваниях, досмотрах и запросах, тем он тяжелее (повторы усиливают);
 *  - связь — ключ «idA|idB» (id по алфавиту, [linkKey]): сочетания тегов (ось «pair», читается как «tag:a|tag:b») и
 *    связи между типами (ось «link»: тег — автор, тег — сабреддит, тег — сайт, запрос — тег и т. д.);
 *  - активация узла = свой вес + [SPREAD] × сглаженное среднее весов соседей по силе связи. Так автор, которого сам ни
 *    разу не лайкали, но чьи теги любимы, получает вес, а плотная группа любимых узлов усиливает каждый свой узел.
 *    Один шаг распространения: дальше — шум, а считать надо на каждый ролик.
 */
object TagNet {
    /** Доля соседей в активации. */
    const val SPREAD = 0.5
    /** Сглаживание: одна слабая связь не должна перенести на узел весь вес соседа. */
    const val SMOOTH = 5.0
    /** Сколько самых сильных связей узла учитывать. */
    const val FANOUT = 12

    /** Разделить ключ связи «a|b» на концы; null — не связь (нет разделителя, пустой конец, петля). */
    fun split(k: String): Pair<String, String>? {
        val i = k.indexOf('|')
        if (i <= 0 || i == k.length - 1) return null
        val a = k.substring(0, i); val b = k.substring(i + 1)
        return if (a == b) null else a to b
    }

    /** Соседи каждого узла по связям «a|b» (только положительные, сильнейшие [FANOUT]). */
    fun edges(links: Map<String, Double>): Map<String, List<Pair<String, Double>>> {
        val adj = HashMap<String, MutableList<Pair<String, Double>>>()
        for ((k, w) in links) {
            if (w <= 0) continue
            val (a, b) = split(k) ?: continue
            adj.getOrPut(a) { ArrayList() } += b to w
            adj.getOrPut(b) { ArrayList() } += a to w
        }
        return adj.mapValues { (_, l) -> l.sortedByDescending { it.second }.take(FANOUT) }
    }

    /** Добавка от соседей: Σ связь·вес соседа / (Σ связь + [SMOOTH]); отрицательные соседи не тянут вниз. */
    fun spread(neighbours: List<Pair<String, Double>>, nodes: Map<String, Double>): Double {
        var num = 0.0; var den = 0.0
        for ((n, e) in neighbours) { num += e * maxOf(nodes[n] ?: 0.0, 0.0); den += e }
        return if (num <= 0) 0.0 else num / (den + SMOOTH)
    }

    /** Активация всех узлов: свой вес + [SPREAD] × добавка от соседей. Узлы без своего веса тоже появляются. */
    fun activate(nodes: Map<String, Double>, links: Map<String, Double>): Map<String, Double> {
        val out = HashMap(nodes)
        for ((t, ns) in edges(links)) {
            val s = spread(ns, nodes)
            if (s > 0) out[t] = (out[t] ?: 0.0) + SPREAD * s
        }
        return out
    }

    /** Связь между типизированными узлами «a|b»: по алфавиту; null — одинаковые, пустые или с разделителем. */
    fun linkKey(a: String, b: String): String? {
        if (a.isEmpty() || b.isEmpty() || a == b || '|' in a || '|' in b) return null
        return if (a < b) "$a|$b" else "$b|$a"
    }

    /** Связь «a,b» в одном написании: по алфавиту; null — одинаковые, пустые или с запятой. */
    fun pairKey(a: String, b: String): String? {
        if (a.isEmpty() || b.isEmpty() || a == b || ',' in a || ',' in b) return null
        return if (a < b) "$a,$b" else "$b,$a"
    }

    /** Насколько неожиданным был пропуск: 1 — ничего не ждали, до 2 — ролик считался «во вкусе» (как ошибка сети). */
    fun surprise(predicted: Double): Double = 1.0 + (Interests.damp(maxOf(predicted, 0.0)) / 2.0).coerceAtMost(1.0)
}
