package app.clipforge.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoTrimStateStoreTest {
    @Test
    fun beginKeepsRunningStateVisibleAcrossUiRecreation() {
        AutoTrimStateStore.begin("session-a")

        val state = AutoTrimStateStore.state.value
        assertTrue(state.visible)
        assertTrue(state.running)
        assertTrue(state.sessionPath == "session-a")
    }

    @Test
    fun dismissDoesNotDiscardRunningAnalysisState() {
        AutoTrimStateStore.begin("session-b")
        AutoTrimStateStore.dismiss("session-b")

        val state = AutoTrimStateStore.state.value
        assertFalse(state.visible)
        assertTrue(state.running)
        assertTrue(AutoTrimStateStore.show("session-b"))
        assertTrue(AutoTrimStateStore.state.value.visible)
    }

    @Test
    fun staleSessionCannotBeReopenedForAnotherEditor() {
        AutoTrimStateStore.begin("old-session")
        assertFalse(AutoTrimStateStore.show("new-session"))
    }
}
