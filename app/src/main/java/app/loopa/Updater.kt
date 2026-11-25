package app.loopa

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * Обновление из релизов GitHub: при каждом запуске приложения (не чаще раза в [LAUNCH_GAP_MS]), раз в [EVERY_MS] (и вручную) спрашивает последний релиз, а если он новее — сам скачивает
 * APK, сверяет SHA-256 с файлом SHA256SUMS из того же релиза и предлагает поставить ([install]). Подлинность проверяет
 * Android: чужую подпись установщик не примет. Тихой установки нет — система просит подтвердить (на Android 12+ при
 * разрешённой установке из этого приложения обновление может пройти без вопроса).
 */
object Updater {
    private const val API = "https://api.github.com/repos/jainikdroid-bit/loopa/releases/latest"
    private const val ASSETS = "https://github.com/jainikdroid-bit/loopa/releases/download/"
    private const val EVERY_MS = 6 * 3600_000L
    /** Запуск за запуском (свернули-развернули, перезапуск) — GitHub не дёргаем чаще: у API без ключа 60 запросов в час. */
    private const val LAUNCH_GAP_MS = 10 * 60_000L
    private const val MAX_METADATA_BYTES = 2 * 1024 * 1024
    private const val MAX_APK_BYTES = 256L * 1024 * 1024
    private val installing = java.util.concurrent.atomic.AtomicBoolean(false)
    private val versionPattern = Regex("v?[0-9]{1,9}(\\.[0-9]{1,9}){0,3}")
    internal fun validVersion(v: String): Boolean = versionPattern.matches(v)
    internal fun validDigest(value: String?): Boolean =
        value != null && Regex("[0-9a-f]{64}").matches(value)
    private fun digestFor(ctx: Context, version: String): String? = p(ctx).let {
        if (it.getString("upd_ready", null) == version) it.getString("upd_digest", null)?.takeIf(::validDigest) else null
    }
    const val ACTION = "app.loopa.INSTALL_RESULT"

    class Release(val version: String, val notes: String, val apkUrl: String, val apkName: String, val sumsUrl: String)

    /** Версия «v1.11.2» / «1.11» → числа; нечисловое отбрасывается. */
    fun parts(v: String): List<Int> = Regex("\\d+").findAll(v).take(4).mapNotNull { it.value.toIntOrNull() }.toList()

