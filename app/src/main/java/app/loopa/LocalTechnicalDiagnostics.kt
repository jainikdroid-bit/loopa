package app.loopa

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

/** Separate opt-in technical counts, private storage, no networking or preference features. */
internal object LocalTechnicalDiagnostics {
    private var prefs: SharedPreferences? = null
    @Synchronized fun init(ctx: Context) {
        if (prefs != null) return
        prefs = ctx.applicationContext.getSharedPreferences("local_technical_diagnostics", Context.MODE_PRIVATE)
        refresh()
    }
    @get:Synchronized val enabled get() = prefs?.getBoolean("enabled", false) == true

    @Synchronized fun setEnabled(value: Boolean) {
        val sp = prefs ?: return
        if (sp.getBoolean("enabled", false) == value) return
        sp.edit().clear().putBoolean("enabled", value).apply()
    }

    @Synchronized fun clear() {
        val sp = prefs ?: return
        sp.edit().clear().putBoolean("enabled", enabled).apply()
    }

    private fun refresh() {
        val sp = prefs ?: return
        val now = System.currentTimeMillis()
        if (!enabled) {
            if (sp.contains("start")) clear()
            return
        }
        if (TechnicalWindow.expired(sp.getLong("start", 0), now)) {
            sp.edit().clear().putBoolean("enabled", true).putLong("start", now).apply()
        }
    }

    private fun counts(): Map<String, Int> {
        val sp = prefs ?: return emptyMap()
        var left = TechnicalWindow.LIMIT
        return TechnicalWindow.kinds.sorted().associateWith {
            val n = sp.getInt(it, 0).coerceIn(0, left)
            left -= n
            n
        }
    }

    @Synchronized fun event(kind: String) {
        if (!enabled || kind !in TechnicalWindow.kinds) return
        refresh()
        val before = counts()
        val after = TechnicalWindow.increment(before, kind)
        if (after != before) prefs?.edit()?.putInt(kind, after.getValue(kind))?.apply()
    }

    /** User-requested export only. Compatible with the offline technical archive. */
    @Synchronized fun report(): String? {
        if (!enabled) return null
        refresh()
        return JSONObject().put("schema", 1).put("window_days", 30)
            .put("counts", JSONObject(counts())).toString(2)
    }
}
