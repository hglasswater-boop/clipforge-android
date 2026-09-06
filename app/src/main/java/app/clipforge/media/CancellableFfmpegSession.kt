package app.clipforge.media

import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred

/**
 * Runs one FFmpeg command without using FFmpegKit.cancel(), which cancels every process-wide
 * session. Coroutine cancellation targets only the session created by this call.
 */
internal suspend fun executeCancellableFfmpeg(arguments: List<String>): FFmpegSession {
    val completion = CompletableDeferred<FFmpegSession>()
    val session = FFmpegKit.executeWithArgumentsAsync(
        arguments.toTypedArray(),
        { completed -> completion.complete(completed) },
    )
    return try {
        completion.await()
    } catch (cancelled: CancellationException) {
        FFmpegKit.cancel(session.sessionId)
        throw cancelled
    }
}
