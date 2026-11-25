package app.loopa

import android.content.SharedPreferences

internal enum class TileRatio(val label: String, private val w: Int, private val h: Int) {
    SQUARE("1:1", 1, 1), PORTRAIT("3:4", 3, 4), TALL("9:16", 9, 16), WIDE("16:9", 16, 9);

    fun height(width: Int): Int = (width.coerceAtLeast(0).toLong() * h / w).coerceIn(1L, 0x3FFFFFFFL).toInt()
}

internal data class GridTileStyle(
    val portrait: Int = 2,
    val landscape: Int = 4,
    val ratio: TileRatio = TileRatio.PORTRAIT,
    val spacing: Int = 3
) {
    fun columns(wide: Boolean) = if (wide) landscape else portrait

    companion object {
        val PORTRAIT_COLUMNS = (1..4).toList()
        val LANDSCAPE_COLUMNS = (2..6).toList()
        val SPACING = listOf(0, 2, 3, 6)
        const val KEY_PORTRAIT = "grid_cols_portrait"
        const val KEY_LANDSCAPE = "grid_cols_landscape"
        const val KEY_RATIO = "grid_tile_ratio"
        const val KEY_SPACING = "grid_tile_spacing"

        fun normalize(portrait: Int, landscape: Int, ratio: String?, spacing: Int) = GridTileStyle(
            portrait.takeIf { it in PORTRAIT_COLUMNS } ?: 2,
            landscape.takeIf { it in LANDSCAPE_COLUMNS } ?: 4,
            TileRatio.values().firstOrNull { it.name == ratio } ?: TileRatio.PORTRAIT,
            spacing.takeIf { it in SPACING } ?: 3
        )

        fun read(prefs: SharedPreferences) = normalize(
            runCatching { prefs.getInt(KEY_PORTRAIT, 2) }.getOrDefault(2),
            runCatching { prefs.getInt(KEY_LANDSCAPE, 4) }.getOrDefault(4),
            runCatching { prefs.getString(KEY_RATIO, TileRatio.PORTRAIT.name) }.getOrNull(),
            runCatching { prefs.getInt(KEY_SPACING, 3) }.getOrDefault(3)
        )
    }
}
