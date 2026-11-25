package app.loopa

import org.junit.Assert.*
import org.junit.Test

class GridTileStyleTest {
    @Test fun originalDefaultsPreserved() {
        val style = GridTileStyle()
        assertEquals(2, style.columns(false))
        assertEquals(4, style.columns(true))
        assertEquals(3, style.spacing)
        assertEquals(400, style.ratio.height(300))
    }

    @Test fun ratiosAndOrientation() {
        val style = GridTileStyle.normalize(3, 5, "WIDE", 6)
        assertEquals(3, style.columns(false))
        assertEquals(5, style.columns(true))
        assertEquals(180, style.ratio.height(320))
        assertEquals(320, TileRatio.SQUARE.height(320))
        assertEquals(320, TileRatio.TALL.height(180))
        assertEquals(6, style.spacing)
    }

    @Test fun invalidPreferencesFallBackToOriginalLayout() {
        assertEquals(GridTileStyle(), GridTileStyle.normalize(0, 99, "unknown", -5))
        assertEquals(1, TileRatio.TALL.height(0))
        assertTrue(TileRatio.TALL.height(Int.MAX_VALUE) > 0)
    }
}
