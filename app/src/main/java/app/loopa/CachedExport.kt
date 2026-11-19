package app.loopa

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.ContentMetadata
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Досмотренные ролики, что целиком лежат в дисковом кэше, переносятся в обычный файл в папке пользователя: по умолчанию
 * Загрузки/AdultSpace/cached videos (Android 10+ — MediaStore, без разрешений; 8–9 — публичная папка Загрузок,
 * WRITE_EXTERNAL_STORAGE) или своя папка (SAF, [pickFolderIntent]). Перенос, а не копия: когда файл записан и проверен
 * (размер), ролик убирается из кэша — он хранится в одном месте, а плеер и превью играют из файла ([localUri], [hasUrl]).
 *
 * mp4 — копируется как есть; HLS (Reddit, xHamster, FikFap), если все сегменты выбранного варианта в кэше, — склеивается в
 * mp4 без перекодирования ([HlsPack]; не вышло и это обычные TS-сегменты — отдаётся .ts). Всё — в фоне, по одному, только
 * после ухода с ролика ([touch]/[sweep]); кэш чистится лишь после успешной записи и не у ролика на экране ([playing]).
 * Частичное и превью остаются в кэше. Индекс «id → файл» — в настройках: дублей нет.
 *
 * В папке лежит пустой `.nomedia`: галерея и медиасканер не показывают эти ролики среди личных (файловый менеджер и
 * сторонние плееры открывают их как обычно). Размер сохранённого считается вместе с дисковым кэшем видео и вместе
 * укладывается в предел кэша ([Video.cacheLimit]): сверх него удаляется самое давнее по последнему просмотру среди
 * всего — и кусков кэша, и файлов ([CacheReaper], [StorageBudget]).
 */
@OptIn(UnstableApi::class)
object CachedExport {
    private const val PREFS = "loopa"
    private const val K_ON = "cx_enabled"
    private const val K_TREE = "cx_tree"
    private const val K_INDEX = "cx_index"
    private const val DEFAULT_DIR = "AdultSpace/cached videos"
    private const val SEEN_MAX = 300
    private const val VERIFY_MS = 30_000L
    private const val K_ERR = "cx_error"
    private const val SAVE_DELAY_MS = 2_000L
    /** HLS больше этого не склеиваем (склейка идёт через временные файлы) — остаётся в кэше. */
    private const val HLS_MAX = 300L shl 20
    private const val NOMEDIA = ".nomedia"
    private const val OCTET = "application/octet-stream"
    private const val RW = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION

