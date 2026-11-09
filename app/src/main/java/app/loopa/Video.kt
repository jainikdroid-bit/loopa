package app.loopa

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.common.C
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy

/**
 * Общее для плеера: дисковый кэш 300 МБ (назад — без повторной закачки; его же использует PreloadManager для
 * соседних роликов) и оценка скорости сети для качества «Авто».
 */
@OptIn(UnstableApi::class)
object Video {
    lateinit var cache: SimpleCache; private set
    lateinit var meter: DefaultBandwidthMeter; private set
    /** Источник данных: сеть через кэш; скорость закачки идёт в meter. */
    lateinit var source: CacheDataSource.Factory; private set

    /**
     * Сколько ждать байтов от сервера видео, прежде чем считать соединение зависшим. Общие [Net.READ_MS] (30 с) — для
     * API; у видео зависшее соединение (CDN перестал слать, канал «залип») держало бы «загрузку» по полминуты. Через 8 с
     * без единого байта плеер рвёт его и докачивает с того же места (Range) — повтор быстрее ожидания.
     */
    const val READ_MS = 8_000

    fun init(ctx: Context) {
        if (::cache.isInitialized) return
        val app = ctx.applicationContext
        cache = SimpleCache(app.cacheDir.resolve("video"), LeastRecentlyUsedCacheEvictor(300L shl 20), StandaloneDatabaseProvider(app))
        meter = DefaultBandwidthMeter.Builder(app).build()
        val http = DefaultHttpDataSource.Factory().setUserAgent(Api.UA).setConnectTimeoutMs(Net.CONNECT_MS).setReadTimeoutMs(READ_MS)
            .setAllowCrossProtocolRedirects(true).setTransferListener(meter)
        // Erome отдаёт видео только с Referer своего сайта (без него 403)
        val ref = ResolvingDataSource.Factory(http) { spec ->
            if (Erome.ours(spec.uri.toString())) spec.withAdditionalHeaders(mapOf("Referer" to Erome.REFERER)) else spec }
        source = CacheDataSource.Factory().setCache(cache).setUpstreamDataSourceFactory(ref).setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }

    /**
     * Ошибки загрузки видео: обрывы и таймауты — повторять (до 10 попыток, пауза 1…5 с); ответ сервера 4xx (ролик удалён,
     * ссылка истекла, нет доступа) — сразу ошибка: повторы его не исправят, а «загрузка…» висела бы под минуту.
     */
    val errors: LoadErrorHandlingPolicy = object : DefaultLoadErrorHandlingPolicy(10) {
        override fun getRetryDelayMsFor(info: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
            var e: Throwable? = info.exception
            while (e != null) {
                if (e is HttpDataSource.InvalidResponseCodeException) {
                    val c = e.responseCode
                    if (c in 400..499 && c != 408 && c != 429) return C.TIME_UNSET
                }
                e = e.cause
            }
            return super.getRetryDelayMsFor(info)
        }
    }

    /** «Авто»: HD, если сеть тянет ~2 битрейта HD (RedGIFs HD ≈ 4–5 Мбит/с), иначе SD (в 5–6 раз легче). */
    fun auto(g: Gif) = if (!sdOnly && meter.bitrateEstimate >= 9_000_000) g.hd else g.sd

    /** До какого времени «Авто» держит SD: ролик подвис на HD — сеть не тянет, 10 минут не пробуем HD снова. */
    private var sdUntil = 0L
    val sdOnly get() = System.currentTimeMillis() < sdUntil
    fun stalled() { sdUntil = System.currentTimeMillis() + 10 * 60_000L }

    /** Медленная сеть (< 3 Мбит/с или недавно подвисали): предзагрузка соседей скромнее, чтобы не отнимать канал у текущего. */
    val slow get() = sdOnly || meter.bitrateEstimate < 3_000_000
}
