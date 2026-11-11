package app.loopa

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast

/**
 * «Поделиться» ссылкой RedGIFs или Reddit → Loopa сразу скачивает ролик по ссылке (Загрузки/Loopa), без открытия
 * приложения: прозрачный экран на время поиска ролика, затем закрывается; скачивание идёт дальше в уведомлении.
 */
class ShareActivity : Activity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        I18n.init(this); Api.init(this); Net.init(this); Reddit.init(this)
        val text = listOfNotNull(intent.getStringExtra(Intent.EXTRA_TEXT), intent.dataString).joinToString(" ")
        val link = ShareLink.find(text)
        android.util.Log.i("Loopa", "поделиться: ссылка ${link ?: "не распознана"}")
        if (link == null) { toast(tr("Нужна ссылка на ролик RedGIFs или Reddit")); finish(); return }
        toast(tr("Ищу ролик по ссылке…"))
        val app = applicationContext
        Thread {
            val r = runCatching { ShareLink.resolve(link) }.onFailure { android.util.Log.w("Loopa", "ссылка $link", it) }
            runOnUiThread {
                val g = r.getOrNull()
                android.util.Log.i("Loopa", "поделиться: ролик ${g?.id ?: "не найден"}${r.exceptionOrNull()?.let { " (${it.message})" } ?: ""}")
                when {
                    g != null -> Downloads.get(app, g)
                    r.isFailure -> toast(tr("Не скачать: %s", r.exceptionOrNull()?.message ?: "?"))
                    else -> toast(tr("Ролик по ссылке не найден"))
                }
                finish()
            }
        }.start()
    }
    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_LONG).show()
}
