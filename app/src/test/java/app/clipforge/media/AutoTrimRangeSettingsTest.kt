package app.clipforge.media

import org.junit.Assert.assertEquals
import org.junit.Test

class AutoTrimRangeSettingsTest {
    @Test
    fun defaultRangeIsFiveMinutes() {
        assertEquals(5 * 60_000L, AutoTrimRangeSettings.DEFAULT_EDGE_WINDOW_MS)
    }

    @Test
    fun rangeOptionsStayWithinExpectedChoices() {
        assertEquals(
            listOf(60_000L, 180_000L, 300_000L, 600_000L),
            AutoTrimRangeSettings.OPTIONS_MS,
        )
    }

    @Test
    fun unsupportedRangeIsNormalizedToNearestChoice() {
        assertEquals(300_000L, normalizeAutoTrimEdgeWindowMs(290_000L))
        assertEquals(600_000L, normalizeAutoTrimEdgeWindowMs(540_000L))
    }
}
