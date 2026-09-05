package app.clipforge.media

import kotlin.math.abs

/**
 * One piece of a smart-cut export. Copy pieces are guaranteed to begin/end on safe sync-frame
 * boundaries (apart from the physical start/end of the source). Reencode pieces cover only the
 * GOP fragments that contain an exact user-selected boundary.
 */
sealed interface SmartCutPart {
    val segment: MediaSegment

    data class Copy(override val segment: MediaSegment) : SmartCutPart
    data class Reencode(override val segment: MediaSegment) : SmartCutPart
}

/**
 * Splits one kept range into lossless-copy and boundary-reencode pieces.
 *
 * Sync positions are supplied by [SyncFrameResolver]. A tiny tolerance absorbs the millisecond
 * rounding that happens when Android's microsecond timestamps are exposed to the editor.
 */
internal fun planSmartCutSegment(
    segment: MediaSegment,
    durationMs: Long,
    startPreviousSyncMs: Long?,
    startNextSyncMs: Long?,
    endPreviousSyncMs: Long?,
    endNextSyncMs: Long?,
): List<SmartCutPart> {
    require(durationMs > 0L) { "durationMs must be > 0" }
    require(segment.endMs <= durationMs) { "segment must fit inside duration" }

    val startIsSync = segment.startMs == 0L ||
        isSameTimestamp(segment.startMs, startPreviousSyncMs) ||
        isSameTimestamp(segment.startMs, startNextSyncMs)
    val endIsSync = segment.endMs == durationMs ||
        isSameTimestamp(segment.endMs, endPreviousSyncMs) ||
        isSameTimestamp(segment.endMs, endNextSyncMs)

    if (startIsSync && endIsSync) return listOf(SmartCutPart.Copy(segment))

    val headEnd = if (startIsSync) {
        segment.startMs
    } else {
        (startNextSyncMs ?: segment.endMs).coerceIn(segment.startMs, segment.endMs)
    }
    val tailStart = if (endIsSync) {
        segment.endMs
    } else {
        (endPreviousSyncMs ?: segment.startMs).coerceIn(segment.startMs, segment.endMs)
    }

    // Both exact boundaries live in the same GOP, or the two boundary GOPs touch. Reencoding the
    // whole small kept range is simpler and avoids creating zero-length copy pieces.
    if (!startIsSync && !endIsSync && headEnd >= tailStart) {
        return listOf(SmartCutPart.Reencode(segment))
    }

    val parts = mutableListOf<SmartCutPart>()
    var cursor = segment.startMs

    if (!startIsSync && headEnd > cursor) {
        parts += SmartCutPart.Reencode(MediaSegment(cursor, headEnd))
        cursor = headEnd
    }

    val copyEnd = if (endIsSync) segment.endMs else tailStart
    if (copyEnd > cursor) {
        parts += SmartCutPart.Copy(MediaSegment(cursor, copyEnd))
        cursor = copyEnd
    }

    if (!endIsSync && segment.endMs > cursor) {
        parts += SmartCutPart.Reencode(MediaSegment(cursor, segment.endMs))
    }

    return parts.ifEmpty { listOf(SmartCutPart.Reencode(segment)) }
}

private fun isSameTimestamp(expectedMs: Long, actualMs: Long?): Boolean =
    actualMs != null && abs(expectedMs - actualMs) <= SYNC_TOLERANCE_MS

private const val SYNC_TOLERANCE_MS = 2L
