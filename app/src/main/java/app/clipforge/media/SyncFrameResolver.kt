package app.clipforge.media

import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.io.FileDescriptor

/** Resolves only the sync frames needed by the current trim operation. */
class SyncFrameResolver {
    fun snapRange(
        source: File,
        durationMs: Long,
        requestedStartMs: Long,
        requestedEndMs: Long,
    ): LongRange {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(source.absolutePath)
            snapRange(extractor, durationMs, requestedStartMs, requestedEndMs)
        } finally {
            extractor.release()
        }
    }

    fun snapRange(
        sourceFd: FileDescriptor,
        durationMs: Long,
        requestedStartMs: Long,
        requestedEndMs: Long,
    ): LongRange {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(sourceFd)
            snapRange(extractor, durationMs, requestedStartMs, requestedEndMs)
        } finally {
            extractor.release()
        }
    }

    fun adjacentSyncFrame(
        source: File,
        durationMs: Long,
        positionMs: Long,
        forward: Boolean,
    ): Long? {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(source.absolutePath)
            adjacentSyncFrame(extractor, durationMs, positionMs, forward)
        } finally {
            extractor.release()
        }
    }

    fun adjacentSyncFrame(
        sourceFd: FileDescriptor,
        durationMs: Long,
        positionMs: Long,
        forward: Boolean,
    ): Long? {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(sourceFd)
            adjacentSyncFrame(extractor, durationMs, positionMs, forward)
        } finally {
            extractor.release()
        }
    }

    private fun snapRange(
        extractor: MediaExtractor,
        durationMs: Long,
        requestedStartMs: Long,
        requestedEndMs: Long,
    ): LongRange {
        val duration = durationMs.coerceAtLeast(1L)
        var start = requestedStartMs.coerceIn(0L, duration - 1L)
        var end = requestedEndMs.coerceIn(start + 1L, duration)

        if (!selectVideoTrack(extractor)) return start..end

        start = seekMs(extractor, start, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            ?.coerceIn(0L, duration - 1L)
            ?: start

        end = seekMs(extractor, end, MediaExtractor.SEEK_TO_NEXT_SYNC)
            ?.coerceIn(start + 1L, duration)
            ?: end.coerceIn(start + 1L, duration)

        return start..end
    }

    private fun adjacentSyncFrame(
        extractor: MediaExtractor,
        durationMs: Long,
        positionMs: Long,
        forward: Boolean,
    ): Long? {
        val duration = durationMs.coerceAtLeast(1L)
        if (!selectVideoTrack(extractor)) return null

        val current = positionMs.coerceIn(0L, duration)
        val probe = if (forward) {
            (current + 1L).coerceAtMost(duration)
        } else {
            (current - 1L).coerceAtLeast(0L)
        }
        val mode = if (forward) {
            MediaExtractor.SEEK_TO_NEXT_SYNC
        } else {
            MediaExtractor.SEEK_TO_PREVIOUS_SYNC
        }
        return seekMs(extractor, probe, mode)
            ?.coerceIn(0L, duration)
            ?.takeIf { target -> if (forward) target > current else target < current }
    }

    private fun selectVideoTrack(extractor: MediaExtractor): Boolean {
        val videoTrack = (0 until extractor.trackCount).firstOrNull { index ->
            extractor.getTrackFormat(index)
                .getString(MediaFormat.KEY_MIME)
                ?.startsWith("video/") == true
        } ?: return false
        extractor.selectTrack(videoTrack)
        return true
    }

    private fun seekMs(extractor: MediaExtractor, requestedMs: Long, mode: Int): Long? {
        extractor.seekTo(requestedMs * 1_000L, mode)
        return extractor.sampleTime.takeIf { it >= 0L }?.div(1_000L)
    }
}
