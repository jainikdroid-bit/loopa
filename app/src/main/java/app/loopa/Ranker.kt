package app.loopa

import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Порядок роликов в «Для вас» и добавка к релевантности поиска. Чистые функции — проверяются тестами.
 *
 *  - вкус: оценка профиля ([Interests.score]) с поправкой на число тегов — ролик с тридцатью тегами не должен
 *    выигрывать только потому, что тегов много ([norm]);
 *  - качество: не просто число лайков (у старых роликов оно всегда больше), а ещё доля лайков от просмотров ([quality]);
 *  - явно не во вкусе (пропускал такое раз за разом) — убрать, если остаётся из чего выбирать ([dropDisliked]);
 *  - разнообразие: один автор не идёт подряд, соседние ролики не про одно и то же ([diversify]).
 */
object Ranker {
    /** Тегов больше стольких — оценка вкуса делится на корень из отношения. */
    private const val BASE_TAGS = 5.0
    /** Ниже этой сглаженной оценки ролик считается «не во вкусе». */
    const val DISLIKED = -1.2
    /** Сколько предыдущих роликов смотрим, чтобы не повторять автора. */
    private const val AUTHOR_WINDOW = 5
    private const val AUTHOR_PENALTY = 1.0
    private const val TAG_PENALTY = 0.6

    /** Поправка на число тегов: 1 для обычного ролика, меньше — для увешанного тегами. */
    fun norm(tags: Int): Double = 1.0 / sqrt(maxOf(tags.toDouble(), BASE_TAGS) / BASE_TAGS)

    /**
     * Качество 0…~1,6: популярность (логарифм лайков) и доля лайков от просмотров. 3 % лайков — хороший ролик; при
     * малом числе просмотров доля сглажена (десять просмотров и три лайка — ещё не шедевр).
     */
    fun quality(likes: Long, views: Long): Double {
        val l = likes.coerceAtLeast(0).toDouble()
        val pop = 0.08 * ln(1.0 + l)
        val ratio = if (views > 0) ((l + 1.0) / (views + 100.0) / 0.03).coerceAtMost(1.5) * 0.35 else 0.0
        return pop + ratio
    }

    /** Итоговая оценка для ленты: сглаженный вкус + качество + шум [noise] (0…1) для разведки. */
    fun value(taste: Double, tags: Int, likes: Long, views: Long, noise: Double): Double =
        Interests.damp(taste * norm(tags)) + quality(likes, views) + noise * 0.5

    /** Убрать явно не во вкусе ([DISLIKED]); но не больше половины списка — лента не должна пустеть. */
    fun <T> dropDisliked(items: List<Pair<T, Double>>, tasteOf: (T) -> Double): List<Pair<T, Double>> {
        val keep = items.filter { tasteOf(it.first) > DISLIKED }
        return if (keep.size * 2 >= items.size) keep else items
    }

    /**
     * Жадная перестановка: на каждое место — лучший из оставшихся с учётом штрафа за автора, который был среди
     * последних [AUTHOR_WINDOW], и за пересечение тегов с предыдущим роликом. Сильный ролик всё равно пройдёт,
     * просто не подряд с похожим.
     */
    fun diversify(scored: List<Pair<Gif, Double>>): List<Gif> {
        val left = scored.sortedByDescending { it.second }.toMutableList()
        val out = ArrayList<Gif>(left.size)
        while (left.isNotEmpty()) {
            val recent = out.takeLast(AUTHOR_WINDOW).map { it.user.lowercase() }.filter { it.isNotEmpty() }
            val prev = out.lastOrNull()?.tags?.map { it.lowercase() }?.toSet().orEmpty()
            var best = 0; var bestV = -Double.MAX_VALUE
            // кандидаты — верх списка: дальше оценки заведомо ниже, чем штраф может отыграть
            for (i in 0 until minOf(left.size, 24)) {
                val (g, s) = left[i]
                var v = s
                if (g.user.isNotEmpty() && g.user.lowercase() in recent) v -= AUTHOR_PENALTY
                if (prev.isNotEmpty() && g.tags.isNotEmpty()) {
                    val t = g.tags.map { it.lowercase() }.toSet()
                    v -= TAG_PENALTY * t.count { it in prev } / (t.size + prev.size - t.count { it in prev }).toDouble()
                }
                if (v > bestV) { bestV = v; best = i }
            }
            out += left.removeAt(best).first
        }
        return out
    }

    /**
     * Добавка к релевантности поиска (уровни 20/16/12/6/3 остаются главными): из одинаково подходящих выше тот, что
     * во вкусе и качественнее. Не больше ±1,5 — уровень не перепрыгнуть.
     */
    fun searchBonus(taste: Double, tags: Int, likes: Long, views: Long): Double =
        (0.5 * Interests.damp(taste * norm(tags))).coerceIn(-1.0, 1.0) + (0.3 * quality(likes, views)).coerceAtMost(0.5)
}
