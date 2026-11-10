package app.loopa

/**
 * Прогноз прокрутки сетки (как в Instagram): по скорости и докуда долетит бросок понять, что окажется на экране в
 * ближайшие секунды, и заранее загрузить это — сначала картинки, потом начало видео там, где лента остановится.
 * Без Android — проверяется юнит-тестами.
 *
 * @param stop ячейки, которые будут на экране, когда прокрутка остановится
 * @param thumbs картинки по порядку важности: место остановки, запас за ним по ходу, затем то, что пролетит
 * @param video ячейки, у которых грузить начало видео (место остановки, ближние к центру первыми), не больше бюджета
 * @param more пора просить следующую страницу ленты
 */
data class Forecast(val stop: IntRange, val thumbs: List<Int>, val video: List<Int>, val more: Boolean)

object Forecaster {
    /** Без броска смотрим вперёд на столько: куда долистают за это время при текущей скорости. */
    const val HORIZON_MS = 2_000L
    /** Запас за местом остановки, экранов. */
    const val MARGIN_SCREENS = 1
    /** Следующую страницу ленты — когда до конца загруженного осталось меньше стольких экранов (запрос идёт секунды). */
    const val MORE_SCREENS = 3
    /** Больше этого картинок за раз не заказываем (очень длинный бросок). */
    const val MAX_THUMBS = 120

    /**
     * @param first первая видимая ячейка, [last] — последняя
     * @param cols столбцов в сетке
     * @param rowPx высота ряда, px
     * @param viewPx высота видимой области, px
     * @param velocityPxS скорость прокрутки, px/с (+ — вниз по ленте)
     * @param flingPx докуда долетит бросок от текущего места, px (0 — броска нет, тогда по скорости за [HORIZON_MS])
     * @param count сколько ячеек загружено
     * @param videoBudget у скольких ячеек грузить начало видео (по сети: 0 — нисколько)
     */
    fun forecast(
        first: Int, last: Int, cols: Int, rowPx: Int, viewPx: Int,
        velocityPxS: Float, flingPx: Int, count: Int, videoBudget: Int,
    ): Forecast {
        if (count <= 0 || first < 0 || last < first || cols <= 0 || rowPx <= 0) return Forecast(IntRange.EMPTY, emptyList(), emptyList(), count == 0)
        val travel = if (flingPx != 0) flingPx.toLong() else (velocityPxS * HORIZON_MS / 1000f).toLong()
        val dir = if (travel < 0) -1 else 1
        val rows = (travel / rowPx).toInt()
        val screenRows = maxOf(1, (viewPx + rowPx - 1) / rowPx)
        val maxPos = count - 1
        // место остановки: видимое окно, сдвинутое на пролёт (целыми рядами), в пределах ленты
        val stopFirst = (first + rows * cols).coerceIn(0, maxPos)
        val stopLast = (last + rows * cols).coerceIn(0, maxPos)
        val stop = stopFirst..stopLast
        val margin = MARGIN_SCREENS * screenRows * cols
        val out = LinkedHashSet<Int>()
        stop.forEach { out += it }
        // запас за остановкой по ходу
        if (dir > 0) (stopLast + 1..minOf(maxPos, stopLast + margin)).forEach { out += it }
        else (stopFirst - 1 downTo maxOf(0, stopFirst - margin)).forEach { out += it }
        // то, что пролетит: от остановки назад к текущему месту (ближнее к остановке — важнее)
        if (dir > 0) (stopFirst - 1 downTo last + 1).forEach { out += it }
        else (stopLast + 1..first - 1).forEach { out += it }
        val thumbs = out.filter { it in 0..maxPos }.take(MAX_THUMBS)
        // видео: место остановки, ближние к центру — первыми
        val mid = (stopFirst + stopLast) / 2.0
        val video = stop.sortedBy { kotlin.math.abs(it - mid) }.take(maxOf(0, videoBudget))
        val more = dir > 0 && stopLast + MORE_SCREENS * screenRows * cols >= maxPos
        return Forecast(stop, thumbs, video, more)
    }

    /** У скольких ячеек грузить начало видео заранее — по сети: быстрая — у двух рядов, лимитная — у двух ячеек. */
    fun videoBudget(net: NetKind, cols: Int): Int = when (net) {
        NetKind.FAST -> 2 * cols
        NetKind.METERED -> 2
        NetKind.SLOW, NetKind.SAVER, NetKind.OFFLINE -> 0
    }

    /**
     * Сколько картинок прогноза заказывать — бюджет на канал ~5 Мбит/с (картинка ~30–60 КБ): быстрая — 6 экранов,
     * лимитная и медленная — 3 (картинки — первыми и про запас, видео — после них), экономия трафика и нет сети — только экран вперёд. [screen] — ячеек на экране.
     */
    fun thumbBudget(net: NetKind, screen: Int): Int = when (net) {
        NetKind.FAST -> 6 * screen
        NetKind.METERED, NetKind.SLOW -> 3 * screen
        NetKind.SAVER, NetKind.OFFLINE -> screen
    }.coerceAtMost(MAX_THUMBS)

    /** Сколько байт начала видео класть в кэш на ячейку: SD RedGIFs ~1 Мбит/с, 384 КБ ≈ 3 с. */
    fun videoBytes(net: NetKind): Long = when (net) {
        NetKind.FAST -> 384L shl 10
        NetKind.METERED -> 192L shl 10
        else -> 0L
    }
}
