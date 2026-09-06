package app.clipforge.media

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class AutoTrimStateStoreTest {
    private val roots = mutableListOf<File>()

    @After
    fun tearDown() {
        AutoTrimStateStore.resetForTest()
        roots.forEach { it.deleteRecursively() }
    }

    @Test
    fun beginKeepsRunningStateVisibleAcrossUiRecreation() {
        val session = session("a")
        AutoTrimStateStore.begin(session)

        val state = AutoTrimStateStore.state.value
        assertTrue(state.visible)
        assertTrue(state.running)
        assertEquals(session, state.sessionPath)
        assertEquals(0, state.progress?.overallPercent)
        assertEquals(AutoTrimPhase.PREPARING, state.progress?.phase)
    }

    @Test
    fun dismissDoesNotDiscardRunningAnalysisState() {
        val session = session("b")
        AutoTrimStateStore.begin(session)
        AutoTrimStateStore.dismiss(session)

        val state = AutoTrimStateStore.state.value
        assertFalse(state.visible)
        assertTrue(state.running)
        assertTrue(AutoTrimStateStore.show(session))
        assertTrue(AutoTrimStateStore.state.value.visible)
    }

    @Test
    fun staleSessionCannotBeReopenedForAnotherEditor() {
        val oldSession = session("old")
        val newSession = session("new")
        AutoTrimStateStore.begin(oldSession)
        assertFalse(AutoTrimStateStore.show(newSession))
    }

    @Test
    fun progressNeverMovesBackwards() {
        val session = session("progress")
        AutoTrimStateStore.begin(session)
        AutoTrimStateStore.updateProgress(
            session,
            AutoTrimProgress(
                phase = AutoTrimPhase.START_AUDIO,
                overallPercent = 47,
                phasePercent = 80,
                elapsedMs = 8_000L,
                remainingMs = 9_000L,
            ),
        )
        AutoTrimStateStore.updateProgress(
            session,
            AutoTrimProgress(
                phase = AutoTrimPhase.END_SCENE,
                overallPercent = 32,
                phasePercent = 100,
                elapsedMs = 9_000L,
                remainingMs = 10_000L,
            ),
        )

        val progress = AutoTrimStateStore.state.value.progress
        assertEquals(47, progress?.overallPercent)
        assertEquals(AutoTrimPhase.START_AUDIO, progress?.phase)
    }

    @Test
    fun runningProgressSurvivesInMemoryStoreReset() {
        val session = session("progress-restore")
        AutoTrimStateStore.begin(session)
        val expected = AutoTrimProgress(
            phase = AutoTrimPhase.END_AUDIO,
            overallPercent = 61,
            phasePercent = 80,
            elapsedMs = 12_500L,
            remainingMs = 8_200L,
        )
        AutoTrimStateStore.updateProgress(session, expected)

        AutoTrimStateStore.resetForTest()
        assertTrue(AutoTrimStateStore.restore(session))

        val restored = AutoTrimStateStore.state.value
        assertTrue(restored.running)
        assertEquals(expected, restored.progress)
    }

    @Test
    fun completedAnalysisSurvivesInMemoryStoreReset() {
        val session = session("restore")
        val expected = sampleAnalysis()
        AutoTrimStateStore.begin(session)
        AutoTrimStateStore.ready(session, expected)
        AutoTrimStateStore.dismiss(session)

        AutoTrimStateStore.resetForTest()
        assertTrue(AutoTrimStateStore.restore(session))

        val restored = AutoTrimStateStore.state.value
        assertEquals(session, restored.sessionPath)
        assertFalse(restored.visible)
        assertFalse(restored.running)
        assertEquals(100, restored.progress?.overallPercent)
        assertEquals(AutoTrimPhase.COMPLETE, restored.progress?.phase)
        assertEquals(expected.startCandidates, restored.analysis?.startCandidates)
        assertEquals(expected.endCandidates, restored.analysis?.endCandidates)
        assertEquals(expected.startFingerprint, restored.analysis?.startFingerprint)
        assertEquals(expected.endFingerprint, restored.analysis?.endFingerprint)
        assertEquals(expected.scannedStart, restored.analysis?.scannedStart)
        assertEquals(expected.scannedEnd, restored.analysis?.scannedEnd)
    }

    @Test
    fun corruptedSnapshotIsIgnoredInsteadOfCrashingEditorRestore() {
        val session = session("corrupt")
        File(session, ".auto-trim-state-v1.bin").writeBytes(byteArrayOf(1, 2, 3, 4, 5))

        AutoTrimStateStore.resetForTest()
        assertFalse(AutoTrimStateStore.restore(session))
        assertNotNull(AutoTrimStateStore.state.value)
    }

    private fun session(name: String): String {
        val root = Files.createTempDirectory("clipforge-auto-trim-$name-").toFile()
        roots += root
        return root.absolutePath
    }

    private fun sampleAnalysis(): AutoTrimAnalysis = AutoTrimAnalysis(
        startCandidates = listOf(
            AutoTrimCandidate(
                side = AutoTrimSide.START,
                boundaryMs = 91_250L,
                confidence = 0.94,
                evidence = setOf(AutoTrimEvidence.KNOWN_CLIP, AutoTrimEvidence.AUDIO_CHANGE),
                knownClipSimilarity = 0.87,
            ),
        ),
        endCandidates = listOf(
            AutoTrimCandidate(
                side = AutoTrimSide.END,
                boundaryMs = 8_812_000L,
                confidence = 0.72,
                evidence = setOf(AutoTrimEvidence.BLACK_FRAME, AutoTrimEvidence.SILENCE_BOUNDARY),
            ),
        ),
        startFingerprint = EdgeFingerprintSnapshot(
            side = AutoTrimSide.START,
            edgeDurationMs = 600_000L,
            visual = listOf(VisualFingerprintPoint(0L, 1234L), VisualFingerprintPoint(5_000L, -44L)),
            audio = listOf(AudioFingerprintPoint(0L, -18.5), AudioFingerprintPoint(1_000L, -22.0)),
        ),
        endFingerprint = EdgeFingerprintSnapshot(
            side = AutoTrimSide.END,
            edgeDurationMs = 600_000L,
            visual = listOf(VisualFingerprintPoint(0L, 9988L)),
            audio = listOf(AudioFingerprintPoint(0L, -30.25)),
        ),
        scannedStart = MediaSegment(0L, 600_000L),
        scannedEnd = MediaSegment(8_400_000L, 9_000_000L),
    )
}
