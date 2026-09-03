package app.clipforge.media

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

class TimelineThumbnailGenerator {
    suspend fun generate(
        source: File,
        outputDir: File,
        durationMs: Long,
        count: Int = 8
    ): List<File> = withContext(Dispatchers.IO) {
        if (durationMs <= 0L || count <= 0) return@withContext emptyList()
        outputDir.mkdirs()

        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(source.absolutePath)
            buildList {
                repeat(count) { index ->
                    val timeMs = if (count == 1) {
                        durationMs / 2L
                    } else {
                        ((durationMs - 1L).coerceAtLeast(0L) * index) / (count - 1L)
                    }
                    val frame = runCatching {
                        retriever.getFrameAtTime(
                            timeMs * 1_000L,
                            MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                        )
                    }.getOrNull() ?: return@repeat

                    try {
                        val scaled = scaleForTimeline(frame)
                        try {
                            val target = File(outputDir, "%02d.jpg".format(index))
                            target.outputStream().buffered().use { output ->
                                scaled.compress(Bitmap.CompressFormat.JPEG, 72, output)
                            }
                            if (target.length() > 0L) add(target) else target.delete()
                        } finally {
                            if (scaled !== frame) scaled.recycle()
                        }
                    } finally {
                        frame.recycle()
                    }
                }
            }
        } catch (_: Throwable) {
            emptyList()
        } finally {
            runCatching { retriever.release() }
        }
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
