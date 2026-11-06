package app.loopa

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.http.HttpResponseCache
import android.os.Handler
import android.os.Looper
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Ответ сервера с кодом ошибки: 429 и 5xx — временные (повторяем), остальные — нет. */
class HttpError(val code: Int, msg: String = "HTTP $code") : IOException(msg) {
    val transient get() = code == 429 || code >= 500
    var retryAfterMs = 0L
}

/**
 * Сеть для плохих каналов: повторы с паузой при обрывах, таймаутах и 429/5xx; дисковый HTTP-кэш (превью и ответы, которые
 * сервер разрешает кэшировать, — второй раз без сети); оповещение «связь вернулась» — лента и плеер сразу повторяют.
 */
object Net {
    const val CONNECT_MS = 15_000      // высокий пинг: соединение через спутник/роуминг может идти секунды
    const val READ_MS = 30_000
    private const val ATTEMPTS = 4
    private val ui = Handler(Looper.getMainLooper())
    /** Слушатели «появилась сеть» — вызываются на главном потоке. */
    val online = LinkedHashSet<() -> Unit>()
    private var inited = false

    fun init(ctx: Context) {
        if (inited) return
        inited = true
        val app = ctx.applicationContext
        runCatching { HttpResponseCache.install(app.cacheDir.resolve("http"), 64L shl 20) }
        val cm = app.getSystemService(ConnectivityManager::class.java)
        runCatching {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(n: Network) { ui.post { online.toList().forEach { it() } } }
            })
        }
    }

    /** Ошибка, которую стоит повторить: обрыв, таймаут, 429, 5xx. */
    fun transient(e: Throwable) = e is HttpError && e.transient || e is IOException && e !is HttpError

    /** Выполнить с повторами: пауза 1, 2, 4 с (или сколько просит сервер в Retry-After, но не больше 10 с). */
    fun <T> retry(block: () -> T): T {
        var wait = 1000L
        repeat(ATTEMPTS - 1) {
            try { return block() } catch (e: Exception) {
                if (!transient(e)) throw e
                Thread.sleep((e as? HttpError)?.retryAfterMs?.takeIf { it > 0 }?.coerceAtMost(10_000) ?: wait)
                wait *= 2
            }
        }
        return block()
    }

    /** GET/запрос с общими таймаутами; ошибка HTTP — [HttpError]. */
    fun open(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = CONNECT_MS; readTimeout = READ_MS
        setRequestProperty("User-Agent", Api.UA)
    }

    fun fail(c: HttpURLConnection): HttpError = HttpError(c.responseCode).apply {
        retryAfterMs = (c.getHeaderField("Retry-After")?.toLongOrNull() ?: 0) * 1000
    }
}
