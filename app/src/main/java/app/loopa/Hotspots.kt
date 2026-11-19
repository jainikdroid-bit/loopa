package app.loopa

/**
 * «Горячие моменты» роликов сети Aylo (Pornhub, YouPorn, RedTube, Tube8): на странице ролика `"hotspots":[…]` — сколько
 * раз смотрели каждый отрезок (по 5 с). Превью в сетке ([GridPreview]) крутит три самых горячих момента вместо
 * 10/50/80 %. Данные приходят со страницы ролика, которую и так открывает [Aylo.resolveMp4].
 *
 * Пики — не просто самые просматриваемые отрезки (к концу ролика смотрят всё меньше), а те, что выше соседей: значение
 * делится на медиану окна ±[WINDOW] отрезков. По одному пику в каждой из трёх частей ролика (начало, середина, конец —
 * [ZONES]); в части без выраженного пика (ниже [MIN_RATIO]) — её запасная точка 10/50/80 %.
 */
object Hotspots {
    private const val WINDOW = 8
    private const val MIN_RATIO = 1.04
    private val HOT = Regex(""""hotspots"\s*:\s*\[([^\]]*)\]""")

    /** Последние ~400 роликов (без android.util — чтобы чистая часть проверялась юнит-тестами). */
    private val memo = object : LinkedHashMap<String, IntArray>(64, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, IntArray>?) = size > 400
    }

    /** Просмотры по отрезкам со страницы ролика; пусто — данных нет. */
    fun parse(html: String): IntArray {
        val m = HOT.find(html) ?: return IntArray(0)
        return m.groupValues[1].split(',').mapNotNull { it.trim().trim('"').toLongOrNull()?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt() }.toIntArray()
    }

    /** Запомнить статистику ролика [id] («ph:…», «yp:…»). */
    fun put(id: String, h: IntArray) { if (h.size >= 10) synchronized(memo) { memo[id] = h } }

    /** Начала трёх кусков превью по горячим моментам ролика [id] длиной [d] мс (куски по [len] мс); null — данных нет. */
    fun cuts(id: String, d: Long, len: Long): LongArray? = synchronized(memo) { memo[id] }?.let { peaks(it, d, len) }

    /** Зоны ролика (доли, %) и запасная точка зоны, если выраженного пика в ней нет: начало, середина, конец. */
    private val ZONES = listOf(Triple(5, 30, 10), Triple(35, 65, 50), Triple(70, 95, 80))

    /**
     * Три куска по времени (мс, по возрастанию): в каждой зоне ([ZONES] — начало, середина, конец) — самый горячий
     * момент зоны; нет в зоне выраженного пика — её запасная точка (10/50/80 %). Так куски всегда разнесены по ролику и
     * раскрывают сюжет, а не идут подряд. null — статистики нет вовсе или ни в одной зоне нет пика. Чистая функция.
     */
    fun peaks(h: IntArray, d: Long, len: Long): LongArray? {
        val n = h.size
        if (n < 10 || d <= 0) return null
        val ratio = DoubleArray(n) { i ->
            val w = ((maxOf(0, i - WINDOW) until i) + (i + 1..minOf(n - 1, i + WINDOW))).map { h[it].toDouble() }.sorted()
            val med = if (w.isEmpty()) 0.0 else w[w.size / 2]
            if (med > 0) h[i] / med else 0.0
        }
        val last = (d - len - 200).coerceAtLeast(0)
        var anyPeak = false
        val at = ZONES.map { (from, to, anchor) ->
            val best = (n * from / 100..minOf(n - 1, n * to / 100)).maxByOrNull { ratio[it] }
            if (best != null && ratio[best] >= MIN_RATIO) {
                anyPeak = true
                // кусок начинается чуть раньше пика: момент «разгорается» на глазах
                (d * best / n - len / 3).coerceIn(0, last)
            } else (d * anchor / 100).coerceIn(0, last)
        }
        return if (anyPeak) at.sorted().toLongArray() else null
    }
}
