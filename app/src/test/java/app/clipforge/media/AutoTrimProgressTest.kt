package app.clipforge.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoTrimProgressTest {
    @Test
    fun etaIsHiddenUntilEnoughRealWorkHasCompleted() {
        var now = 0L
        val tracker = AutoTrimProgressTracker { now }

        now = 1_000L
        val early = tracker.update(AutoTrimPhase.START_SCENE, 0.1)

        assertNull(early.remainingMs)
    }

    @Test
    fun etaUsesElapsedTimeAndActualPhaseProgress() {
        var now = 0L
        val tracker = AutoTrimProgressTracker { now }

        now = 2_000L
        val progress = tracker.update(AutoTrimPhase.START_SCENE, 0.5)

        assertEquals(10, progress.overallPercent)
        assertEquals(50, progress.phasePercent)
        assertEquals(2_000L, progress.elapsedMs)
        assertEquals(18_000L, progress.remainingMs)
    }

    @Test
    fun overallProgressNeverMovesBackwards() {
        var now = 0L
        val tracker = AutoTrimProgressTracker { now }

        now = 2_000L
        val laterPhase = tracker.update(AutoTrimPhase.END_AUDIO, 0.7)
        now = 3_000L
        val staleUpdate = tracker.update(AutoTrimPhase.START_SCENE, 0.2)

        assertTrue(laterPhase.overallPercent > 0)
        assertEquals(laterPhase.overallPercent, staleUpdate.overallPercent)
        assertNotNull(staleUpdate.remainingMs)
    }

    @Test
    fun completeAlwaysEndsAtHundredPercentAndZeroEta() {
        var now = 0L
        val tracker = AutoTrimProgressTracker { now }
        now = 5_000L

        val complete = tracker.update(AutoTrimPhase.COMPLETE, 1.0)

        assertEquals(100, complete.overallPercent)
        assertEquals(100, complete.phasePercent)
        assertEquals(0L, complete.remainingMs)
    }
}
