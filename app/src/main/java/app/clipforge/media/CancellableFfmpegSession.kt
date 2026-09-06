package app.clipforge.media

import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred

/**
 * Runs one FFmpeg command without using FFmpegKit.cancel(), which cancels every process-wide
 * session. Coroutine cancellation targets only the session created by this call.
 *
 * When [expectedDurationMs] is supplied, FFmpeg statistics are converted into real media-time
 * progress. The callback is never driven by a synthetic timer.
 */
internal suspend fun executeCancellableFfmpeg(
    arguments: List<String>,
    expectedDurationMs: Long? = null,
    onProgress: (Double) -> Unit = {},
): FFmpegSession {
    val completion = CompletableDeferred<FFmpegSession>()
    val safeDuration = expectedDurationMs?.takeIf { it > 0L }
    val session = FFmpegKit.executeWithArgumentsAsync(
        arguments.toTypedArray(),
        { completed -> completion.complete(completed) },
        null,
        { statistics ->
            val duration = safeDuration ?: return@executeWithArgumentsAsync
            if (statistics.time.isFinite()) {
                val fraction = (statistics.time / duration.toDouble()).coerceIn(0.0, 0.99)
                runCatching { onProgress(fraction) }
            }
        },
    )
    return try {
        val completed = completion.await()
        if (safeDuration != null) runCatching { onProgress(1.0) }
        completed
    } catch (cancelled: CancellationException) {
        FFmpegKit.cancel(session.sessionId)
        throw cancelled
    }
}
