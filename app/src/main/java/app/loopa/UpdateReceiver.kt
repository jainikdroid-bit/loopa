package app.loopa

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.widget.Toast

/** Ответы установщика Android на [Updater.install]: просьба подтвердить — показать; ошибка — сказать. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        I18n.init(ctx)
        when (val st = i.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION ->
                (if (android.os.Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java) else @Suppress("DEPRECATION") i.getParcelableExtra(Intent.EXTRA_INTENT))
                    ?.let { ctx.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            PackageInstaller.STATUS_SUCCESS -> {}
            else -> Toast.makeText(ctx, tr("Не удалось установить: %s", i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "код $st"), Toast.LENGTH_LONG).show()
        }
    }
}
