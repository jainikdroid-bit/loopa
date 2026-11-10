package app.loopa

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Превью для сетки: память (~1/8 памяти приложения, LRU) и диск (128 МБ…1 ГБ, кэш OkHttp [Net.img] — превью храним, даже
 * если сервер не разрешил). Картинка декодируется под ширину ячейки ([width]), а не целиком.
 *
 * Два потока работ. Видимое ([load]) — 6 потоков, очередь «последним пришёл — первым загружен»: на медленной сети
 * сначала то, что на экране сейчас. Прогноз ([prefetch]) — ещё 4 потока в порядке важности из [Forecaster]: то, что
 * окажется на экране после броска, грузится заранее и при прокрутке уже лежит в памяти; новый прогноз отменяет то, что
 * из него выпало. По HTTP/2 все запросы к одному серверу идут по одному соединению — на канале с пингом 800 мс
 * параллельность важнее числа соединений.
 */
object Thumbs {
    private class LifoQueue : LinkedBlockingDeque<Runnable>() { override fun offer(e: Runnable) = offerFirst(e) }
    private val pool = ThreadPoolExecutor(6, 6, 30, TimeUnit.SECONDS, LifoQueue())
    private val ahead = ThreadPoolExecutor(4, 4, 30, TimeUnit.SECONDS, LinkedBlockingDeque())
    private val ui = Handler(Looper.getMainLooper())
    private val cache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 8).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    /** Ширина, под которую декодировать, px (ширина ячейки сетки; задаёт экран). */
    @Volatile var width = 360
    /** Что сейчас грузится (видимым или прогнозом) — один адрес не качать дважды. */
    private val inFlight = ConcurrentHashMap.newKeySet<String>()
    /** Номер прогноза: работа из устаревшего прогноза пропускается. */
    @Volatile private var gen = 0
    @Volatile private var wanted: Set<String> = emptySet()

    /** Картинка уже в памяти — ячейка показана, можно запускать живое превью. */
    fun has(url: String) = url.isEmpty() || cache.get(url) != null

    fun load(iv: ImageView, url: String) {
        // та же картинка уже на месте (ячейку перепривязали к тому же ролику) — не трогать: иначе мигает заглушкой
        if (iv.tag == url && iv.drawable != null) return
        iv.tag = url
        cache.get(url)?.let { fromMem.incrementAndGet(); iv.setImageBitmap(it); return }
        iv.setImageDrawable(null)
        pool.execute {
            if (iv.tag != url) return@execute       // ячейку уже переиспользовали
            var bm = cache.get(url) ?: runCatching { Net.retry { if (iv.tag != url) throw IllegalStateException("не нужна"); fetch(url) } }.getOrNull()
            // её уже грузит прогноз — дождаться его, а не качать второй раз
            val end = System.currentTimeMillis() + 30_000
            while (bm == null && url in inFlight && iv.tag == url && System.currentTimeMillis() < end) { Thread.sleep(50); bm = cache.get(url) }
            if (bm == null && iv.tag == url) bm = runCatching { Net.retry { fetch(url) } }.getOrNull()   // прогноз не смог — сами
            if (bm == null) return@execute
            ui.post { if (iv.tag == url) iv.setImageBitmap(bm) }
        }
    }

    /**
     * Заранее загрузить в память и на диск картинки прогноза — в порядке важности. Прежний прогноз отменяется: его
     * ещё не начатые картинки, которых нет в новом, пропускаются.
     */
    fun prefetch(urls: List<String>) {
        val list = urls.filter { it.isNotEmpty() && cache.get(it) == null }
        val g = ++gen; wanted = list.toHashSet()
        list.forEach { u ->
            ahead.execute {
                // видимое — первым: пока на экране что-то грузится, прогноз ждёт (на медленном канале не делит его)
                while (pool.activeCount > 0 || pool.queue.isNotEmpty()) {
                    if (gen != g && u !in wanted) return@execute
                    Thread.sleep(30)
                }
                if (gen != g && u !in wanted) return@execute   // прогноз сменился, а в новом её нет
                if (cache.get(u) != null || u in inFlight) return@execute
                runCatching { fetch(u) }
            }
        }
    }

    /** Грузятся картинки, которые сейчас на экране, — видео заранее пусть подождёт ([Prefetch]). */
    val busy get() = pool.activeCount > 0 || pool.queue.isNotEmpty()
    /** Грузятся любые картинки — и на экране, и про запас: видео из сети начинаем после них. */
    val loading get() = busy || ahead.activeCount > 0 || ahead.queue.isNotEmpty()

    /** Счётчики для журнала: картинки из памяти, с диска, из сети (с запуска). */
    private val fromMem = java.util.concurrent.atomic.AtomicInteger()
    private val fromDisk = java.util.concurrent.atomic.AtomicInteger()
    private val fromNet = java.util.concurrent.atomic.AtomicInteger()
    fun stats() = "картинки: из памяти ${fromMem.get()}, с диска ${fromDisk.get()}, из сети ${fromNet.get()}"

    /** Отменить ещё не начатое из прогноза (ушли в плеер — канал ролику). */
    fun stopPrefetch() { gen++; wanted = emptySet() }

    /** Загрузить и декодировать под [width]; в память. Повторы — снаружи. */
    private fun fetch(url: String): Bitmap? {
        cache.get(url)?.let { return it }
        if (!inFlight.add(url)) return null
        try {
            val rq = okhttp3.Request.Builder().url(url).header("User-Agent", Api.UA).build()
            val bytes = Net.img.newCall(rq).execute().use { r ->
                if (!r.isSuccessful) throw HttpError(r.code)
                (if (r.networkResponse == null) fromDisk else fromNet).incrementAndGet()
                r.body?.bytes() ?: return null
            }
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
            var s = 1; val w = width.coerceAtLeast(64)
            while (o.outWidth / (s * 2) >= w) s *= 2
            val bm = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = s }) ?: return null
            cache.put(url, bm)
            return bm
        } finally { inFlight.remove(url) }
    }
}
