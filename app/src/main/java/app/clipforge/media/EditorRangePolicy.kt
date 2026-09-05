package app.clipforge.media

/**
 * Keeps a valid half-open trim range while moving one marker past the other.
 *
 * Rather than throwing the opposite marker to the start/end of the whole video, crossing markers
 * simply swaps the selected interval so the range stays local to the user's gesture.
 */
internal fun rangeAfterSettingStart(
    durationMs: Long,
    currentStartMs: Long,
    currentEndMs: Long,
    positionMs: Long,
): MediaSegment {
    require(durationMs > 0L) { "durationMs must be > 0" }
    val currentStart = currentStartMs.coerceIn(0L, durationMs - 1L)
    val currentEnd = currentEndMs.coerceIn(currentStart + 1L, durationMs)
    val position = positionMs.coerceIn(0L, durationMs - 1L)

    return when {
        position < currentEnd -> MediaSegment(position, currentEnd)
        position > currentEnd -> MediaSegment(currentEnd.coerceAtMost(durationMs - 1L), position)
        else -> MediaSegment(position, (position + 1L).coerceAtMost(durationMs))
    }
}

internal fun rangeAfterSettingEnd(
    durationMs: Long,
    currentStartMs: Long,
    currentEndMs: Long,
    positionMs: Long,
): MediaSegment {
    require(durationMs > 0L) { "durationMs must be > 0" }
    val currentStart = currentStartMs.coerceIn(0L, durationMs - 1L)
    currentEndMs.coerceIn(currentStart + 1L, durationMs) // Validate the current range.
    val position = positionMs.coerceIn(1L, durationMs)

    return when {
        position > currentStart -> MediaSegment(currentStart, position)
        position < currentStart -> MediaSegment(position, currentStart)
        else -> MediaSegment(currentStart, (currentStart + 1L).coerceAtMost(durationMs))
    }
}
