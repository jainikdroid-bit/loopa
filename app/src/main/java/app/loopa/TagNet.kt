package app.loopa

/**
 * Сеть вкуса: теги — узлы, сочетания тегов — связи. Чистые функции — проверяются тестами.
 *
 * Идея взята у графовых рекомендателей (Pinterest Pixie: блуждания по графу «пин — доска») и у правила Хебба
 * («что срабатывает вместе — связывается»), упрощённо под телефон:
 *  - вес узла — то, что выучил [Interests] по оси «tag»: чем чаще тег встречался в лайках, скачиваниях, досмотрах и
 *    запросах, тем он тяжелее (повторы усиливают, см. Interests.eff);
 *  - вес связи «a,b» — ось «pair»: теги, что раз за разом нравятся вместе (в одном ролике или «запрос → лайкнутый
 *    ролик»), связываются сильнее;
 *  - активация узла = свой вес + [SPREAD] × сглаженное среднее весов соседей по силе связи. Так тег, который сам ни
 *    разу не лайкали, но который постоянно рядом с любимыми, получает вес, а плотная группа любимых тегов
 *    усиливает каждый свой тег. Один шаг распространения: дальше — шум, а считать надо на каждый ролик.
 */
object TagNet {
    /** Доля соседей в активации. */
    const val SPREAD = 0.5
    /** Сглаживание: одна слабая связь не должна перенести на тег весь вес соседа. */
    const val SMOOTH = 5.0
    /** Сколько самых сильных связей узла учитывать. */
    const val FANOUT = 12

    /** Соседи каждого тега по связям «a,b» (только положительные, сильнейшие [FANOUT]). */
    fun edges(pairs: Map<String, Double>): Map<String, List<Pair<String, Double>>> {
        val adj = HashMap<String, MutableList<Pair<String, Double>>>()
        for ((k, w) in pairs) {
            if (w <= 0) continue
            val i = k.indexOf(',')
            if (i <= 0 || i == k.length - 1) continue
            val a = k.substring(0, i); val b = k.substring(i + 1)
            if (a == b) continue
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
    fun activate(nodes: Map<String, Double>, pairs: Map<String, Double>): Map<String, Double> {
        val out = HashMap(nodes)
        for ((t, ns) in edges(pairs)) {
            val s = spread(ns, nodes)
            if (s > 0) out[t] = (out[t] ?: 0.0) + SPREAD * s
        }
        return out
    }

    /** Связь «a,b» в одном написании: по алфавиту; null — одинаковые, пустые или с запятой. */
    fun pairKey(a: String, b: String): String? {
        if (a.isEmpty() || b.isEmpty() || a == b || ',' in a || ',' in b) return null
        return if (a < b) "$a,$b" else "$b,$a"
    }

    /** Насколько неожиданным был пропуск: 1 — ничего не ждали, до 2 — ролик считался «во вкусе» (как ошибка сети). */
    fun surprise(predicted: Double): Double = 1.0 + (Interests.damp(maxOf(predicted, 0.0)) / 2.0).coerceAtMost(1.0)
}
