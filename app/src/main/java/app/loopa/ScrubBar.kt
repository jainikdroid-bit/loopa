package app.loopa

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.view.Gravity
import android.widget.SeekBar

/**
 * Ползунок перемотки плеера: дорожка 6 dp, крупный бегунок, высота (зона касания) 52 dp. Под прогрессом — что уже
 * загружено: буфер плеера ([setLoaded]) и разрывные куски из дискового кэша ([CacheMap]), цвета из [Theme].
 */
class ScrubBar(ctx: Context) : SeekBar(ctx) {
    private val dm = ctx.resources.displayMetrics
    private fun dp(v: Int) = (v * dm.density).toInt()
    private val loaded = Loaded(dp(3).toFloat())

    init {
        val track = dp(6)
        val bg = GradientDrawable().apply { cornerRadius = track / 2f; setColor(0x44FFFFFF) }
        val fg = ClipDrawable(GradientDrawable().apply { cornerRadius = track / 2f; setColor(Theme.accent) }, Gravity.START, ClipDrawable.HORIZONTAL)
        val layers = LayerDrawable(arrayOf(bg, loaded, fg)).apply {
            setId(0, android.R.id.background); setId(1, android.R.id.secondaryProgress); setId(2, android.R.id.progress)
            for (i in 0 until numberOfLayers) { setLayerHeight(i, track); setLayerGravity(i, Gravity.CENTER_VERTICAL) }
        }
        progressDrawable = layers
        thumb = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Theme.accent); setStroke(dp(2), 0xFFFFFFFF.toInt()); setSize(dp(22), dp(22)) }
        splitTrack = false
        setPadding(dp(11), 0, dp(11), 0)
    }

    /** Буфер плеера (доля 0…1 — от начала до загруженного места) и куски кэша в долях длины. */
    fun setLoaded(buffered: Float, parts: List<CacheMap.Part>) = loaded.set(buffered, parts)

    /** Загруженное: буфер плеера светлее, куски кэша — цветом темы с прозрачностью; обрезано по скруглённой дорожке. */
    private class Loaded(private val r: Float) : Drawable() {
        private var buffered = 0f
        private var parts: List<CacheMap.Part> = emptyList()
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val clip = Path()
        fun set(b: Float, ps: List<CacheMap.Part>) {
            if (b == buffered && ps == parts) return
            buffered = b; parts = ps; invalidateSelf()
        }
        override fun draw(c: Canvas) {
            val b = bounds
            if (b.isEmpty) return
            clip.rewind(); clip.addRoundRect(RectF(b), r, r, Path.Direction.CW)
            c.save(); c.clipPath(clip)
            val w = b.width().toFloat()
            p.color = 0x55FFFFFF
            if (buffered > 0f) c.drawRect(b.left.toFloat(), b.top.toFloat(), b.left + w * buffered.coerceAtMost(1f), b.bottom.toFloat(), p)
            p.color = androidx.core.graphics.ColorUtils.setAlphaComponent(Theme.soft, 0x99)
            parts.forEach { c.drawRect(b.left + w * it.from, b.top.toFloat(), maxOf(b.left + w * it.to, b.left + w * it.from + 2f), b.bottom.toFloat(), p) }
            c.restore()
        }
        override fun setAlpha(a: Int) {}
        override fun setColorFilter(f: ColorFilter?) {}
        @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
    }
}
