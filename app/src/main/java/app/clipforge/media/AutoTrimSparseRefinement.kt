package app.clipforge.media

import kotlin.math.abs

/**
 * One sparse, sync-frame sample. offsetFromEdgeMs is measured from the start for START samples
 * and backwards from the end for END samples. averageLuma is intentionally kept out of the
 * persisted fingerprint format; it is only used to cheaply find places worth precise decoding.
 */
internal data class SparseVisualSample(
    val offsetFromEdgeMs: Long,
    val hash: Long,
    val averageLuma: Int,
)

internal data class SceneRefinementHint(
    val timeMs: Long,
    val score: Int,
)

/**
 * Turns adjacent sparse sync-frame samples into a small set of likely visual boundaries.
 * The returned times are approximate. A short precise FFmpeg pass is used around them later.
 */
internal fun visualRefinementHints(
    side: AutoTrimSide,
    durationMs: Long,
    samples: List<SparseVisualSample>,
): List<SceneRefinementHint> {
    val timed = samples.map { sample ->
        val absoluteTime = when (side) {
            AutoTrimSide.START -> sample.offsetFromEdgeMs
            AutoTrimSide.END -> (durationMs - sample.offsetFromEdgeMs).coerceAtLeast(0L)
        }
        absoluteTime to sample
    }.sortedBy { it.first }

    return timed.zipWithNext().mapNotNull { (before, after) ->
        val bitDistance = java.lang.Long.bitCount(before.second.hash xor after.second.hash)
        val lumaJump = abs(before.second.averageLuma - after.second.averageLuma)
        val beforeBlack = before.second.averageLuma <= COARSE_BLACK_LUMA
        val afterBlack = after.second.averageLuma <= COARSE_BLACK_LUMA
        val blackTransition = beforeBlack != afterBlack
        if (
            bitDistance < MIN_HASH_DISTANCE &&
            lumaJump < MIN_LUMA_JUMP &&
            !blackTransition
        ) {
            return@mapNotNull null
        }

        val score = 80 +
            bitDistance * 4 +
            lumaJump / 2 +
            if (blackTransition) 60 else 0
        SceneRefinementHint(
            timeMs = (before.first + after.first) / 2L,
            score = score,
        )
    }
        .sortedByDescending(SceneRefinementHint::score)
        .take(MAX_VISUAL_HINTS)
}

/**
 * Builds only a handful of short ranges for expensive full-frame scene/black detection.
 * A very strong learned-clip match can skip scene refinement entirely because the persisted
 * fingerprint already gives a better boundary signal than another broad decode pass.
 */
internal fun buildSceneRefinementWindows(
    range: MediaSegment,
    visualHints: List<SceneRefinementHint>,
    audioSignals: List<AutoTrimAudioSignal>,
    knownMatch: KnownClipMatch?,
): List<MediaSegment> {
    if (shouldSkipSceneRefinement(knownMatch)) return emptyList()

    val audioHints = audioRefinementHints(audioSignals)
    val candidates = mutableListOf<SceneRefinementHint>()
    knownMatch?.let {
        candidates += SceneRefinementHint(
            timeMs = it.boundaryMs,
            score = KNOWN_MATCH_HINT_SCORE,
        )
    }
    visualHints.forEach { visual ->
        val hasNearbyAudio = audioSignals.any { abs(it.timeMs - visual.timeMs) <= AUDIO_VISUAL_BONUS_RADIUS_MS }
        candidates += if (hasNearbyAudio) visual.copy(score = visual.score + AUDIO_VISUAL_BONUS) else visual
    }
    candidates += audioHints

    val selectedCenters = mutableListOf<Long>()
    candidates
        .asSequence()
        .filter { it.timeMs in range.startMs..range.endMs }
        .sortedByDescending(SceneRefinementHint::score)
        .forEach { hint ->
            if (
                selectedCenters.size < MAX_REFINEMENT_WINDOWS &&
                selectedCenters.none { abs(it - hint.timeMs) < CENTER_DEDUP_MS }
            ) {
                selectedCenters += hint.timeMs
            }
        }

    val windows = selectedCenters
        .map { center ->
            MediaSegment(
                startMs = (center - REFINEMENT_RADIUS_MS).coerceAtLeast(range.startMs),
                endMs = (center + REFINEMENT_RADIUS_MS).coerceAtMost(range.endMs),
            )
        }
        .filter { it.endMs > it.startMs }
        .sortedBy(MediaSegment::startMs)

    if (windows.isEmpty()) return emptyList()
    val merged = mutableListOf<MediaSegment>()
    windows.forEach { window ->
        val previous = merged.lastOrNull()
        if (previous != null && window.startMs <= previous.endMs + WINDOW_MERGE_GAP_MS) {
            merged[merged.lastIndex] = MediaSegment(
                startMs = previous.startMs,
                endMs = maxOf(previous.endMs, window.endMs),
            )
        } else {
            merged += window
        }
    }
    return merged
}

internal fun shouldSkipSceneRefinement(match: KnownClipMatch?): Boolean =
    (match?.similarity ?: 0.0) >= KNOWN_FAST_PATH_SIMILARITY

private fun audioRefinementHints(signals: List<AutoTrimAudioSignal>): List<SceneRefinementHint> {
    if (signals.isEmpty()) return emptyList()
    val clusters = mutableListOf<MutableList<AutoTrimAudioSignal>>()
    signals.sortedBy(AutoTrimAudioSignal::timeMs).forEach { signal ->
        val current = clusters.lastOrNull()
        if (current == null || signal.timeMs - current.last().timeMs > AUDIO_CLUSTER_MS) {
            clusters += mutableListOf(signal)
        } else {
            current += signal
        }
    }

    return clusters.map { cluster ->
        val ordered = cluster.sortedBy(AutoTrimAudioSignal::timeMs)
        val score = cluster.sumOf { signal ->
            when (signal.kind) {
                AutoTrimAudioSignalKind.SILENCE_START,
                AutoTrimAudioSignalKind.SILENCE_END -> 70
                AutoTrimAudioSignalKind.LEVEL_JUMP -> 20
            }
        }.coerceAtMost(180)
        SceneRefinementHint(
            timeMs = ordered[ordered.size / 2].timeMs,
            score = score,
        )
    }
        .sortedByDescending(SceneRefinementHint::score)
        .take(MAX_AUDIO_HINTS)
}

private const val MIN_HASH_DISTANCE = 8
private const val MIN_LUMA_JUMP = 28
private const val COARSE_BLACK_LUMA = 18
private const val MAX_VISUAL_HINTS = 6
private const val MAX_AUDIO_HINTS = 4
private const val MAX_REFINEMENT_WINDOWS = 5
private const val REFINEMENT_RADIUS_MS = 6_000L
private const val CENTER_DEDUP_MS = 10_000L
private const val WINDOW_MERGE_GAP_MS = 1_000L
private const val AUDIO_CLUSTER_MS = 4_000L
private const val AUDIO_VISUAL_BONUS_RADIUS_MS = 8_000L
private const val AUDIO_VISUAL_BONUS = 50
private const val KNOWN_MATCH_HINT_SCORE = 1_000
private const val KNOWN_FAST_PATH_SIMILARITY = 0.92
