package app.clipforge.media

import kotlin.math.abs
import kotlin.math.max

enum class AutoTrimSide {
    START,
    END,
}

enum class AutoTrimEvidence {
    KNOWN_CLIP,
    SCENE_CHANGE,
    BLACK_FRAME,
    AUDIO_CHANGE,
    SILENCE_BOUNDARY,
    SCENE_DENSITY,
}

data class AutoTrimCandidate(
    val side: AutoTrimSide,
    val boundaryMs: Long,
    val confidence: Double,
    val evidence: Set<AutoTrimEvidence>,
    val knownClipSimilarity: Double? = null,
)

enum class AutoTrimAudioSignalKind {
    LEVEL_JUMP,
    SILENCE_START,
    SILENCE_END,
}

data class AutoTrimAudioSignal(
    val timeMs: Long,
    val kind: AutoTrimAudioSignalKind,
)

data class VisualFingerprintPoint(
    val offsetFromEdgeMs: Long,
    val hash: Long,
)

data class AudioFingerprintPoint(
    val offsetFromEdgeMs: Long,
    val rmsDb: Double,
)

data class EdgeFingerprintSnapshot(
    val side: AutoTrimSide,
    val edgeDurationMs: Long,
    val visual: List<VisualFingerprintPoint>,
    val audio: List<AudioFingerprintPoint>,
)

data class KnownClipFingerprint(
    val side: AutoTrimSide,
    val clipDurationMs: Long,
    val visual: List<VisualFingerprintPoint>,
    val audio: List<AudioFingerprintPoint>,
    val createdAtEpochMs: Long,
)

data class KnownClipMatch(
    val boundaryMs: Long,
    val similarity: Double,
)

internal fun rankAutoTrimCandidates(
    side: AutoTrimSide,
    durationMs: Long,
    windowStartMs: Long,
    windowEndMs: Long,
    sceneMarkers: List<SceneMarker>,
    audioSignals: List<AutoTrimAudioSignal>,
    knownClipMatch: KnownClipMatch? = null,
): List<AutoTrimCandidate> {
    if (durationMs <= 1L || windowEndMs <= windowStartMs) return emptyList()

    val candidates = mutableListOf<AutoTrimCandidate>()
    knownClipMatch?.let { match ->
        val safeBoundary = match.boundaryMs.coerceIn(1L, durationMs - 1L)
        candidates += AutoTrimCandidate(
            side = side,
            boundaryMs = safeBoundary,
            confidence = (0.78 + match.similarity.coerceIn(0.0, 1.0) * 0.20).coerceAtMost(0.98),
            evidence = setOf(AutoTrimEvidence.KNOWN_CLIP),
            knownClipSimilarity = match.similarity,
        )
    }

    val eventTimes = buildList {
        addAll(sceneMarkers.map(SceneMarker::timeMs))
        addAll(audioSignals.map(AutoTrimAudioSignal::timeMs))
    }
        .filter { it in windowStartMs..windowEndMs }
        .sorted()

    val clusteredTimes = clusterTimes(eventTimes, EVENT_CLUSTER_MS)
    clusteredTimes.forEach { boundary ->
        if (boundary <= 3_000L || boundary >= durationMs - 3_000L) return@forEach

        val nearbyScenes = sceneMarkers.filter { abs(it.timeMs - boundary) <= EVENT_CLUSTER_MS }
        val nearbyAudio = audioSignals.filter { abs(it.timeMs - boundary) <= EVENT_CLUSTER_MS }
        val hasScene = nearbyScenes.any { it.kind == SceneMarkerKind.SCENE_CHANGE }
        val hasBlack = nearbyScenes.any { it.kind == SceneMarkerKind.BLACK }
        val hasAudioJump = nearbyAudio.any { it.kind == AutoTrimAudioSignalKind.LEVEL_JUMP }
        val hasSilence = nearbyAudio.any {
            it.kind == AutoTrimAudioSignalKind.SILENCE_START || it.kind == AutoTrimAudioSignalKind.SILENCE_END
        }

        var score = 0.18
        val evidence = linkedSetOf<AutoTrimEvidence>()
        if (hasScene) {
            score += 0.16
            evidence += AutoTrimEvidence.SCENE_CHANGE
        }
        if (hasBlack) {
            score += 0.24
            evidence += AutoTrimEvidence.BLACK_FRAME
        }
        if (hasAudioJump) {
            score += 0.22
            evidence += AutoTrimEvidence.AUDIO_CHANGE
        }
        if (hasSilence) {
            score += 0.16
            evidence += AutoTrimEvidence.SILENCE_BOUNDARY
        }
        if ((hasScene || hasBlack) && (hasAudioJump || hasSilence)) score += 0.10

        val beforeDensity = sceneDensity(sceneMarkers, boundary - DENSITY_WINDOW_MS, boundary)
        val afterDensity = sceneDensity(sceneMarkers, boundary, boundary + DENSITY_WINDOW_MS)
        val densityAdvantage = when (side) {
            AutoTrimSide.START -> beforeDensity - afterDensity
            AutoTrimSide.END -> afterDensity - beforeDensity
        }
        if (densityAdvantage >= 2) {
            score += (densityAdvantage.coerceAtMost(6) / 6.0) * 0.16
            evidence += AutoTrimEvidence.SCENE_DENSITY
        }

        if (evidence.isEmpty()) return@forEach
        candidates += AutoTrimCandidate(
            side = side,
            boundaryMs = boundary,
            confidence = score.coerceIn(0.35, 0.91),
            evidence = evidence,
        )
    }

    val ordered = candidates.sortedWith(
        compareByDescending<AutoTrimCandidate> { AutoTrimEvidence.KNOWN_CLIP in it.evidence }
            .thenByDescending(AutoTrimCandidate::confidence),
    )
    val separated = mutableListOf<AutoTrimCandidate>()
    ordered.forEach { candidate ->
        if (separated.none { abs(it.boundaryMs - candidate.boundaryMs) < MIN_CANDIDATE_SEPARATION_MS }) {
            separated += candidate
        }
    }
    return separated.take(MAX_CANDIDATES_PER_SIDE)
}

