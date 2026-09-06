package app.clipforge.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoTrimStateStoreTest {
    @Test
    fun beginKeepsRunningStateVisibleAcrossUiRecreation() {
        AutoTrimStateStore.begin("session-a", nowElapsedMs = 1_000L)

        val state = AutoTrimStateStore.state.value
        assertTrue(state.visible)
        assertTrue(state.running)
        assertTrue(state.sessionPath == "session-a")
        assertEquals(0, state.progress?.percent)
    }

    @Test
    fun dismissDoesNotDiscardRunningAnalysisState() {
        AutoTrimStateStore.begin("session-b", nowElapsedMs = 1_000L)
        AutoTrimStateStore.dismiss("session-b")

        val state = AutoTrimStateStore.state.value
        assertFalse(state.visible)
        assertTrue(state.running)
        assertTrue(AutoTrimStateStore.show("session-b"))
        assertTrue(AutoTrimStateStore.state.value.visible)
    }

    @Test
    fun staleSessionCannotBeReopenedForAnotherEditor() {
        AutoTrimStateStore.begin("old-session", nowElapsedMs = 1_000L)
        assertFalse(AutoTrimStateStore.show("new-session"))
    }

    @Test
    fun progressNeverMovesBackwards() {
        AutoTrimStateStore.begin("session-progress", nowElapsedMs = 1_000L)
        AutoTrimStateStore.progress(
            sessionPath = "session-progress",
            phase = AutoTrimPhase.START_AUDIO,
            percent = 47,
            nowElapsedMs = 5_000L,
        )
        AutoTrimStateStore.progress(
            sessionPath = "session-progress",
            phase = AutoTrimPhase.END_SCENE,
            percent = 32,
            nowElapsedMs = 6_000L,
        )

        assertEquals(47, AutoTrimStateStore.state.value.progress?.percent)
    }

    @Test
    fun etaWaitsForEnoughElapsedTime() {
        val progress = AutoTrimProgress(
            phase = AutoTrimPhase.START_SCENE,
            percent = 20,
            startedAtElapsedMs = 1_000L,
            updatedAtElapsedMs = 2_000L,
        )

        assertNull(progress.estimatedRemainingMs(nowElapsedMs = 3_500L))
    }

    @Test
    fun etaUsesMeasuredElapsedTimeAndRealPercent() {
        val progress = AutoTrimProgress(
            phase = AutoTrimPhase.END_AUDIO,
            percent = 50,
            startedAtElapsedMs = 1_000L,
            updatedAtElapsedMs = 6_000L,
        )

        assertEquals(5_000L, progress.elapsedMs(nowElapsedMs = 6_000L))
        assertEquals(5_000L, progress.estimatedRemainingMs(nowElapsedMs = 6_000L))
    }
}
