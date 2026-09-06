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

private data class RankedCandidate(
    val candidate: AutoTrimCandidate,
    val directionalTransitionStrength: Double,
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

    val ranked = mutableListOf<RankedCandidate>()
    knownClipMatch?.let { match ->
        val safeBoundary = match.boundaryMs.coerceIn(1L, durationMs - 1L)
        ranked += RankedCandidate(
            candidate = AutoTrimCandidate(
                side = side,
                boundaryMs = safeBoundary,
                confidence = (0.78 + match.similarity.coerceIn(0.0, 1.0) * 0.20).coerceAtMost(0.98),
                evidence = setOf(AutoTrimEvidence.KNOWN_CLIP),
                knownClipSimilarity = match.similarity,
            ),
            directionalTransitionStrength = 1.0,
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

        val transitionStrength = directionalTransitionStrength(
            side = side,
            boundaryMs = boundary,
            windowStartMs = windowStartMs,
            windowEndMs = windowEndMs,
            sceneMarkers = sceneMarkers,
            audioSignals = audioSignals,
        )
        // Directional activity is useful when it agrees with an edge transition, but appended clips
        // can be either busier or quieter than the main feature. Never veto a strong local boundary
        // just because the activity direction is opposite to the historical heuristic.
        if (transitionStrength >= MIN_DIRECTIONAL_TRANSITION_STRENGTH) {
            score += (0.10 + transitionStrength * 0.14).coerceAtMost(MAX_DIRECTIONAL_BONUS)
            evidence += AutoTrimEvidence.SCENE_DENSITY
        }

        if (evidence.isEmpty()) return@forEach
        ranked += RankedCandidate(
            candidate = AutoTrimCandidate(
                side = side,
                boundaryMs = boundary,
                confidence = score.coerceIn(0.35, 0.91),
                evidence = evidence,
            ),
            directionalTransitionStrength = transitionStrength.coerceAtLeast(0.0),
        )
    }

    val ordered = ranked.sortedWith(
        compareByDescending<RankedCandidate> {
            AutoTrimEvidence.KNOWN_CLIP in it.candidate.evidence
        }
            .thenByDescending { it.candidate.confidence }
            .thenByDescending(RankedCandidate::directionalTransitionStrength),
    )
    val separated = mutableListOf<AutoTrimCandidate>()
    ordered.forEach { rankedCandidate ->
        val candidate = rankedCandidate.candidate
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

/**
 * Adds a modest preference for the historically common edge profile without assuming it is
 * universal. START often transitions from a busy intro to the main feature; END often transitions
 * from the main feature into a busier trailer/outro. Real appended clips can also be quieter, so
 * opposite direction is intentionally neutral rather than a rejection signal.
 */
private fun directionalTransitionStrength(
    side: AutoTrimSide,
    boundaryMs: Long,
    windowStartMs: Long,
    windowEndMs: Long,
    sceneMarkers: List<SceneMarker>,
    audioSignals: List<AutoTrimAudioSignal>,
): Double {
    val beforeStart = max(windowStartMs, boundaryMs - TRANSITION_ACTIVITY_WINDOW_MS)
    val beforeEnd = (boundaryMs - TRANSITION_GUARD_MS).coerceAtLeast(beforeStart)
    val afterStart = (boundaryMs + TRANSITION_GUARD_MS).coerceAtMost(windowEndMs)
    val afterEnd = minOf(windowEndMs, boundaryMs + TRANSITION_ACTIVITY_WINDOW_MS)

    val before = activityDensity(beforeStart, beforeEnd, sceneMarkers, audioSignals)
    val after = activityDensity(afterStart, afterEnd, sceneMarkers, audioSignals)
    val total = before + after
    if (total < MIN_TOTAL_ACTIVITY_DENSITY) return 0.0

    val desiredDelta = when (side) {
        AutoTrimSide.START -> before - after
        AutoTrimSide.END -> after - before
    }
    return (desiredDelta / total).coerceIn(-1.0, 1.0)
}

private fun activityDensity(
    startMs: Long,
    endMs: Long,
    sceneMarkers: List<SceneMarker>,
    audioSignals: List<AutoTrimAudioSignal>,
): Double {
    if (endMs <= startMs) return 0.0

    val audioWeight = audioSignals.asSequence()
        .filter { it.timeMs in startMs until endMs }
        .sumOf { signal ->
            when (signal.kind) {
                AutoTrimAudioSignalKind.LEVEL_JUMP -> 1.0
                AutoTrimAudioSignalKind.SILENCE_START,
                AutoTrimAudioSignalKind.SILENCE_END -> 1.15
            }
        }
    val sceneWeight = sceneMarkers.asSequence()
        .filter { it.timeMs in startMs until endMs }
        .sumOf { marker ->
            when (marker.kind) {
                SceneMarkerKind.SCENE_CHANGE -> 0.30
                SceneMarkerKind.BLACK -> 0.55
            }
        }

    val minutes = ((endMs - startMs).toDouble() / 60_000.0).coerceAtLeast(MIN_DENSITY_WINDOW_MINUTES)
    return (audioWeight + sceneWeight) / minutes
}

private const val EVENT_CLUSTER_MS = 1_500L
private const val TRANSITION_ACTIVITY_WINDOW_MS = 45_000L
private const val TRANSITION_GUARD_MS = 2_000L
private const val MIN_DENSITY_WINDOW_MINUTES = 0.20
private const val MIN_TOTAL_ACTIVITY_DENSITY = 0.50
private const val MIN_DIRECTIONAL_TRANSITION_STRENGTH = 0.22
private const val MAX_DIRECTIONAL_BONUS = 0.24
private const val MIN_CANDIDATE_SEPARATION_MS = 5_000L
private const val MAX_CANDIDATES_PER_SIDE = 3
private const val VISUAL_PAIR_TOLERANCE_MS = 4_000L
private const val AUDIO_PAIR_TOLERANCE_MS = 1_500L
private const val MIN_VISUAL_PAIRS = 3
private const val MIN_AUDIO_PAIRS = 3
private const val KNOWN_MATCH_THRESHOLD = 0.78
