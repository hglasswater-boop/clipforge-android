package app.clipforge.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoTrimAcceptanceTest {
    private val durationMs = 600_000L

    @Test
    fun `start candidate is applied when delete list contains the edge range`() {
        val candidate = candidate(AutoTrimSide.START, 45_000L)

        assertTrue(
            isAutoTrimCandidateApplied(
                candidate = candidate,
                durationMs = durationMs,
                cutRanges = listOf(MediaSegment(0L, 45_000L)),
                cutMode = CutMode.SMART,
            ),
        )
    }

    @Test
    fun `removing the edge range makes start candidate adoptable again`() {
        val candidate = candidate(AutoTrimSide.START, 45_000L)

        assertFalse(
            isAutoTrimCandidateApplied(
                candidate = candidate,
                durationMs = durationMs,
                cutRanges = emptyList(),
                cutMode = CutMode.SMART,
            ),
        )
    }

    @Test
    fun `larger start removal also covers an earlier candidate`() {
        val candidate = candidate(AutoTrimSide.START, 45_000L)

        assertTrue(
            isAutoTrimCandidateApplied(
                candidate = candidate,
                durationMs = durationMs,
                cutRanges = listOf(MediaSegment(0L, 70_000L)),
                cutMode = CutMode.SMART,
            ),
        )
    }

    @Test
    fun `internal delete range does not mark edge candidate as applied`() {
        val candidate = candidate(AutoTrimSide.START, 45_000L)

        assertFalse(
            isAutoTrimCandidateApplied(
                candidate = candidate,
                durationMs = durationMs,
                cutRanges = listOf(MediaSegment(20_000L, 70_000L)),
                cutMode = CutMode.SMART,
            ),
        )
    }

    @Test
    fun `end candidate follows an end anchored delete range`() {
        val candidate = candidate(AutoTrimSide.END, 540_000L)

        assertTrue(
            isAutoTrimCandidateApplied(
                candidate = candidate,
                durationMs = durationMs,
                cutRanges = listOf(MediaSegment(540_000L, durationMs)),
                cutMode = CutMode.SMART,
            ),
        )
    }

    @Test
    fun `lossless keyframe snap may undershoot requested edge within tolerance`() {
        val startCandidate = candidate(AutoTrimSide.START, 45_000L)
        val endCandidate = candidate(AutoTrimSide.END, 540_000L)

        assertTrue(
            isAutoTrimCandidateApplied(
                candidate = startCandidate,
                durationMs = durationMs,
                cutRanges = listOf(MediaSegment(0L, 35_000L)),
                cutMode = CutMode.LOSSLESS,
            ),
        )
        assertTrue(
            isAutoTrimCandidateApplied(
                candidate = endCandidate,
                durationMs = durationMs,
                cutRanges = listOf(MediaSegment(550_000L, durationMs)),
                cutMode = CutMode.LOSSLESS,
            ),
        )
    }

    @Test
    fun `lossless snap tolerance does not hide a materially different candidate`() {
        val candidate = candidate(AutoTrimSide.START, 45_000L)

        assertFalse(
            isAutoTrimCandidateApplied(
                candidate = candidate,
                durationMs = durationMs,
                cutRanges = listOf(MediaSegment(0L, 20_000L)),
                cutMode = CutMode.LOSSLESS,
            ),
        )
    }

    private fun candidate(side: AutoTrimSide, boundaryMs: Long) = AutoTrimCandidate(
        side = side,
        boundaryMs = boundaryMs,
        confidence = 0.9,
        evidence = setOf(AutoTrimEvidence.SCENE_CHANGE),
    )
}
