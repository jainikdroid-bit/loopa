package app.loopa

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.LruCache
import android.widget.ImageView
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** UI-owned bounded scheduler. HTTP and bitmap decoding run on workers; no worker waits for another. */
object Thumbs {
    private const val VISIBLE_WORKERS = 6
    private const val AHEAD_WORKERS = 2
    private const val MAX_PENDING = 64
    private const val MAX_BYTES = 8 * 1024 * 1024
    private const val MAX_PIXELS = 4_000_000L
    private val pool = Executors.newFixedThreadPool(VISIBLE_WORKERS + AHEAD_WORKERS)
    private val cancelPool = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())
    private val cache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 8).coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    @Volatile var width = 360
    private val queue = GridWorkQueue<String>(MAX_PENDING)
    private class Job(val url: String) {
        val views = LinkedHashSet<ImageView>()
        var running = false
        var speculative = false
        @Volatile var cancelled = false
        @Volatile var call: okhttp3.Call? = null
    }
    private val jobs = HashMap<String, Job>()
    private val bindings = HashMap<ImageView, Job>()
    private var wanted: Set<String> = emptySet()
    private var activeVisible = 0
    private var activeAhead = 0
    @Volatile private var visibleBusy = false
    @Volatile private var allBusy = false
    private val retryAfter = LinkedHashMap<String, Long>()
    private val fromMem = AtomicInteger()
    private val fromDisk = AtomicInteger()
    private val fromNet = AtomicInteger()
    private val failures = AtomicInteger()
    private val cancellations = AtomicInteger()
    private val evicted = AtomicInteger()
    private val peakPending = AtomicInteger()
    private val client by lazy { Net.img.newBuilder().callTimeout(18, TimeUnit.SECONDS).build() }

    fun has(url: String) = url.isEmpty() || cache.get(url) != null
    val busy get() = visibleBusy
    val loading get() = allBusy
    private fun publishBusy() {
        visibleBusy = activeVisible > 0 || jobs.values.any { it.views.isNotEmpty() }
        allBusy = visibleBusy || activeAhead > 0 || queue.size > 0
    }

    fun load(iv: ImageView, url: String) {
        if (iv.tag == url && iv.drawable != null) return
        val previous = bindings[iv]
        if (previous?.url == url && !previous.cancelled) return
        stop(iv)
        if (iv.tag != url) iv.setImageDrawable(null)
        iv.tag = url
        if (url.isBlank()) return
        cache.get(url)?.let { fromMem.incrementAndGet(); iv.setImageBitmap(it); return }
        if (SystemClock.elapsedRealtime() < (retryAfter[url] ?: 0L)) return
        val job = jobs.getOrPut(url) { Job(url) }
        job.views.add(iv)
        bindings[iv] = job
        if (!job.running) enqueue(job, true)
        pump()
    }

    fun stop(iv: ImageView) {
        val job = bindings.remove(iv) ?: return
        job.views.remove(iv)
        if (job.views.isEmpty() && job.url !in wanted) cancel(job)
        publishBusy()
    }

    fun prefetch(urls: List<String>) {
        wanted = urls.asSequence().filter { it.isNotBlank() }.distinct().take(MAX_PENDING).toSet()
        jobs.values.toList().filter { it.views.isEmpty() && it.url !in wanted }.forEach(::cancel)
        wanted.forEach { url ->
            if (cache.get(url) == null && SystemClock.elapsedRealtime() >= (retryAfter[url] ?: 0L)) {
                val job = jobs.getOrPut(url) { Job(url) }
                if (!job.running && job.views.isEmpty()) enqueue(job, false)
            }
        }
        pump()
    }

    fun stopPrefetch() {
        wanted = emptySet()
        jobs.values.toList().filter { it.views.isEmpty() }.forEach(::cancel)
        publishBusy()
    }

    private fun enqueue(job: Job, visible: Boolean) {
        queue.offer(job.url, visible).forEach { key ->
            jobs[key]?.let { evicted.incrementAndGet(); cancel(it) }
        }
        peakPending.updateAndGet { maxOf(it, queue.size) }
    }

    private fun cancel(job: Job) {
        if (jobs[job.url] !== job) return
        jobs.remove(job.url)
        queue.remove(job.url)
        job.views.forEach { if (bindings[it] === job) bindings.remove(it) }
        job.views.clear()
        job.cancelled = true
        cancellations.incrementAndGet()
        job.call?.let { call -> cancelPool.execute { call.cancel() } }
    }

    private fun pump() {
        while (activeVisible < VISIBLE_WORKERS) {
            val key = queue.poll(true) ?: break
            val job = jobs[key] ?: continue
            if (job.views.isEmpty()) {
                if (job.url in wanted) enqueue(job, false) else cancel(job)
                continue
            }
            start(job, false)
        }
        while (activeAhead < AHEAD_WORKERS && activeVisible == 0 && jobs.values.none { it.views.isNotEmpty() }) {
            val key = queue.poll(false) ?: break
            val job = jobs[key] ?: continue
            if (job.url !in wanted) { cancel(job); continue }
            start(job, true)
        }
        publishBusy()
    }

    private fun start(job: Job, speculative: Boolean) {
        job.running = true
        job.speculative = speculative
        if (speculative) activeAhead++ else activeVisible++
        val target = width.coerceIn(64, 1024)
        pool.execute {
            var bitmap: Bitmap? = null
            try {
                if (!job.cancelled) bitmap = cache.get(job.url) ?: fetch(job, target)
            } catch (_: Exception) {
                // Leave the placeholder unchanged on failure.
            } finally {
                ui.post { finish(job, bitmap) }
            }
        }
    }

    private fun finish(job: Job, bitmap: Bitmap?) {
        if (job.speculative) activeAhead-- else activeVisible--
        if (jobs[job.url] === job) {
            jobs.remove(job.url)
            if (!job.cancelled && bitmap != null) {
                cache.put(job.url, bitmap)
                retryAfter.remove(job.url)
            } else if (!job.cancelled) {
                failures.incrementAndGet()
                retryAfter.remove(job.url)
                retryAfter[job.url] = SystemClock.elapsedRealtime() + 3_000L
                while (retryAfter.size > 256) retryAfter.remove(retryAfter.keys.first())
            }
            job.views.forEach { view ->
                if (bindings[view] === job) {
                    bindings.remove(view)
                    if (!job.cancelled && bitmap != null && view.tag == job.url)
                        view.setImageBitmap(bitmap)
                }
            }
            job.views.clear()
        }
        pump()
    }

    private fun fetch(job: Job, target: Int): Bitmap? {
        val rq = okhttp3.Request.Builder().url(job.url).header("User-Agent", Api.UA).build()
        val call = client.newCall(rq)
        job.call = call
        if (job.cancelled) { call.cancel(); return null }
        val bytes = call.execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body ?: return null
            if (body.contentLength() > MAX_BYTES) return null
            (if (response.networkResponse == null) fromDisk else fromNet).incrementAndGet()
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            body.byteStream().use { input ->
                while (!job.cancelled) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    if (out.size() + n > MAX_BYTES) return null
                    out.write(buffer, 0, n)
                }
            }
            if (job.cancelled) return null
            out.toByteArray()
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || job.cancelled) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= target && sample < (1 shl 20)) sample *= 2
        while ((bounds.outWidth.toLong() / sample) * (bounds.outHeight.toLong() / sample) > MAX_PIXELS && sample < (1 shl 20)) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    fun stats() = "images: memory=" + fromMem.get() + " disk=" + fromDisk.get() + " network=" + fromNet.get() +
        " failed=" + failures.get() + " cancelled=" + cancellations.get() + " evicted=" + evicted.get() + " pending_peak=" + peakPending.get()
}
