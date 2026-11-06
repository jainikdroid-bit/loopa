package app.loopa

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.widget.Toast

/**
 * Скачивание через системный DownloadManager (в общую папку загрузок, с уведомлением о
 * прогрессе): Загрузки/Loopa/<автор>_<id>.mp4. Разрешений не нужно (Android 10+). Уже скачанное повторно не качаем.
 */
object Downloads {
    fun get(ctx: Context, g: Gif) {
        val name = "${g.user.ifBlank { "loopa" }}_${g.id}.mp4".replace(Regex("[^A-Za-z0-9._-]"), "_")
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (java.io.File(dir, "Loopa/$name").exists()) { Toast.makeText(ctx, "Уже скачано: Загрузки/Loopa/$name", Toast.LENGTH_SHORT).show(); return }
        val rq = DownloadManager.Request(Uri.parse(g.hd))
            .setTitle(name)
            .setDescription("Loopa")
            .addRequestHeader("User-Agent", Api.UA)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "Loopa/$name")
        runCatching { (ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(rq) }
            .onSuccess { Toast.makeText(ctx, "Скачиваю в Загрузки/Loopa", Toast.LENGTH_SHORT).show() }
            .onFailure { Toast.makeText(ctx, "Не скачать: ${it.message}", Toast.LENGTH_LONG).show() }
    }
}
