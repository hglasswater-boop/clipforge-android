package app.clipforge.media

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileDescriptor
import kotlin.math.roundToInt

class TimelineThumbnailGenerator {
    suspend fun generate(
        source: File,
        outputDir: File,
        durationMs: Long,
        count: Int = 8,
        onProgress: (completed: Int, total: Int) -> Unit = { _, _ -> },
    ): List<File> = withContext(Dispatchers.IO) {
        generateWithRetriever(
            outputDir = outputDir,
            durationMs = durationMs,
            count = count,
            onProgress = onProgress,
        ) { retriever ->
            retriever.setDataSource(source.absolutePath)
        }
    }

    suspend fun generate(
        sourceFd: FileDescriptor,
        outputDir: File,
        durationMs: Long,
        count: Int = 8,
        onProgress: (completed: Int, total: Int) -> Unit = { _, _ -> },
    ): List<File> = withContext(Dispatchers.IO) {
        generateWithRetriever(
            outputDir = outputDir,
            durationMs = durationMs,
            count = count,
            onProgress = onProgress,
        ) { retriever ->
            retriever.setDataSource(sourceFd)
        }
    }

    private fun generateWithRetriever(
        outputDir: File,
        durationMs: Long,
        count: Int,
        onProgress: (completed: Int, total: Int) -> Unit,
        configure: (MediaMetadataRetriever) -> Unit,
    ): List<File> {
        if (durationMs <= 0L || count <= 0) return emptyList()
        outputDir.mkdirs()
        val slots = List(count) { index -> File(outputDir, "%02d.jpg".format(index)) }

        val retriever = MediaMetadataRetriever()
        try {
            configure(retriever)
            slots.forEachIndexed { index, target ->
                try {
                    val timeMs = if (count == 1) {
                        durationMs / 2L
                    } else {
                        ((durationMs - 1L).coerceAtLeast(0L) * index) / (count - 1L)
                    }
                    val frame = runCatching {
                        retriever.getFrameAtTime(
                            timeMs * 1_000L,
                            MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                        )
                    }.getOrNull() ?: return@forEachIndexed

                    try {
                        val scaled = scaleForTimeline(frame)
                        try {
                            val written = target.outputStream().buffered().use { output ->
                                scaled.compress(Bitmap.CompressFormat.JPEG, 72, output)
                            }
                            if (!written || target.length() <= 0L) target.delete()
                        } finally {
                            if (scaled !== frame) scaled.recycle()
                        }
                    } finally {
                        frame.recycle()
                    }
                } finally {
                    onProgress(index + 1, count)
                }
            }
        } catch (_: Throwable) {
            slots.forEachIndexed { index, _ -> onProgress(index + 1, count) }
        } finally {
            runCatching { retriever.release() }
        }
        // Return every time slot, even when an individual JPEG could not be produced. The UI uses
        // missing files as placeholders so a failed decode cannot visually compress the timeline.
        return slots
    }

    private fun scaleForTimeline(frame: Bitmap): Bitmap {
        val targetHeight = 96
        if (frame.height <= targetHeight) return frame
        val targetWidth = (frame.width * (targetHeight / frame.height.toFloat()))
            .roundToInt()
            .coerceAtLeast(1)
        return Bitmap.createScaledBitmap(frame, targetWidth, targetHeight, true)
    }
}
