package app.clipforge.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoTrimSparseRefinementTest {
    @Test
    fun sparseVisualChangeProducesApproximateStartBoundary() {
        val hints = visualRefinementHints(
            side = AutoTrimSide.START,
            durationMs = 120_000L,
            samples = listOf(
                SparseVisualSample(0L, 0L, 70),
                SparseVisualSample(7_500L, 0L, 72),
                SparseVisualSample(15_000L, -1L, 74),
            ),
        )

        assertEquals(1, hints.size)
        assertEquals(11_250L, hints.single().timeMs)
    }

    @Test
    fun endOffsetsAreConvertedBackToAbsoluteTimeline() {
        val hints = visualRefinementHints(
            side = AutoTrimSide.END,
            durationMs = 100_000L,
            samples = listOf(
                SparseVisualSample(0L, 0L, 70),
                SparseVisualSample(7_500L, 0L, 70),
                SparseVisualSample(15_000L, -1L, 70),
            ),
        )

        assertEquals(1, hints.size)
        assertEquals(88_750L, hints.single().timeMs)
    }

    @Test
    fun strongKnownMatchSkipsExpensiveSceneRefinement() {
        val windows = buildSceneRefinementWindows(
            range = MediaSegment(0L, 300_000L),
            visualHints = listOf(SceneRefinementHint(120_000L, 200)),
            audioSignals = emptyList(),
            knownMatch = KnownClipMatch(boundaryMs = 95_000L, similarity = 0.95),
        )

        assertTrue(windows.isEmpty())
        assertTrue(shouldSkipSceneRefinement(KnownClipMatch(95_000L, 0.95)))
    }

    @Test
    fun refinementIsLimitedToSmallWindowsAroundBestHints() {
        val windows = buildSceneRefinementWindows(
            range = MediaSegment(0L, 300_000L),
            visualHints = listOf(
                SceneRefinementHint(30_000L, 300),
                SceneRefinementHint(60_000L, 290),
                SceneRefinementHint(90_000L, 280),
                SceneRefinementHint(120_000L, 270),
                SceneRefinementHint(150_000L, 260),
                SceneRefinementHint(180_000L, 250),
                SceneRefinementHint(210_000L, 240),
            ),
            audioSignals = emptyList(),
            knownMatch = null,
        )

        assertTrue(windows.size <= 5)
        assertTrue(windows.all { it.startMs >= 0L && it.endMs <= 300_000L })
        assertTrue(windows.sumOf { it.endMs - it.startMs } <= 60_000L)
    }

    @Test
    fun silenceBoundaryCanRequestRefinementWithoutVisualHint() {
        val windows = buildSceneRefinementWindows(
            range = MediaSegment(0L, 300_000L),
            visualHints = emptyList(),
            audioSignals = listOf(
                AutoTrimAudioSignal(80_000L, AutoTrimAudioSignalKind.SILENCE_START),
                AutoTrimAudioSignal(82_000L, AutoTrimAudioSignalKind.SILENCE_END),
            ),
            knownMatch = null,
        )

        assertEquals(1, windows.size)
        assertTrue(81_000L in windows.single().startMs..windows.single().endMs)
    }
}
