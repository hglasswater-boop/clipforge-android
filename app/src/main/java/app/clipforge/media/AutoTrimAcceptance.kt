package app.clipforge.media

/**
 * Returns whether applying [candidate] would add any meaningful edge removal beyond [cutRanges].
 *
 * The delete list is the source of truth. This means removing an edge range from that list
 * immediately makes the matching auto-trim candidate adoptable again, without a second UI-only
 * accepted flag that could drift out of sync.
 */
internal fun isAutoTrimCandidateApplied(
    candidate: AutoTrimCandidate,
    durationMs: Long,
    cutRanges: List<MediaSegment>,
    cutMode: CutMode,
): Boolean {
    if (durationMs <= 0L || cutRanges.isEmpty()) return false
    val boundary = candidate.boundaryMs.coerceIn(0L, durationMs)
    val snapToleranceMs = if (cutMode == CutMode.LOSSLESS) LOSSLESS_EDGE_SNAP_TOLERANCE_MS else 0L

    return when (candidate.side) {
        AutoTrimSide.START -> cutRanges.any { range ->
            if (range.startMs != 0L) return@any false
            range.endMs >= boundary || boundary - range.endMs <= snapToleranceMs
        }

        AutoTrimSide.END -> cutRanges.any { range ->
            if (range.endMs != durationMs) return@any false
            range.startMs <= boundary || range.startMs - boundary <= snapToleranceMs
        }
    }
}

// Lossless mode may move a requested boundary to the nearest keyframe. Keep the UI tied to the
// resulting delete range while allowing a reasonably long GOP without introducing a separate flag.
private const val LOSSLESS_EDGE_SNAP_TOLERANCE_MS = 15_000L
