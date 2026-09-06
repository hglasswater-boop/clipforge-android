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
