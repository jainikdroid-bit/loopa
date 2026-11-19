package app.loopa

/**
 * «Горячие моменты» роликов сети Aylo (Pornhub, YouPorn, RedTube, Tube8): на странице ролика `"hotspots":[…]` — сколько
 * раз смотрели каждый отрезок (по 5 с). Превью в сетке ([GridPreview]) крутит три самых горячих момента вместо
 * 10/50/80 %. Данные приходят со страницы ролика, которую и так открывает [Aylo.resolveMp4].
 *
 * Пики — не просто самые просматриваемые отрезки (к концу ролика смотрят всё меньше), а те, что выше соседей: значение
 * делится на медиану окна ±[WINDOW] отрезков; крайние [EDGE_PCT] % в начале и в конце не берём; между кусками — не
 * меньше [MIN_GAP_PCT] % длины. Слабые пики (ниже [MIN_RATIO]) — данных нет, остаются 10/50/80 %.
 */
object Hotspots {
    private const val WINDOW = 8
    private const val EDGE_PCT = 5
    private const val MIN_GAP_PCT = 12
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

    /** Три пика по времени (мс, по возрастанию) или null, если выраженных пиков нет. Чистая функция — для тестов. */
    fun peaks(h: IntArray, d: Long, len: Long): LongArray? {
        val n = h.size
        if (n < 10 || d <= 0) return null
        val ratio = DoubleArray(n) { i ->
            val w = ((maxOf(0, i - WINDOW) until i) + (i + 1..minOf(n - 1, i + WINDOW))).map { h[it].toDouble() }.sorted()
            val med = if (w.isEmpty()) 0.0 else w[w.size / 2]
            if (med > 0) h[i] / med else 0.0
        }
        val lo = n * EDGE_PCT / 100; val hi = n - 1 - n * EDGE_PCT / 100
        val gap = maxOf(1, n * MIN_GAP_PCT / 100)
        val picked = ArrayList<Int>()
        for (i in (lo..hi).sortedByDescending { ratio[it] }) {
            if (ratio[i] < MIN_RATIO) break
            if (picked.all { kotlin.math.abs(it - i) >= gap }) picked += i
            if (picked.size == 3) break
        }
        if (picked.size < 3) return null
        val last = (d - len - 200).coerceAtLeast(0)
        // кусок начинается чуть раньше пика: момент «разгорается» на глазах
        return picked.sorted().map { (d * it / n - len / 3).coerceIn(0, last) }.toLongArray()
    }
}
