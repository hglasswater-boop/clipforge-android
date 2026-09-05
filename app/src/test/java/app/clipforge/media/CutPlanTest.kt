package app.clipforge.media

import org.junit.Assert.assertEquals
import org.junit.Test

class CutPlanTest {
    @Test
    fun mergesOverlappingAndTouchingCutRanges() {
        val normalized = normalizeCutRanges(
            durationMs = 10_000L,
            ranges = listOf(
                MediaSegment(4_000L, 6_000L),
                MediaSegment(1_000L, 2_000L),
                MediaSegment(1_500L, 3_000L),
                MediaSegment(6_000L, 7_000L),
            ),
        )

        assertEquals(
            listOf(
                MediaSegment(1_000L, 3_000L),
                MediaSegment(4_000L, 7_000L),
            ),
            normalized,
        )
    }

    @Test
    fun returnsSegmentsLeftAfterSeveralCuts() {
        val remaining = remainingSegments(
            durationMs = 10_000L,
            cutRanges = listOf(
                MediaSegment(1_000L, 2_000L),
                MediaSegment(4_000L, 6_000L),
                MediaSegment(8_000L, 10_000L),
            ),
        )

        assertEquals(
            listOf(
                MediaSegment(0L, 1_000L),
                MediaSegment(2_000L, 4_000L),
                MediaSegment(6_000L, 8_000L),
            ),
            remaining,
        )
    }

    @Test
    fun fullVideoCutLeavesNothing() {
        assertEquals(
            emptyList<MediaSegment>(),
            remainingSegments(5_000L, listOf(MediaSegment(0L, 5_000L))),
        )
    }
}