    /** [latest] новее [current] (по числам, недостающие — нули). */
    fun isNewer(current: String, latest: String): Boolean {
        val a = parts(current); val b = parts(latest)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }
            if (y != x) return y > x
        }
        return false
    }

    /** Хеш файла [name] из SHA256SUMS («хеш  имя» или «хеш *имя»); null — нет. */
    fun sumFor(sums: String, name: String): String? = sums.lineSequence().map { it.trim() }.mapNotNull { l ->
        val m = Regex("^([0-9a-fA-F]{64})\\s+\\*?(.+)$").find(l) ?: return@mapNotNull null
        if (m.groupValues[2].trim() == name) m.groupValues[1].lowercase() else null
    }.firstOrNull()

    /** Адрес — файл релиза нашего репозитория (больше ничего не качаем). */
    fun trusted(url: String): Boolean {
        if (!url.startsWith(ASSETS) || url.length > 512) return false
        val segments = url.removePrefix(ASSETS).split('/')
        return segments.size == 2 && validVersion(segments[0]) &&
            Regex("[A-Za-z0-9_.-]{1,128}").matches(segments[1]) &&
            !segments[1].contains("..") && segments[1] != "."
    }

    private var prefs: android.content.SharedPreferences? = null
    private fun p(ctx: Context) = prefs ?: ctx.applicationContext.getSharedPreferences("loopa", Context.MODE_PRIVATE).also { prefs = it }
    private fun dir(ctx: Context) = File(ctx.cacheDir, "updates").apply { mkdirs() }

    private fun installer(ctx: Context, version: String): File {
        val target = File(dir(ctx), "AdultSpace-$version.apk")
        val previous = File(dir(ctx), "Loopa-$version.apk")
        if (!target.exists() && previous.exists() && !previous.renameTo(target)) return previous
        return target
    }

    const val MANUAL = "manual"; const val ASK = "ask"; const val AUTO = "auto"
    fun parseMode(s: String?) = if (s == MANUAL || s == ASK || s == AUTO) s else ASK

    /** Режим: вручную (только по кнопке) / предлагать (скачать и спросить — по умолчанию) / автоматически (скачать и поставить). */
    fun mode(ctx: Context): String = p(ctx).let { sp ->
        sp.getString("upd_mode", null)?.let(::parseMode) ?: if (sp.getBoolean("upd_auto", true)) ASK else MANUAL   // прежняя галочка
    }
    fun setMode(ctx: Context, m: String) { p(ctx).edit().putString("upd_mode", parseMode(m)).apply() }
    fun current(ctx: Context): String = ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "0"

    /** Скачанная и проверенная версия, готовая к установке; null — нет. */
    fun ready(ctx: Context): String? {
        val v = p(ctx).getString("upd_ready", null) ?: return null
        return v.takeIf { validVersion(it) && digestFor(ctx, it) != null && installer(ctx, it).isFile && isNewer(current(ctx), it) }
    }

    /** Итог проверки: версия, готовая к установке (или null), и текст сообщения. */
    class Result(val ready: String?, val message: String, val notes: String = "")

    /**
     * Проверить и при необходимости скачать (в вызывающем потоке — только фоновом). [force] — вручную: без интервала;
     * [launch] — запуск приложения: проверяем сразу (если с прошлой проверки прошло больше [LAUNCH_GAP_MS]).
     */
    @Synchronized fun check(ctx: Context, force: Boolean, launch: Boolean = false): Result {
        if (installing.get()) return Result(null, "")
        val sp = p(ctx)
        val gap = if (launch) LAUNCH_GAP_MS else EVERY_MS
        if (!force && (mode(ctx) == MANUAL || System.currentTimeMillis() - sp.getLong("upd_checked", 0) < gap)) return Result(ready(ctx), "")
        val rel = try { latest() } catch (e: Exception) {
            android.util.Log.w("Loopa", "обновление: проверка не удалась", e)
            return Result(ready(ctx), tr("Не удалось проверить обновления: %s", e.message ?: e.javaClass.simpleName))
        }
        sp.edit().putLong("upd_checked", System.currentTimeMillis()).apply()
        if (!isNewer(current(ctx), rel.version)) { sp.edit().remove("upd_ready").remove("upd_digest").apply(); return Result(null, tr("У вас последняя версия")) }
        val file = installer(ctx, rel.version)
        if (!(file.isFile && digestFor(ctx, rel.version) != null)) {
            // сеть с экономией трафика — автоматически не качаем (вручную — качаем)
            if (!force && Video.net() == NetKind.SAVER) return Result(null, tr("Доступна версия %s", rel.version), rel.notes)
            android.util.Log.i("Loopa", "обновление: скачиваю ${rel.version}")
            sp.edit().remove("upd_ready").remove("upd_digest").apply()
            val digest = try { download(ctx, rel, file) } catch (e: Exception) {
                file.delete(); File(file.path + ".part").delete()
                android.util.Log.w("Loopa", "Update download failed: " + e.javaClass.simpleName)
                return Result(null, e.message ?: e.javaClass.simpleName, rel.notes)
            }
            if (!sp.edit().putString("upd_ready", rel.version).putString("upd_digest", digest).commit()) {
                file.delete()
                sp.edit().remove("upd_ready").remove("upd_digest").apply()
                return Result(null, tr("Не удалось проверить обновления: %s", "checkpoint write failed"))
            }
        }
        dir(ctx).listFiles()?.filter { it.name != file.name }?.forEach { it.delete() }   // старые версии не копим
        return Result(rel.version, tr("Версия %s скачана и проверена — можно ставить", rel.version), rel.notes)
    }

    private fun get(url: String): String {
        val c = Net.open(url); c.setRequestProperty("Accept", "application/vnd.github+json")
        try {
            if (c.responseCode != 200) throw Net.fail(c)
            if (c.contentLengthLong > MAX_METADATA_BYTES) throw java.io.IOException("Update metadata too large")
            return c.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    if (out.size() + n > MAX_METADATA_BYTES) throw java.io.IOException("Update metadata too large")
                    out.write(buffer, 0, n)
                }
                out.toString("UTF-8")
            }
        } finally { c.disconnect() }
    }

    private fun latest(): Release {
        val j = JSONObject(get(API))
        val tag = j.getString("tag_name")
        require(validVersion(tag)) { "Unsupported release tag" }
        val version = tag.removePrefix("v")
        val assets = j.getJSONArray("assets")
        var apk: Pair<String, String>? = null; var sums: String? = null
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i); val n = a.getString("name"); val u = a.getString("browser_download_url")
            if (!trusted(u)) continue
            when (n) {
                "AdultSpace-$version.apk" -> apk = n to u
                "Loopa-$version.apk" -> if (apk == null) apk = n to u
                "SHA256SUMS" -> sums = u
            }
        }
        if (apk == null || sums == null) throw RuntimeException("в релизе нет APK или SHA256SUMS")
        return Release(version, j.optString("body"), apk.second, apk.first, sums)
    }

    /** Скачать APK и сверить SHA-256 с SHA256SUMS; не совпало — исключение, файл удалён. */
    private fun download(ctx: Context, r: Release, out: File): String {
        val want = sumFor(get(r.sumsUrl), r.apkName) ?: throw RuntimeException("в SHA256SUMS нет ${r.apkName}")
        val part = File(out.path + ".part"); val md = MessageDigest.getInstance("SHA-256")
        val c = Net.open(r.apkUrl); c.readTimeout = 60_000
        try {
            if (c.responseCode != 200) throw Net.fail(c)
            if (c.contentLengthLong > MAX_APK_BYTES) throw java.io.IOException("APK too large")
            c.inputStream.use { i -> part.outputStream().use { o ->
                val buf = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val n = i.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > MAX_APK_BYTES) throw java.io.IOException("APK too large")
                    md.update(buf, 0, n); o.write(buf, 0, n)
                }
                o.fd.sync()
            } }
        } finally { c.disconnect() }
        val got = md.digest().joinToString("") { "%02x".format(it) }
        if (got != want) { part.delete(); throw RuntimeException(tr("Контрольная сумма не совпала — файл отброшен")) }
        if (!part.renameTo(out)) throw RuntimeException("не сохранить ${out.name}")
        return got
    }

    /** The APK copy and PackageInstaller session must never block the UI. */
    fun installAsync(ctx: Context, callback: (String?) -> Unit) {
        val app = ctx.applicationContext
        if (!installing.compareAndSet(false, true)) return
        kotlin.concurrent.thread(name = "update-install") {
            val result = try { synchronized(this) { install(app) } }
                catch (e: Exception) { tr("Не удалось установить: %s", e.javaClass.simpleName) }
                finally { installing.set(false) }
            android.os.Handler(android.os.Looper.getMainLooper()).post { callback(result) }
        }
    }

    private fun invalidateReady(ctx: Context, file: File) {
        p(ctx).edit().remove("upd_ready").remove("upd_digest").remove("upd_asked").apply()
        file.delete()
    }

    /** Поставить скачанную версию через установщик Android; null — запущено, иначе текст ошибки. */
    private fun install(ctx: Context): String? {
        val v = ready(ctx) ?: return tr("Не удалось установить: %s", "нет скачанной версии")
        val f = installer(ctx, v)
        val want = digestFor(ctx, v) ?: return tr("Не удалось установить: %s", "missing checksum")
        if (!ctx.packageManager.canRequestPackageInstalls()) {
            ctx.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return tr("Разрешите Adult Space устанавливать приложения: откроются настройки")
        }
        return try {
            val pm = ctx.packageManager
            val archive = pm.getPackageArchiveInfo(f.absolutePath, 0)
            val installed = pm.getPackageInfo(ctx.packageName, 0)
            fun code(info: android.content.pm.PackageInfo): Long =
                if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
            if (archive == null || archive.packageName != ctx.packageName || code(archive) <= code(installed) ||
                f.length() !in 1L..MAX_APK_BYTES) {
                invalidateReady(ctx, f)
                return tr("Не удалось установить: %s", "invalid package or version")
            }
            val pi = pm.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            params.setAppPackageName(ctx.packageName)
            if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            val id = pi.createSession(params)
            try { pi.openSession(id).use { s ->
                val md = MessageDigest.getInstance("SHA-256")
                f.inputStream().use { i -> s.openWrite("AdultSpace.apk", 0, f.length()).use { o ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val n = i.read(buffer)
                        if (n < 0) break
                        total += n
                        if (total > MAX_APK_BYTES) throw java.io.IOException("APK too large")
                        md.update(buffer, 0, n)
                        o.write(buffer, 0, n)
                    }
                    s.fsync(o)
                } }
                val got = md.digest().joinToString("") { "%02x".format(it) }
                if (got != want) {
                    invalidateReady(ctx, f)
                    throw java.io.IOException(tr("Контрольная сумма не совпала — файл отброшен"))
                }
                val it = Intent(ctx, UpdateReceiver::class.java).setAction(ACTION)
                val pend = PendingIntent.getBroadcast(ctx, id, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
                s.commit(pend.intentSender)
            } } catch (e: Exception) {
                runCatching { pi.abandonSession(id) }
                throw e
            }
            null
        } catch (e: Exception) { android.util.Log.w("Loopa", "обновление: установка", e); tr("Не удалось установить: %s", e.message ?: e.javaClass.simpleName) }
    }
}
