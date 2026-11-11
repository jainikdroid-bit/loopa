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
 * Обновление из релизов GitHub: раз в [EVERY_MS] (и вручную) спрашивает последний релиз, а если он новее — сам скачивает
 * APK, сверяет SHA-256 с файлом SHA256SUMS из того же релиза и предлагает поставить ([install]). Подлинность проверяет
 * Android: чужую подпись установщик не примет. Тихой установки нет — система просит подтвердить (на Android 12+ при
 * разрешённой установке из этого приложения обновление может пройти без вопроса).
 */
object Updater {
    private const val API = "https://api.github.com/repos/jainikdroid-bit/loopa/releases/latest"
    private const val ASSETS = "https://github.com/jainikdroid-bit/loopa/releases/download/"
    private const val EVERY_MS = 6 * 3600_000L
    const val ACTION = "app.loopa.INSTALL_RESULT"

    class Release(val version: String, val notes: String, val apkUrl: String, val apkName: String, val sumsUrl: String)

    /** Версия «v1.11.2» / «1.11» → числа; нечисловое отбрасывается. */
    fun parts(v: String): List<Int> = Regex("\\d+").findAll(v).map { it.value.toInt() }.take(4).toList()

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
    fun trusted(url: String) = url.startsWith(ASSETS) && !url.contains("..")

    private var prefs: android.content.SharedPreferences? = null
    private fun p(ctx: Context) = prefs ?: ctx.applicationContext.getSharedPreferences("loopa", Context.MODE_PRIVATE).also { prefs = it }
    private fun dir(ctx: Context) = File(ctx.cacheDir, "updates").apply { mkdirs() }

    fun auto(ctx: Context) = p(ctx).getBoolean("upd_auto", true)
    fun setAuto(ctx: Context, on: Boolean) { p(ctx).edit().putBoolean("upd_auto", on).apply() }
    fun current(ctx: Context): String = ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "0"

    /** Скачанная и проверенная версия, готовая к установке; null — нет. */
    fun ready(ctx: Context): String? {
        val v = p(ctx).getString("upd_ready", null) ?: return null
        return v.takeIf { File(dir(ctx), "Loopa-$it.apk").exists() && isNewer(current(ctx), it) }
    }

    /** Итог проверки: версия, готовая к установке (или null), и текст сообщения. */
    class Result(val ready: String?, val message: String, val notes: String = "")

    /** Проверить и при необходимости скачать (в вызывающем потоке — только фоновом). [force] — вручную: без интервала. */
    fun check(ctx: Context, force: Boolean): Result {
        val sp = p(ctx)
        if (!force && (!auto(ctx) || System.currentTimeMillis() - sp.getLong("upd_checked", 0) < EVERY_MS)) return Result(ready(ctx), "")
        val rel = try { latest() } catch (e: Exception) {
            android.util.Log.w("Loopa", "обновление: проверка не удалась", e)
            return Result(ready(ctx), tr("Не удалось проверить обновления: %s", e.message ?: e.javaClass.simpleName))
        }
        sp.edit().putLong("upd_checked", System.currentTimeMillis()).apply()
        if (!isNewer(current(ctx), rel.version)) { sp.edit().remove("upd_ready").apply(); return Result(null, tr("У вас последняя версия")) }
        val file = File(dir(ctx), "Loopa-${rel.version}.apk")
        if (!(file.exists() && sp.getString("upd_ready", null) == rel.version)) {
            // сеть с экономией трафика — автоматически не качаем (вручную — качаем)
            if (!force && Video.net() == NetKind.SAVER) return Result(null, tr("Доступна версия %s", rel.version), rel.notes)
            android.util.Log.i("Loopa", "обновление: скачиваю ${rel.version}")
            try { download(ctx, rel, file) } catch (e: Exception) {
                file.delete(); android.util.Log.w("Loopa", "обновление: скачивание не удалось", e)
                return Result(null, e.message ?: e.javaClass.simpleName, rel.notes)
            }
            sp.edit().putString("upd_ready", rel.version).apply()
        }
        dir(ctx).listFiles()?.filter { it.name != file.name }?.forEach { it.delete() }   // старые версии не копим
        return Result(rel.version, tr("Версия %s скачана и проверена — можно ставить", rel.version), rel.notes)
    }

    private fun get(url: String): String {
        val c = Net.open(url); c.setRequestProperty("Accept", "application/vnd.github+json")
        try { if (c.responseCode != 200) throw Net.fail(c); return c.inputStream.bufferedReader().readText() } finally { c.disconnect() }
    }

    private fun latest(): Release {
        val j = JSONObject(get(API))
        val assets = j.getJSONArray("assets")
        var apk: Pair<String, String>? = null; var sums: String? = null
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i); val n = a.getString("name"); val u = a.getString("browser_download_url")
            if (!trusted(u)) continue
            if (Regex("^Loopa-.*\\.apk$").matches(n)) apk = n to u else if (n == "SHA256SUMS") sums = u
        }
        if (apk == null || sums == null) throw RuntimeException("в релизе нет APK или SHA256SUMS")
        return Release(j.getString("tag_name").removePrefix("v"), j.optString("body"), apk.second, apk.first, sums)
    }

    /** Скачать APK и сверить SHA-256 с SHA256SUMS; не совпало — исключение, файл удалён. */
    private fun download(ctx: Context, r: Release, out: File) {
        val want = sumFor(get(r.sumsUrl), r.apkName) ?: throw RuntimeException("в SHA256SUMS нет ${r.apkName}")
        val part = File(out.path + ".part"); val md = MessageDigest.getInstance("SHA-256")
        val c = Net.open(r.apkUrl); c.readTimeout = 60_000
        try {
            if (c.responseCode != 200) throw Net.fail(c)
            c.inputStream.use { i -> part.outputStream().use { o ->
                val buf = ByteArray(64 * 1024); while (true) { val n = i.read(buf); if (n < 0) break; md.update(buf, 0, n); o.write(buf, 0, n) } } }
        } finally { c.disconnect() }
        val got = md.digest().joinToString("") { "%02x".format(it) }
        if (got != want) { part.delete(); throw RuntimeException(tr("Контрольная сумма не совпала — файл отброшен")) }
        if (!part.renameTo(out)) throw RuntimeException("не сохранить ${out.name}")
    }

    /** Поставить скачанную версию через установщик Android; null — запущено, иначе текст ошибки. */
    fun install(ctx: Context): String? {
        val v = ready(ctx) ?: return tr("Не удалось установить: %s", "нет скачанной версии")
        val f = File(dir(ctx), "Loopa-$v.apk")
        if (!ctx.packageManager.canRequestPackageInstalls()) {
            ctx.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return tr("Разрешите Loopa устанавливать приложения: откроются настройки")
        }
        return try {
            val pi = ctx.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            val id = pi.createSession(params)
            pi.openSession(id).use { s ->
                f.inputStream().use { i -> s.openWrite("loopa.apk", 0, f.length()).use { o -> i.copyTo(o); s.fsync(o) } }
                val it = Intent(ctx, UpdateReceiver::class.java).setAction(ACTION)
                val pend = PendingIntent.getBroadcast(ctx, id, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
                s.commit(pend.intentSender)
            }
            null
        } catch (e: Exception) { android.util.Log.w("Loopa", "обновление: установка", e); tr("Не удалось установить: %s", e.message ?: e.javaClass.simpleName) }
    }
}