internal fun matchKnownFingerprint(
    current: EdgeFingerprintSnapshot,
    known: KnownClipFingerprint,
): Double? {
    if (current.side != known.side || known.clipDurationMs <= 0L) return null

    val knownVisual = known.visual.filter { it.offsetFromEdgeMs <= known.clipDurationMs }
    val currentVisual = current.visual.filter { it.offsetFromEdgeMs <= known.clipDurationMs + VISUAL_PAIR_TOLERANCE_MS }
    val visualScores = knownVisual.mapNotNull { expected ->
        val actual = currentVisual.minByOrNull { abs(it.offsetFromEdgeMs - expected.offsetFromEdgeMs) }
            ?.takeIf { abs(it.offsetFromEdgeMs - expected.offsetFromEdgeMs) <= VISUAL_PAIR_TOLERANCE_MS }
            ?: return@mapNotNull null
        val bitDistance = java.lang.Long.bitCount(expected.hash xor actual.hash)
        (1.0 - bitDistance / 64.0).coerceIn(0.0, 1.0)
    }
    if (visualScores.size < MIN_VISUAL_PAIRS) return null
    val visualScore = visualScores.average()

    val knownAudio = known.audio.filter { it.offsetFromEdgeMs <= known.clipDurationMs }
    val currentAudio = current.audio.filter { it.offsetFromEdgeMs <= known.clipDurationMs + AUDIO_PAIR_TOLERANCE_MS }
    val audioScores = knownAudio.mapNotNull { expected ->
        val actual = currentAudio.minByOrNull { abs(it.offsetFromEdgeMs - expected.offsetFromEdgeMs) }
            ?.takeIf { abs(it.offsetFromEdgeMs - expected.offsetFromEdgeMs) <= AUDIO_PAIR_TOLERANCE_MS }
            ?: return@mapNotNull null
        (1.0 - abs(expected.rmsDb - actual.rmsDb) / 20.0).coerceIn(0.0, 1.0)
    }

    val combined = if (audioScores.size >= MIN_AUDIO_PAIRS) {
        visualScore * 0.75 + audioScores.average() * 0.25
    } else {
        visualScore
    }
    return combined.takeIf { it >= KNOWN_MATCH_THRESHOLD }
}

private fun clusterTimes(times: List<Long>, toleranceMs: Long): List<Long> {
    if (times.isEmpty()) return emptyList()
    val clusters = mutableListOf<MutableList<Long>>()
    times.forEach { time ->
        val last = clusters.lastOrNull()
        if (last == null || time - last.last() > toleranceMs) {
            clusters += mutableListOf(time)
        } else {
            last += time
        }
    }
    return clusters.map { cluster -> cluster[cluster.size / 2] }
}

private fun sceneDensity(markers: List<SceneMarker>, startMs: Long, endMs: Long): Int {
    val safeStart = max(0L, startMs)
    return markers.count {
        it.kind == SceneMarkerKind.SCENE_CHANGE && it.timeMs in safeStart until endMs
    }
}

private const val EVENT_CLUSTER_MS = 1_500L
private const val DENSITY_WINDOW_MS = 60_000L
private const val MIN_CANDIDATE_SEPARATION_MS = 5_000L
private const val MAX_CANDIDATES_PER_SIDE = 3
private const val VISUAL_PAIR_TOLERANCE_MS = 4_000L
private const val AUDIO_PAIR_TOLERANCE_MS = 1_500L
private const val MIN_VISUAL_PAIRS = 3
private const val MIN_AUDIO_PAIRS = 3
private const val KNOWN_MATCH_THRESHOLD = 0.78
