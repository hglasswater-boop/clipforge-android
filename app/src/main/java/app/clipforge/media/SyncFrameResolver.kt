package app.clipforge.media

import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.io.FileDescriptor

/** Resolves only the sync frames needed by the current trim handles. */
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

    private fun snapRange(
        extractor: MediaExtractor,
        durationMs: Long,
        requestedStartMs: Long,
        requestedEndMs: Long,
    ): LongRange {
        val duration = durationMs.coerceAtLeast(1L)
        var start = requestedStartMs.coerceIn(0L, duration - 1L)
        var end = requestedEndMs.coerceIn(start + 1L, duration)

        val videoTrack = (0 until extractor.trackCount).firstOrNull { index ->
            extractor.getTrackFormat(index)
                .getString(MediaFormat.KEY_MIME)
                ?.startsWith("video/") == true
        } ?: return start..end

        extractor.selectTrack(videoTrack)
        start = seekMs(extractor, start, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            ?.coerceIn(0L, duration - 1L)
            ?: start

        end = seekMs(extractor, end, MediaExtractor.SEEK_TO_NEXT_SYNC)
            ?.coerceIn(start + 1L, duration)
            ?: end.coerceIn(start + 1L, duration)

        return start..end
    }

    private fun seekMs(extractor: MediaExtractor, requestedMs: Long, mode: Int): Long? {
        extractor.seekTo(requestedMs * 1_000L, mode)
        return extractor.sampleTime.takeIf { it >= 0L }?.div(1_000L)
    }
}