    private lateinit var app: Context
    private val prefs get() = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val pool = Executors.newSingleThreadExecutor { r -> Thread(r, "cached-export").apply { priority = Thread.MIN_PRIORITY; isDaemon = true } }
    /** Просмотренные в этом запуске (id → ролик) — их проверяет [sweep]; в работе, неудавшиеся и ждущие проверки HLS. */
    private val seen = LinkedHashMap<String, Gif>()
    private val busy = HashSet<String>()
    private val failed = HashSet<String>()
    private val dirty = HashSet<String>()
    /** Записанные, но ещё не убранные из кэша (ролик был на экране): id → ключи кэша. */
    private val pending = HashMap<String, List<String>>()
    private val verified = ConcurrentHashMap<String, Long>()
    /** Фоновая проверка, что сохранённый файл на месте: на главном потоке binder и диск не трогаем. */
    private val checker = Executors.newSingleThreadExecutor { r -> Thread(r, "cached-export-check").apply { priority = Thread.MIN_PRIORITY; isDaemon = true } }
    private val checking = ConcurrentHashMap.newKeySet<String>()
    /** Отложенная запись индекса: пачка изменений — одна запись. */
    private val saver = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "cached-export-save").apply { priority = Thread.MIN_PRIORITY; isDaemon = true } }
    private val saveQueued = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile private var noMediaKey: String? = null
    @Volatile private var errorShown = false

    /** Сколько занимают сохранённые ролики, байт (по индексу, в памяти): входит в общий предел с кэшем видео. */
    @Volatile var savedTotal = 0L; private set

    /** Ролик на экране плеера — его кэш не трогаем (null — плеер закрыт). */
    @Volatile var playing: String? = null

    /** Повторный вызов безвреден. Звать из экранов, что пользуются объектом (плеер, настройки, [FolderPickActivity]). */
    @Synchronized fun init(ctx: Context) {
        if (::app.isInitialized) return
        app = ctx.applicationContext
        index()   // индекс в память: savedTotal нужен общему пределу сразу
        // временные файлы склейки HLS прошлого запуска (процесс убили посреди работы) — убрать
        pool.execute { runCatching { app.cacheDir.resolve("cx").deleteRecursively() } }
    }

    /** Сохранять ли досмотренные ролики (по умолчанию да). */
    var enabled: Boolean
        get() = prefs.getBoolean(K_ON, true)
        set(v) { prefs.edit().putBoolean(K_ON, v).apply() }

    private val tree: Uri? get() = prefs.getString(K_TREE, null)?.let(Uri::parse)

    /** Куда сохраняем, для показа в настройках: «Загрузки/AdultSpace/cached videos» или выбранная папка. */
    fun folderLabel(): String {
        val t = tree ?: return tr("Загрузки/AdultSpace/cached videos")
        val id = runCatching { DocumentsContract.getTreeDocumentId(t) }.getOrNull() ?: return t.toString()
        return id.replaceFirst(":", "/").removeSuffix("/")
    }

    /** Системный выбор папки (SAF). Запускать через [FolderPickActivity] — она вернёт результат в [onFolderPicked]. */
    fun pickFolderIntent(): Intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        .addFlags(RW or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)

    /** Папка выбрана: запомнить и закрепить доступ (переживает перезапуск). Уже сохранённое остаётся где лежит. */
    fun onFolderPicked(uri: Uri) {
        runCatching { app.contentResolver.takePersistableUriPermission(uri, RW) }
            .onFailure { android.util.Log.w("Loopa", "папка сохранения: доступ не закреплён: ${it.message}") }
        tree?.takeIf { it != uri }?.let { old -> runCatching { app.contentResolver.releasePersistableUriPermission(old, RW) } }
        prefs.edit().putString(K_TREE, uri.toString()).remove(K_ERR).apply()
        errorShown = false
        synchronized(failed) { failed.clear() }
    }

    /** Вернуть папку по умолчанию (Загрузки/AdultSpace/cached videos). */
    fun resetFolder() {
        tree?.let { old -> runCatching { app.contentResolver.releasePersistableUriPermission(old, RW) } }
        prefs.edit().remove(K_TREE).remove(K_ERR).apply()
        errorShown = false
        synchronized(failed) { failed.clear() }
    }

    // --- индекс: id → {n: имя, s: размер, u: адрес файла, k: [адреса ролика (hd, sd)]} — в памяти и в настройках ---
    private var ix: JSONObject? = null
    private var urlMap: Map<String, String>? = null

    @Synchronized private fun index(): JSONObject =
        ix ?: (runCatching { JSONObject(prefs.getString(K_INDEX, "{}")!!) }.getOrDefault(JSONObject())).also { ix = it; recount(it) }

    private fun recount(i: JSONObject) { savedTotal = i.keys().asSequence().sumOf { i.getJSONObject(it).optLong("s") } }

    /** Индекс изменился: в памяти — сразу, в настройки — отложенно ([flush]); кто убирает кэш после записи, сначала зовёт [flush]. */
    @Synchronized private fun saveSoon() {
        urlMap = null; recount(index())
        if (saveQueued.compareAndSet(false, true)) saver.schedule({ flush() }, SAVE_DELAY_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
    }

    /** Записать индекс в настройки сейчас. */
    @Synchronized fun flush() {
        saveQueued.set(false)
        if (!::app.isInitialized) return
        prefs.edit().putString(K_INDEX, index().toString()).apply()
    }

    /** Новая запись: время последнего просмотра («a») — сейчас (ролик только что досмотрели). */
    @Synchronized private fun put(id: String, v: JSONObject) { index().put(id, v.put("a", System.currentTimeMillis())); saveSoon() }
    @Synchronized private fun drop(id: String) { index().remove(id); verified.remove(id); saveSoon() }

    /** Сколько места занимают сохранённые ролики, байт (по индексу). */
    fun usedBytes(): Long { if (!::app.isInitialized) return 0L; index(); return savedTotal }

    /** Сохранённый ролик для уборщика: id, размер, когда последний раз играл (мс). */
    class Entry(val id: String, val size: Long, val at: Long)

    @Synchronized fun entries(): List<Entry> {
        if (!::app.isInitialized) return emptyList()
        val i = index()
        return i.keys().asSequence().map { id -> i.getJSONObject(id).let { Entry(id, it.optLong("s"), it.optLong("a")) } }.toList()
    }

    /** Ролик [g] играют из файла — время последнего просмотра обновить: давно не смотренное удаляется первым. */
    fun played(g: Gif) {
        if (!::app.isInitialized) return
        synchronized(this) { index().optJSONObject(g.id)?.let { it.put("a", System.currentTimeMillis()); saveSoon() } }
    }

    /** Убрать сохранённый ролик (файл и запись) по требованию уборщика; ролик на экране не трогаем. */
    fun evict(id: String): Boolean {
        if (id == playing) return false
        val e = synchronized(this) { index().optJSONObject(id)?.let { JSONObject(it.toString()) } } ?: return false
        drop(id)
        synchronized(pending) { pending.remove(id) }
        runCatching { delete(Uri.parse(e.getString("u")), e.optLong("s")) }.onFailure { android.util.Log.w("Loopa", "не удалить сохранённый ролик $id: ${it.message}") }
        return true
    }

    /** Файл пропал или не открывается: убрать запись из индекса (сам файл не трогаем) — дальше играем по сети. */
    fun forget(g: Gif) { if (::app.isInitialized) drop(g.id) }

    /** Удалить сохранённые приложением ролики (файлы из папки и индекс). Досмотренные позже сохранятся снова. */
    fun clear() {
        val old = synchronized(this) { JSONObject(index().toString()).also { ix = JSONObject(); verified.clear(); saveSoon(); flush() } }
        synchronized(pending) { pending.clear() }
        kotlin.concurrent.thread(name = "cached-export-clear") {
            old.keys().forEach { runCatching { old.getJSONObject(it).let { e -> delete(Uri.parse(e.getString("u")), e.optLong("s")) } } }
        }
    }

    /** Адрес файла, куда перенесён ролик (file:// или content://); нет в индексе — null. Только память: наличие файла проверяется в фоне ([recheck]). */
    fun localUri(g: Gif): Uri? {
        if (!::app.isInitialized) return null
        val u = synchronized(this) { index().optJSONObject(g.id)?.optString("u") }?.takeIf { it.isNotEmpty() }?.let(Uri::parse) ?: return null
        recheck(g.id, u)
        return u
    }

    /** Раз в [VERIFY_MS] в фоне проверить, что файл на месте; пропал — запись снимается (плеер сам перейдёт на сеть). */
    private fun recheck(id: String, u: Uri) {
        val now = System.currentTimeMillis()
        if (now - (verified[id] ?: 0L) < VERIFY_MS || !checking.add(id)) return
        verified[id] = now
        checker.execute {
            try {
                if (!exists(u)) { android.util.Log.i("Loopa", "сохранённый файл пропал: $id"); drop(id) }
            } catch (_: Throwable) { } finally { checking.remove(id) }
        }
    }

    /** Этот адрес ролика (HD/SD) уже перенесён в файл — для [Video.fullyCached]. */
    @Synchronized fun hasUrl(url: String): Boolean {
        if (!::app.isInitialized || url.isEmpty()) return false
        val m = urlMap ?: HashMap<String, String>().also { map ->
            val i = index()
            i.keys().forEach { id -> i.getJSONObject(id).optJSONArray("k")?.let { a -> for (j in 0 until a.length()) map[a.getString(j)] = id } }
        }.also { urlMap = it }
        return url in m
    }

    private fun exists(u: Uri): Boolean = try {
        if (u.scheme == "file") (u.path?.let { File(it).length() } ?: 0L) > 0
        else app.contentResolver.openAssetFileDescriptor(u, "r")?.use { it.length != 0L } ?: false
    } catch (e: Exception) { false }

    // --- очередь ---
    /** Запомнить просмотренный ролик (при уходе с него): его проверит [sweep]. */
    fun touch(g: Gif) {
        synchronized(seen) { seen.remove(g.id); seen[g.id] = g; while (seen.size > SEEN_MAX) seen.remove(seen.keys.first()) }
        if (g.hls) synchronized(dirty) { dirty += g.id }
    }

    /** Проверить просмотренные: целиком в кэше и ещё не сохранён — в очередь. [skipId] — ролик на экране, его не трогаем. */
    fun sweep(skipId: String? = null) {
        if (!::app.isInitialized) return
        pool.execute { try { purgeReady() } catch (_: Throwable) { } }
        if (!enabled) return
        val todo = synchronized(seen) { seen.values.filter { it.id != skipId } }
        val have = synchronized(this) { index().let { i -> todo.filter { !i.has(it.id) } } }
        have.filter { if (it.hls) synchronized(dirty) { dirty.remove(it.id) } else candidate(it) != null }.forEach { g ->
            val queue = synchronized(failed) { g.id !in failed } && synchronized(busy) { busy.add(g.id) }
            if (queue) pool.execute { try { export(g) } catch (_: Throwable) { } finally { synchronized(busy) { busy.remove(g.id) } } }
        }
    }

    /** Адрес mp4 (HD или SD), что целиком в кэше. */
    private fun candidate(g: Gif): String? {
        if (g.hls || g.hd.isEmpty()) return null
        return listOf(g.hd, g.sd).distinct().firstOrNull { it.isNotEmpty() && !Sources.uncached(it) && inCache(it) }
    }

    private fun inCache(key: String): Boolean = runCatching {
        val len = ContentMetadata.getContentLength(Video.cache.getContentMetadata(key))
        len > 0 && Video.cache.isCached(key, 0, len)
    }.getOrDefault(false)

    /** Что и как записывать: расширение, тип, размер, запись в поток, ключи кэша на удаление, уборка временных файлов. */
    private class Job(val ext: String, val mime: String, val size: Long, val keys: List<String>, val write: (OutputStream) -> Long, val done: () -> Unit = {})

    private fun export(g: Gif) {
        if (!enabled || synchronized(this) { index().has(g.id) }) return
        var made: Uri? = null; var job: Job? = null; var writing = false
        try {
            job = (if (g.hls) hlsJob(g) else mp4Job(g)) ?: return
            // ролик больше всего предела кэша: после записи его же и удалил бы уборщик — не переносим, остаётся в кэше
            if (job.size > Video.cacheLimit) {
                android.util.Log.i("Loopa", "ролик ${g.id} (${job.size shr 20} МБ) больше предела кэша — не переношу")
                synchronized(failed) { failed += g.id }
                return
            }
            val site = if (g.reddit) "reddit" else g.site.ifEmpty { "redgifs" }
            val name = CacheMap.fileName(site, g.user, g.description, g.id).removeSuffix(".mp4") + "." + job.ext
            writing = true
            ensureNoMedia()
            val (uri, out) = create(name, job.mime)
            made = uri
            val n = out.use { job.write(it) }
            if (n != job.size) throw java.io.IOException("записано $n из ${job.size}")
            finish(uri)
            val urls = JSONArray(listOf(g.hd, g.sd).filter { it.isNotEmpty() }.distinct())
            put(g.id, JSONObject().put("n", name).put("s", n).put("u", uri.toString()).put("k", urls))
            synchronized(pending) { pending[g.id] = job.keys }
            clearError()
            android.util.Log.i("Loopa", "ролик перенесён из кэша в файл: $name, ${n shr 10} КБ")
            purgeReady()
            CacheReaper.request()
        } catch (e: Throwable) {
            android.util.Log.w("Loopa", "не перенести ролик ${g.id} из кэша: ${e.message}")
            made?.let { runCatching { delete(it) } }
            synchronized(failed) { failed += g.id }
            // ошибка записи в папку (нет доступа, нет места) — не молча: подсказка в настройках и один раз тост
            if (writing && e is java.io.IOException) reportError(e.message ?: e.javaClass.simpleName)
        } finally { job?.done?.invoke() }
    }

    /** Подсказка «не удалось сохранить»: запоминается для настроек, тост — один раз за запуск, пока не получится записать. */
    private fun reportError(msg: String) {
        prefs.edit().putString(K_ERR, msg).apply()
        if (errorShown) return
        errorShown = true
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            android.widget.Toast.makeText(app, tr("Не удалось сохранить в папку: %s", msg), android.widget.Toast.LENGTH_LONG).show()
        }
    }

    private fun clearError() { errorShown = false; if (prefs.contains(K_ERR)) prefs.edit().remove(K_ERR).apply() }

    /** Последняя ошибка записи в папку (для настроек); null — всё в порядке. */
    val lastError: String? get() = if (::app.isInitialized) prefs.getString(K_ERR, null) else null

    /** Убрать из кэша перенесённое — только у ролика не на экране; записанное в файл раньше уже в индексе. */
    private fun purgeReady() {
        val go = synchronized(pending) { pending.filterKeys { it != playing }.also { it.keys.forEach { k -> pending.remove(k) } } }
        if (go.isEmpty()) return
        flush()   // индекс на диск раньше, чем уйдёт кэш: убьют процесс — файл останется известным
        go.values.forEach { keys -> synchronized(Video.cache) { keys.forEach { runCatching { Video.cache.removeResource(it) } } } }
    }

    private fun mp4Job(g: Gif): Job? {
        val key = candidate(g) ?: return null
        val len = ContentMetadata.getContentLength(Video.cache.getContentMetadata(key))
        return Job("mp4", "video/mp4", len, listOf(g.hd, g.sd).filter { it.isNotEmpty() && !Sources.uncached(it) }.distinct(), { copyFromCache(key, it) })
    }

    /** Читаем только из кэша (без сети): ключ — адрес. Возвращает число байт. */
    private fun copyFromCache(key: String, out: OutputStream): Long {
        val src = CacheDataSource.Factory().setCache(Video.cache).createDataSource()
        var n = 0L
        try {
            src.open(DataSpec.Builder().setUri(Uri.parse(key)).setKey(key).build())
            val buf = ByteArray(256 shl 10)
            while (true) { val r = src.read(buf, 0, buf.size); if (r < 0) break; out.write(buf, 0, r); n += r }
        } finally { src.close() }
        return n
    }

    // --- HLS ---
    private fun cachedText(key: String): String? = if (!inCache(key)) null else
        java.io.ByteArrayOutputStream().also { copyFromCache(key, it) }.toString("UTF-8")

    private fun fetchText(r: Resolved): String? = runCatching {
        val rq = okhttp3.Request.Builder().url(r.url).header("User-Agent", Api.UA).apply { r.headers.forEach { (k, v) -> header(k, v) } }.build()
        Net.http.newCall(rq).execute().use { if (it.isSuccessful) it.body?.string() else null }
    }.getOrNull()

    /** Плейлист варианта: из кэша (его играл плеер); нет — этот вариант не смотрели. */
    private fun mediaOf(url: String): HlsPack.Media? = cachedText(url)?.let { HlsPack.media(it, url) }?.takeIf { !it.encrypted && it.segments.isNotEmpty() }

    private fun allCached(m: HlsPack.Media) = listOfNotNull(m.init).plus(m.segments).all { inCache(it) }

    /** Сколько байт занимают сегменты в кэше. */
    private fun bytesOf(m: HlsPack.Media?): Long = if (m == null) 0L else
        (listOfNotNull(m.init) + m.segments).sumOf { runCatching { ContentMetadata.getContentLength(Video.cache.getContentMetadata(it)) }.getOrDefault(0L).coerceAtLeast(0L) }

    /** HLS: лучший вариант, у которого (и у его звука) все сегменты в кэше → склейка в mp4 во временный файл. Нет такого — null. */
    private fun hlsJob(g: Gif): Job? {
        for (top in listOf(g.hd, g.sd).filter { it.isNotEmpty() }.distinct()) {
            val real = if (Sources.isLazy(top)) runCatching { Sources.resolve(top) }.getOrNull() ?: continue else Resolved(top)
            val text = (if (Sources.isLazy(top)) null else cachedText(top)) ?: fetchText(real) ?: continue
            val variants = if (HlsPack.isMaster(text)) HlsPack.variants(text, real.url) else listOf(HlsPack.Variant(real.url, null, 0))
            for (v in variants) {
                val vm = (if (v.url == real.url && !HlsPack.isMaster(text)) HlsPack.media(text, v.url).takeIf { !it.encrypted && it.segments.isNotEmpty() } else mediaOf(v.url)) ?: continue
                if (!allCached(vm)) continue
                val am = v.audio?.let { mediaOf(it) }
                if (v.audio != null && (am == null || !allCached(am))) continue
                if (bytesOf(vm) + bytesOf(am) > HLS_MAX) throw IllegalStateException("HLS больше ${HLS_MAX shr 20} МБ — не склеиваем")
                val keys = (listOf(top, real.url, v.url, v.audio) + listOfNotNull(vm.init) + vm.segments + listOfNotNull(am?.init) + (am?.segments ?: emptyList()))
                    .filterNotNull().distinct()
                return packHls(g, vm, am, keys)
            }
        }
        return null
    }

    private fun packHls(g: Gif, vm: HlsPack.Media, am: HlsPack.Media?, keys: List<String>): Job {
        val tmp = app.cacheDir.resolve("cx").apply { mkdirs() }
        val vf = File(tmp, "${g.id.hashCode()}.v"); val af = File(tmp, "${g.id.hashCode()}.a"); val out = File(tmp, "${g.id.hashCode()}.mp4")
        fun clean() { vf.delete(); af.delete(); out.delete() }
        fun join(m: HlsPack.Media, to: File) = to.outputStream().use { o -> (listOfNotNull(m.init) + m.segments).forEach { copyFromCache(it, o) } }
        try {
            join(vm, vf); if (am != null) join(am, af)
            var src = out; var ext = "mp4"; var mime = "video/mp4"
            try { HlsPack.mux(vf, if (am != null) af else null, out) }
            catch (e: Exception) {
                // не склеилось в mp4: обычные TS-сегменты со звуком внутри — отдаём как .ts (VLC, MX играют)
                if (am != null || vm.init != null) throw e
                src = vf; ext = "ts"; mime = "video/mp2t"
            }
            return Job(ext, mime, src.length(), keys, { o -> src.inputStream().use { it.copyTo(o) } }, { clean() })
        } catch (e: Exception) { clean(); throw e }
    }

    // --- запись ---
    /** Папка по умолчанию (Android 8–9): публичные Загрузки/AdultSpace/cached videos. */
    private fun legacyDir(): File = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), DEFAULT_DIR)

    /** Путь папки по умолчанию в MediaStore (Android 10+): «Download/AdultSpace/cached videos/». */
    private val mediaPath get() = Environment.DIRECTORY_DOWNLOADS + "/$DEFAULT_DIR/"

    /** Новый файл в папке сохранения: адрес и поток записи. */
    private fun create(name: String, mime: String): Pair<Uri, OutputStream> {
        val cr = app.contentResolver
        tree?.let { t ->
            val parent = DocumentsContract.buildDocumentUriUsingTree(t, DocumentsContract.getTreeDocumentId(t))
            val doc = DocumentsContract.createDocument(cr, parent, mime, name) ?: throw java.io.IOException("папка недоступна")
            return doc to (cr.openOutputStream(doc) ?: throw java.io.IOException("нет записи"))
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val cv = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name); put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, mediaPath)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv) ?: throw java.io.IOException("нет доступа к Загрузкам")
            return uri to (cr.openOutputStream(uri) ?: throw java.io.IOException("нет записи"))
        }
        val d = legacyDir().apply { mkdirs() }
        if (!d.isDirectory) throw java.io.IOException("нет доступа к Загрузкам (разрешение?)")
        val f = File(d, name)
        return Uri.fromFile(f) to f.outputStream()
    }

    /**
     * Пустой `.nomedia` в папке сохранения (раз за запуск на папку): галерея и медиасканер не показывают ролики. Не вышло —
     * не страшно, ролики всё равно сохраняются (в журнал). Файл с точкой в начале:
     *  - Android 10+, папка по умолчанию: MediaStore.Downloads с типом application/octet-stream (с типом видео имя без
     *    расширения-пары система «исправила» бы); имя проверяем после создания;
     *  - своя папка (SAF): createDocument с тем же типом, имя тоже проверяется (провайдер может дописать расширение);
     *  - Android 8–9: обычный File в публичной папке.
     * Сторонним плеерам и файловым менеджерам `.nomedia` не мешает: он действует только на сканер медиатеки.
     */
    private fun ensureNoMedia() {
        val key = tree?.toString() ?: "default"
        if (noMediaKey == key) return
        noMediaKey = key
        try { if (!makeNoMedia()) android.util.Log.w("Loopa", "не создать .nomedia в папке сохранённых роликов") }
        catch (e: Exception) { android.util.Log.w("Loopa", "не создать .nomedia: ${e.message}") }
    }

    private fun makeNoMedia(): Boolean {
        val cr = app.contentResolver
        val t = tree
        if (t != null) {
            val docId = DocumentsContract.getTreeDocumentId(t)
            val kids = DocumentsContract.buildChildDocumentsUriUsingTree(t, docId)
            cr.query(kids, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
                while (c.moveToNext()) if (c.getString(0) == NOMEDIA) return true
            }
            val doc = DocumentsContract.createDocument(cr, DocumentsContract.buildDocumentUriUsingTree(t, docId), OCTET, NOMEDIA) ?: return false
            if (displayName(doc, DocumentsContract.Document.COLUMN_DISPLAY_NAME) == NOMEDIA) return true
            runCatching { DocumentsContract.deleteDocument(cr, doc) }   // имя исказили (.nomedia.bin) — такой файл бесполезен
            return false
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val sel = "${MediaStore.Downloads.DISPLAY_NAME}=? AND ${MediaStore.Downloads.RELATIVE_PATH}=?"
            cr.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Downloads._ID), sel, arrayOf(NOMEDIA, mediaPath), null)?.use { if (it.moveToFirst()) return true }
            val cv = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, NOMEDIA); put(MediaStore.Downloads.MIME_TYPE, OCTET)
                put(MediaStore.Downloads.RELATIVE_PATH, mediaPath)
            }
            val uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv) ?: return false
            if (displayName(uri, MediaStore.Downloads.DISPLAY_NAME) == NOMEDIA) return true
            runCatching { cr.delete(uri, null, null) }
            return false
        }
        val d = legacyDir().apply { mkdirs() }
        val f = File(d, NOMEDIA)
        return f.exists() || f.createNewFile()
    }

    private fun displayName(uri: Uri, column: String): String? =
        app.contentResolver.query(uri, arrayOf(column), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }

    /** MediaStore: файл готов (снять «в процессе»). */
    private fun finish(uri: Uri) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && uri.authority == MediaStore.AUTHORITY)
            app.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
    }

    /**
     * Удалить файл. [size] ≥ 0 — ожидаемый размер: у файла в чужой (SAF) папке перед удалением проверяем, что он на месте
     * и размер тот же (пользователь мог подменить файл с тем же именем) — иначе не трогаем.
     */
    private fun delete(uri: Uri, size: Long = -1L) {
        when {
            uri.scheme == "file" -> File(uri.path!!).delete()
            DocumentsContract.isDocumentUri(app, uri) -> {
                if (size >= 0) {
                    val len = try { app.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: return }
                    catch (e: java.io.FileNotFoundException) { return }   // файла уже нет
                    if (len >= 0 && len != size) { android.util.Log.i("Loopa", "файл в папке изменён ($len вместо $size) — не удаляю"); return }
                }
                DocumentsContract.deleteDocument(app.contentResolver, uri)
            }
            else -> app.contentResolver.delete(uri, null, null)
        }
    }
}
