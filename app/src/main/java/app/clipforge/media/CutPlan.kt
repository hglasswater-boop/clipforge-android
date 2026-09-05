package app.clipforge.media

enum class CutMode {
    SMART,
    LOSSLESS,
}

/** Half-open media range: [startMs, endMs). */
data class MediaSegment(
    val startMs: Long,
    val endMs: Long,
) {
    init {
        require(startMs >= 0L) { "startMs must be >= 0" }
        require(endMs > startMs) { "endMs must be greater than startMs" }
    }

    val durationMs: Long get() = endMs - startMs
}

/**
 * Normalizes user-selected deletion ranges. Ranges are clamped to the source duration, sorted,
 * and overlapping/touching ranges are merged so export never creates zero-length keep segments.
 */
internal fun normalizeCutRanges(
    durationMs: Long,
    ranges: List<MediaSegment>,
): List<MediaSegment> {
    require(durationMs > 0L) { "durationMs must be > 0" }
    val clamped = ranges.mapNotNull { range ->
        val start = range.startMs.coerceIn(0L, durationMs)
        val end = range.endMs.coerceIn(0L, durationMs)
        if (end <= start) null else MediaSegment(start, end)
    }.sortedWith(compareBy(MediaSegment::startMs, MediaSegment::endMs))

    if (clamped.isEmpty()) return emptyList()
    val merged = mutableListOf<MediaSegment>()
    clamped.forEach { range ->
        val previous = merged.lastOrNull()
        if (previous == null || range.startMs > previous.endMs) {
            merged += range
        } else {
            merged[merged.lastIndex] = MediaSegment(
                startMs = previous.startMs,
                endMs = maxOf(previous.endMs, range.endMs),
            )
        }
    }
    return merged
}

/** Returns the source ranges that must be preserved after all deletion ranges are applied. */
internal fun remainingSegments(
    durationMs: Long,
    cutRanges: List<MediaSegment>,
): List<MediaSegment> {
    require(durationMs > 0L) { "durationMs must be > 0" }
    val cuts = normalizeCutRanges(durationMs, cutRanges)
    val keep = mutableListOf<MediaSegment>()
    var cursor = 0L
    cuts.forEach { cut ->
        if (cursor < cut.startMs) keep += MediaSegment(cursor, cut.startMs)
        cursor = maxOf(cursor, cut.endMs)
    }
    if (cursor < durationMs) keep += MediaSegment(cursor, durationMs)
    return keep
}
