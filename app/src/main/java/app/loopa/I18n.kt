package app.loopa

import android.content.Context
import android.content.SharedPreferences
import java.util.Locale

/**
 * Языки интерфейса. Исходные строки — русские прямо в коде (`tr("…")`), переводы — таблицы в [I18nTables] по русскому
 * ключу. Нет перевода на выбранный язык — английский, нет и его — русский. Шаблоны: `%s`, `%d` как в String.format.
 * Выбор: «auto» — язык системы (если он есть среди поддерживаемых, иначе английский) или код языка вручную.
 */
object I18n {
    /** Код → название на самом языке. */
    val LANGS = linkedMapOf(
        "ru" to "Русский", "en" to "English", "es" to "Español", "pt" to "Português",
        "de" to "Deutsch", "fr" to "Français", "it" to "Italiano", "tr" to "Türkçe", "pl" to "Polski", "uk" to "Українська",
    )

    private var prefs: SharedPreferences? = null
    @Volatile var code = "ru"; private set

    fun init(ctx: Context) {
        if (prefs == null) prefs = ctx.applicationContext.getSharedPreferences("loopa", Context.MODE_PRIVATE)
        code = resolve(choice)
    }

    /** «auto» или код языка. */
    var choice: String
        get() = prefs?.getString("lang", "auto") ?: "auto"
        set(v) { prefs?.edit()?.putString("lang", v)?.apply(); code = resolve(v) }

    /** Язык системы, если он поддерживается. */
    val system: String get() = Locale.getDefault().language.let { if (it in LANGS) it else "en" }

    private fun resolve(c: String) = if (c in LANGS) c else system

    /** Подпись внутри фразы — со строчной буквы; в немецком существительные пишутся с заглавной. */
    fun lower(s: String) = if (code == "de") s else s.lowercase()

    fun tr(ru: String, vararg args: Any?): String {
        val t = if (code == "ru") ru else I18nTables.of(code)[ru] ?: I18nTables.of("en")[ru] ?: ru
        return if (args.isEmpty()) t else runCatching { String.format(Locale.ROOT, t, *args) }.getOrDefault(t)
    }
}

/** Перевести строку интерфейса (ключ — русский текст). */
fun tr(ru: String, vararg args: Any?) = I18n.tr(ru, *args)

/** Ошибка сети (ключ перевода; сравнивается и показывается через [tr]). */
const val NO_NET = "нет связи"
