package app.loopa

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.widget.Toast

/**
 * Невидимый экран выбора своей папки для [CachedExport]: настройкам достаточно
 * `startActivity(Intent(this, FolderPickActivity::class.java))`. Запускает системный выбор папки (SAF), сохраняет
 * результат и закрывается; в настройках после возврата обновить подпись ([CachedExport.folderLabel]) в onResume.
 */
class FolderPickActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        I18n.init(this); CachedExport.init(this)
        if (savedInstanceState != null) return
        try { startActivityForResult(CachedExport.pickFolderIntent(), REQ) }
        catch (e: ActivityNotFoundException) { Toast.makeText(this, tr("Не удалось открыть выбор папки"), Toast.LENGTH_LONG).show(); finish() }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (requestCode == REQ && resultCode == RESULT_OK && uri != null) {
            CachedExport.onFolderPicked(uri)
            Toast.makeText(this, tr("Папка для сохранённых роликов: %s", CachedExport.folderLabel()), Toast.LENGTH_LONG).show()
        }
        finish()
    }

    private companion object { const val REQ = 41 }
}
