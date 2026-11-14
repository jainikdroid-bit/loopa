package app.loopa

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import androidx.core.graphics.ColorUtils

/**
 * Тема оформления: фон у всех тёмный, меняется акцент (вкладка, чипы, кнопки, переключатели, ползунки, прогресс, ссылки)
 * и чуть оттенок поверхностей. Выбор хранится в настройках; экраны читают цвета отсюда, а смена темы пересоздаёт их.
 */
object Theme {
    /** Тема: акцент, фон, поле ввода и плашка (чип) — всё тёмное, с оттенком акцента. */
    class Scheme(val id: String, val title: String, val accent: Int, val bg: Int, val field: Int, val chip: Int)

    val ALL = listOf(
        Scheme("black", "Чёрная", 0xFF7C3AED.toInt(), 0xFF0E1015.toInt(), 0xFF1F232B.toInt(), 0xFF262B34.toInt()),
        Scheme("pink", "Розовая", 0xFFFF4FA3.toInt(), 0xFF120C10.toInt(), 0xFF261A21.toInt(), 0xFF33232C.toInt()),
        Scheme("red", "Красная", 0xFFFF3B47.toInt(), 0xFF120C0D.toInt(), 0xFF271819.toInt(), 0xFF342224.toInt()),
        Scheme("violet", "Фиолетовая", 0xFFA855F7.toInt(), 0xFF0F0C16.toInt(), 0xFF1D1829.toInt(), 0xFF292337.toInt()),
    )

    private var cur = ALL[0]
    val id get() = cur.id
    val accent get() = cur.accent
    val bg get() = cur.bg
    val field get() = cur.field
    val chip get() = cur.chip
    /** Акцент, подмешанный к белому: подзаголовки и ссылки на тёмном фоне. */
    val soft get() = ColorUtils.blendARGB(cur.accent, Color.WHITE, 0.45f)
    /** Акцент как «#rrggbb» — для страницы 3D-графа. */
    val accentHex get() = String.format("#%06X", cur.accent and 0xFFFFFF)

    /** Прочитать сохранённый выбор (вызывать в onCreate каждого экрана). */
    fun init(ctx: Context) {
        val id = ctx.applicationContext.getSharedPreferences("loopa", Context.MODE_PRIVATE).getString("theme", "black")
        cur = ALL.firstOrNull { it.id == id } ?: ALL[0]
    }

    fun set(ctx: Context, id: String) {
        ctx.applicationContext.getSharedPreferences("loopa", Context.MODE_PRIVATE).edit().putString("theme", id).apply()
        init(ctx)
    }

    /** Цвета строки состояния и панели навигации окна — под фон темы. */
    fun bars(a: Activity) { a.window.statusBarColor = bg; a.window.navigationBarColor = bg }

    fun tint() = ColorStateList.valueOf(accent)

    /** Переключатель: включён — акцент, выключен — серый (и ползунок, и дорожка). */
    fun switchTints(): Pair<ColorStateList, ColorStateList> {
        val st = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        return ColorStateList(st, intArrayOf(accent, 0xFFB0B5BD.toInt())) to
            ColorStateList(st, intArrayOf(ColorUtils.setAlphaComponent(accent, 0x80), 0xFF3A3F49.toInt()))
    }
}
