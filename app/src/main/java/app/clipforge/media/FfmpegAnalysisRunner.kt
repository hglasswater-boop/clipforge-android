package app.clipforge.media

import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegSession
import com.arthenica.ffmpegkit.ReturnCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred

/**
 * Runs one FFmpeg analysis session with real timestamp-based progress reporting.
 *
 * Cancellation is scoped to this session id. Never call the global FFmpegKit.cancel() from
 * auto-trim because export/cut operations may be running in another service at the same time.
 */
internal suspend fun runFfmpegAnalysis(
    arguments: List<String>,
    expectedDurationMs: Long,
    failureMessage: String,
    onProgress: (Double) -> Unit = {},
): FFmpegSession {
    val completion = CompletableDeferred<FFmpegSession>()
    val safeDurationMs = expectedDurationMs.coerceAtLeast(1L)
    val session = FFmpegKit.executeWithArgumentsAsync(
        arguments.toTypedArray(),
        { completed -> completion.complete(completed) },
        null,
        { statistics ->
            if (statistics.time.isFinite()) {
                val fraction = (statistics.time / safeDurationMs.toDouble())
                    .coerceIn(0.0, 0.99)
                runCatching { onProgress(fraction) }
            }
        },
    )

    try {
        val completed = completion.await()
        if (!ReturnCode.isSuccess(completed.returnCode)) {
            throw MediaCommandException(
                completed.allLogsAsString.ifBlank { failureMessage },
            )
        }
        runCatching { onProgress(1.0) }
        return completed
    } catch (cancelled: CancellationException) {
        FFmpegKit.cancel(session.sessionId)
        throw cancelled
    }
}
