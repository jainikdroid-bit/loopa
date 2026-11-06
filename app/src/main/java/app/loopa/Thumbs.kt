package app.loopa

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Превью для сетки: 4 потока, кэш в памяти (~1/8 памяти приложения) и на диске (HTTP-кэш [Net]), ImageView помнит,
 * какой адрес ждёт. Очередь — последним пришёл, первым загружен: на медленной сети сначала то, что на экране сейчас,
 * а не то, что пролистали. Сбой — повтор ([Net.retry]), пока ячейка ещё ждёт эту картинку.
 */
object Thumbs {
    private val pool = ThreadPoolExecutor(4, 4, 30, TimeUnit.SECONDS, object : LinkedBlockingDeque<Runnable>() {
        override fun offer(e: Runnable) = offerFirst(e)
    })
    private val ui = Handler(Looper.getMainLooper())
    private val cache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 8).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    fun load(iv: ImageView, url: String) {
        iv.tag = url
        cache.get(url)?.let { iv.setImageBitmap(it); return }
        iv.setImageDrawable(null)
        pool.execute {
            if (iv.tag != url) return@execute       // ячейку уже переиспользовали
            val bm = runCatching { Net.retry {
                if (iv.tag != url) throw IllegalStateException("не нужна")
                val c = Net.open(url)
                try {
                    if (c.responseCode !in 200..299) throw Net.fail(c)
                    val bytes = c.inputStream.readBytes()
                    // превью до ~360 px по ширине — сетка в 2-3 столбца
                    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
                    var s = 1; while (o.outWidth / (s * 2) >= 360) s *= 2
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = s })
                } finally { c.disconnect() }
            } }.getOrNull() ?: return@execute
            cache.put(url, bm)
            ui.post { if (iv.tag == url) iv.setImageBitmap(bm) }
        }
    }
}
