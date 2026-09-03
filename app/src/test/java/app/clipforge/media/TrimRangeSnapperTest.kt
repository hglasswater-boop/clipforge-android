package app.clipforge.media

import org.junit.Assert.assertEquals
import org.junit.Test

class TrimRangeSnapperTest {
    @Test
    fun snapsBothHandlesToActualCutPoints() {
        val result = TrimRangeSnapper.snap(
            durationMs = 10_000L,
            keyframesMs = listOf(0L, 2_000L, 4_000L, 6_000L, 8_000L),
            requestedStartMs = 2_500L,
            requestedEndMs = 7_300L
        )

        assertEquals(SnappedTrimRange(2_000L, 8_000L), result)
    }

    @Test
    fun allowsDurationAsFinalOutPoint() {
        val result = TrimRangeSnapper.snap(
            durationMs = 10_000L,
            keyframesMs = listOf(0L, 2_000L, 4_000L, 6_000L, 8_000L),
            requestedStartMs = 8_400L,
            requestedEndMs = 9_900L
        )

        assertEquals(SnappedTrimRange(8_000L, 10_000L), result)
    }

    @Test
    fun keepsFreeSelectionWhenKeyframesAreUnavailable() {
        val result = TrimRangeSnapper.snap(
            durationMs = 10_000L,
            keyframesMs = emptyList(),
            requestedStartMs = 1_234L,
            requestedEndMs = 8_765L
        )

        assertEquals(SnappedTrimRange(1_234L, 8_765L), result)
    }

    @Test
    fun keepsOutPointAfterInPoint() {
        val result = TrimRangeSnapper.snap(
            durationMs = 10_000L,
            keyframesMs = listOf(0L, 2_000L, 4_000L, 6_000L, 8_000L),
            requestedStartMs = 5_000L,
            requestedEndMs = 5_000L
        )

        assertEquals(4_000L, result.startMs)
        assertEquals(6_000L, result.endMs)
    }
}
