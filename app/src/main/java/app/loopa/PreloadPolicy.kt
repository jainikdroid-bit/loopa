package app.loopa

/** Какая сеть сейчас — для предзагрузки соседних роликов. */
enum class NetKind {
    /** Wi-Fi/проводная без лимита, скорость в норме. */
    FAST,
    /** Мобильная или иная лимитная сеть. */
    METERED,
    /** Медленная (оценка < 3 Мбит/с или недавно подвисали — [Video.slow]). */
    SLOW,
    /** Экономия трафика (Data Saver) включена для приложения на лимитной сети. */
    SAVER,
    /** Сети нет. */
    OFFLINE,
}

/** Что сделать с соседним роликом. */
sealed class Plan {
    /** Ничего (и отменить начатое). */
    object None : Plan()
    /** Только открыть источник (заголовок файла), без видео. */
    object Prepare : Plan()
    /** Загрузить первые [ms] миллисекунд. */
    data class Range(val ms: Long) : Plan()
}

/**
 * Правила умной предзагрузки и размера кэша — без Android, чтобы проверять юнит-тестами.
 *
 * Грузим только по ходу листания и только начало (секунды, а не файл): быстрая сеть — 2 следующих (3 с и 2 с; в
 * расчёте на канал ~5 Мбит/с, где ролики идут в SD ≈ 1 Мбит/с — это 0,4 и 0,25 МБ), лимитная — 1 следующий на 1,5 с, медленная — 1 на 1,5 с (на пинге ~800 мс меньше не окупает запроса), экономия трафика и нет сети — ничего.
 * Листают быстро (несколько страниц подряд меньше чем за [FLING_MS]) — только следующий: промежуточные всё равно
 * пролетят. Предыдущий на быстрой сети — только заголовок: данные и так в дисковом кэше.
 */
object PreloadPolicy {
    /** Страница на экране меньше этого — значит, листают, не смотря. */
    const val FLING_MS = 900L
    /** Сколько последних смен страниц смотреть, чтобы понять «листают быстро». */
    private const val FLING_PAGES = 2

    /** Сколько роликов вперёд по ходу листания. */
    fun depth(net: NetKind, flinging: Boolean): Int = when (net) {
        NetKind.SAVER, NetKind.OFFLINE -> 0
        NetKind.SLOW, NetKind.METERED -> 1
        NetKind.FAST -> if (flinging) 1 else 2
    }

    /** Сколько миллисекунд начала грузить у ролика, который [ahead]-й по ходу листания (1 — следующий). */
    fun rangeMs(net: NetKind, ahead: Int): Long = when (net) {
        NetKind.FAST -> if (ahead == 1) 3_000L else 2_000L
        NetKind.METERED -> 1_500L
        NetKind.SLOW -> 1_500L
        NetKind.SAVER, NetKind.OFFLINE -> 0L
    }

    /**
     * План для ролика на расстоянии [offset] от текущего (номер в ленте минус номер текущего); [dir] — направление
     * листания (+1 вниз по ленте, −1 назад). Текущий ролик сюда не попадает — им занят плеер.
     */
    fun plan(offset: Int, dir: Int, net: NetKind, flinging: Boolean): Plan {
        if (offset == 0) return Plan.None
        val ahead = offset * (if (dir < 0) -1 else 1)
        val depth = depth(net, flinging)
        return when {
            ahead in 1..depth -> Plan.Range(rangeMs(net, ahead))
            ahead == -1 && net == NetKind.FAST && !flinging -> Plan.Prepare
            else -> Plan.None
        }
    }

    /** Листают быстро: последние [FLING_PAGES] страниц были на экране меньше [FLING_MS] каждая. */
    fun flinging(dwellsMs: List<Long>): Boolean =
        dwellsMs.size >= FLING_PAGES && dwellsMs.takeLast(FLING_PAGES).all { it < FLING_MS }

    const val MB = 1L shl 20
    /** Варианты размера кэша видео в меню, МБ; 0 — авто. */
    val CACHE_CHOICES = listOf(0, 512, 1024, 2048)

    /**
     * Размер дискового кэша видео: [choiceMb] из меню или авто (0) — 5 % свободного места, но 512 МБ…1 ГБ. Всегда не
     * больше четверти свободного места (вместе с уже занятым кэшем — [usedBytes]) и не меньше 64 МБ.
     */
    fun cacheBytes(choiceMb: Int, freeBytes: Long, usedBytes: Long = 0): Long {
        val room = (freeBytes + usedBytes) / 4
        val want = if (choiceMb > 0) choiceMb * MB else (freeBytes / 20).coerceIn(512 * MB, 1024 * MB)
        return minOf(want, room).coerceAtLeast(64 * MB)
    }
}
