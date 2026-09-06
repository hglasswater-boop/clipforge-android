package app.clipforge.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class AutoTrimHeuristicsTest {
    @Test
    fun `known clip match is ranked first`() {
        val candidates = rankAutoTrimCandidates(
            side = AutoTrimSide.START,
            durationMs = 20 * 60_000L,
            windowStartMs = 0L,
            windowEndMs = 10 * 60_000L,
            sceneMarkers = listOf(
                SceneMarker(180_000L, SceneMarkerKind.SCENE_CHANGE),
            ),
            audioSignals = emptyList(),
            knownClipMatch = KnownClipMatch(
                boundaryMs = 92_000L,
                similarity = 0.92,
            ),
        )

        assertEquals(92_000L, candidates.first().boundaryMs)
        assertTrue(AutoTrimEvidence.KNOWN_CLIP in candidates.first().evidence)
        assertTrue(candidates.first().confidence >= 0.95)
    }

    @Test
    fun `visual and audio discontinuities reinforce one boundary`() {
        val candidates = rankAutoTrimCandidates(
            side = AutoTrimSide.START,
            durationMs = 12 * 60_000L,
            windowStartMs = 0L,
            windowEndMs = 6 * 60_000L,
            sceneMarkers = listOf(
                SceneMarker(60_000L, SceneMarkerKind.SCENE_CHANGE),
                SceneMarker(60_250L, SceneMarkerKind.BLACK),
            ),
            audioSignals = listOf(
                AutoTrimAudioSignal(60_500L, AutoTrimAudioSignalKind.LEVEL_JUMP),
                AutoTrimAudioSignal(60_700L, AutoTrimAudioSignalKind.SILENCE_END),
            ),
        )

        val candidate = candidates.first()
        assertTrue(abs(candidate.boundaryMs - 60_250L) <= 1_000L)
        assertTrue(AutoTrimEvidence.SCENE_CHANGE in candidate.evidence)
        assertTrue(AutoTrimEvidence.BLACK_FRAME in candidate.evidence)
        assertTrue(AutoTrimEvidence.AUDIO_CHANGE in candidate.evidence)
        assertTrue(AutoTrimEvidence.SILENCE_BOUNDARY in candidate.evidence)
        assertTrue(candidate.confidence >= 0.80)
    }

    @Test
    fun `end ranking prefers entry into appended clip over a stronger internal cut`() {
        val candidates = rankAutoTrimCandidates(
            side = AutoTrimSide.END,
            durationMs = 600_000L,
            windowStartMs = 300_000L,
            windowEndMs = 600_000L,
            sceneMarkers = listOf(
                SceneMarker(360_000L, SceneMarkerKind.SCENE_CHANGE),
                SceneMarker(450_000L, SceneMarkerKind.SCENE_CHANGE),
                SceneMarker(450_150L, SceneMarkerKind.BLACK),
            ),
            audioSignals = listOf(
                AutoTrimAudioSignal(360_200L, AutoTrimAudioSignalKind.LEVEL_JUMP),
                AutoTrimAudioSignal(370_000L, AutoTrimAudioSignalKind.LEVEL_JUMP),
                AutoTrimAudioSignal(380_000L, AutoTrimAudioSignalKind.LEVEL_JUMP),
                AutoTrimAudioSignal(390_000L, AutoTrimAudioSignalKind.SILENCE_START),
                AutoTrimAudioSignal(400_000L, AutoTrimAudioSignalKind.SILENCE_END),
                AutoTrimAudioSignal(420_000L, AutoTrimAudioSignalKind.LEVEL_JUMP),
                AutoTrimAudioSignal(430_000L, AutoTrimAudioSignalKind.LEVEL_JUMP),
                AutoTrimAudioSignal(440_000L, AutoTrimAudioSignalKind.LEVEL_JUMP),
                AutoTrimAudioSignal(450_250L, AutoTrimAudioSignalKind.LEVEL_JUMP),
                AutoTrimAudioSignal(460_000L, AutoTrimAudioSignalKind.LEVEL_JUMP),
                AutoTrimAudioSignal(470_000L, AutoTrimAudioSignalKind.LEVEL_JUMP),
                AutoTrimAudioSignal(480_000L, AutoTrimAudioSignalKind.SILENCE_START),
                AutoTrimAudioSignal(490_000L, AutoTrimAudioSignalKind.SILENCE_END),
            ),
        )

        assertTrue(abs(candidates.first().boundaryMs - 360_000L) <= 1_000L)
        assertTrue(AutoTrimEvidence.SCENE_DENSITY in candidates.first().evidence)
    }

    @Test
    fun `end ranking keeps evidence score ordering when no directional profile exists`() {
        val candidates = rankAutoTrimCandidates(
            side = AutoTrimSide.END,
            durationMs = 600_000L,
            windowStartMs = 300_000L,
            windowEndMs = 600_000L,
            sceneMarkers = listOf(
                SceneMarker(420_000L, SceneMarkerKind.SCENE_CHANGE),
                SceneMarker(500_000L, SceneMarkerKind.SCENE_CHANGE),
                SceneMarker(500_100L, SceneMarkerKind.BLACK),
            ),
            audioSignals = emptyList(),
        )

        assertTrue(abs(candidates.first().boundaryMs - 500_000L) <= 1_000L)
    }

    @Test
    fun `quiet appended clip hard boundary outranks opposite activity direction`() {
        // Regression profile captured from a real tail sample where the desired boundary is about
        // 04:50.6. The main feature is much more active than the appended clip, so the old END
        // direction assumption produced transitionStrength=-1 and incorrectly demoted the cut.
        val candidates = rankAutoTrimCandidates(
            side = AutoTrimSide.END,
            durationMs = 370_573L,
            windowStartMs = 70_573L,
            windowEndMs = 370_573L,
            sceneMarkers = listOf(
                SceneMarker(171_573L, SceneMarkerKind.SCENE_CHANGE),
                SceneMarker(284_153L, SceneMarkerKind.BLACK),
                SceneMarker(290_526L, SceneMarkerKind.BLACK),
                SceneMarker(290_526L, SceneMarkerKind.SCENE_CHANGE),
            ),
            audioSignals = listOf(
                AutoTrimAudioSignal(171_573L, AutoTrimAudioSignalKind.LEVEL_JUMP),
                AutoTrimAudioSignal(181_573L, AutoTrimAudioSignalKind.LEVEL_JUMP),
                AutoTrimAudioSignal(188_573L, AutoTrimAudioSignalKind.SILENCE_START),
                AutoTrimAudioSignal(190_573L, AutoTrimAudioSignalKind.SILENCE_END),
                AutoTrimAudioSignal(250_573L, AutoTrimAudioSignalKind.LEVEL_JUMP),
                AutoTrimAudioSignal(258_163L, AutoTrimAudioSignalKind.SILENCE_END),
                AutoTrimAudioSignal(258_173L, AutoTrimAudioSignalKind.SILENCE_START),
                AutoTrimAudioSignal(260_083L, AutoTrimAudioSignalKind.SILENCE_END),
                AutoTrimAudioSignal(260_134L, AutoTrimAudioSignalKind.SILENCE_START),
                AutoTrimAudioSignal(262_573L, AutoTrimAudioSignalKind.LEVEL_JUMP),
                AutoTrimAudioSignal(263_573L, AutoTrimAudioSignalKind.LEVEL_JUMP),
                AutoTrimAudioSignal(284_950L, AutoTrimAudioSignalKind.SILENCE_START),
                AutoTrimAudioSignal(285_573L, AutoTrimAudioSignalKind.LEVEL_JUMP),
                AutoTrimAudioSignal(286_573L, AutoTrimAudioSignalKind.LEVEL_JUMP),
                AutoTrimAudioSignal(287_573L, AutoTrimAudioSignalKind.LEVEL_JUMP),
                AutoTrimAudioSignal(290_573L, AutoTrimAudioSignalKind.LEVEL_JUMP),
                AutoTrimAudioSignal(290_638L, AutoTrimAudioSignalKind.SILENCE_END),
                AutoTrimAudioSignal(362_573L, AutoTrimAudioSignalKind.LEVEL_JUMP),
            ),
        )

        val best = candidates.first()
        assertTrue(abs(best.boundaryMs - 290_573L) <= 1_000L)
        assertTrue(AutoTrimEvidence.SCENE_CHANGE in best.evidence)
        assertTrue(AutoTrimEvidence.BLACK_FRAME in best.evidence)
        assertTrue(AutoTrimEvidence.AUDIO_CHANGE in best.evidence)
        assertTrue(AutoTrimEvidence.SILENCE_BOUNDARY in best.evidence)
    }

    @Test
    fun `similar visual fingerprint survives small encoding differences`() {
        val known = KnownClipFingerprint(
            side = AutoTrimSide.START,
            clipDurationMs = 15_000L,
            visual = listOf(
                VisualFingerprintPoint(0L, 0x0F0F0F0F0F0F0F0FL),
                VisualFingerprintPoint(5_000L, 0x3333333333333333L),
                VisualFingerprintPoint(10_000L, 0x5555555555555555L),
            ),
            audio = listOf(
                AudioFingerprintPoint(0L, -18.0),
                AudioFingerprintPoint(5_000L, -20.0),
                AudioFingerprintPoint(10_000L, -19.0),
            ),
            createdAtEpochMs = 1L,
        )
        val current = EdgeFingerprintSnapshot(
            side = AutoTrimSide.START,
            edgeDurationMs = 30_000L,
            visual = listOf(
                VisualFingerprintPoint(200L, 0x0F0F0F0F0F0F0F0EL),
                VisualFingerprintPoint(5_200L, 0x3333333333333331L),
                VisualFingerprintPoint(10_100L, 0x5555555555555554L),
            ),
            audio = listOf(
                AudioFingerprintPoint(100L, -18.5),
                AudioFingerprintPoint(5_100L, -20.5),
                AudioFingerprintPoint(10_200L, -18.5),
            ),
        )

        val similarity = matchKnownFingerprint(current, known)
        assertTrue(similarity != null && similarity > 0.95)
    }

    @Test
    fun `unrelated visual fingerprint is rejected`() {
        val known = KnownClipFingerprint(
            side = AutoTrimSide.END,
            clipDurationMs = 15_000L,
            visual = listOf(
                VisualFingerprintPoint(0L, 0x0000000000000000L),
                VisualFingerprintPoint(5_000L, 0x1111111111111111L),
                VisualFingerprintPoint(10_000L, 0x2222222222222222L),
            ),
            audio = emptyList(),
            createdAtEpochMs = 1L,
        )
        val current = EdgeFingerprintSnapshot(
            side = AutoTrimSide.END,
            edgeDurationMs = 30_000L,
            visual = listOf(
                VisualFingerprintPoint(0L, -1L),
                VisualFingerprintPoint(5_000L, -0x1111111111111112L),
                VisualFingerprintPoint(10_000L, -0x2222222222222223L),
            ),
            audio = emptyList(),
        )

        assertNull(matchKnownFingerprint(current, known))
    }
}
