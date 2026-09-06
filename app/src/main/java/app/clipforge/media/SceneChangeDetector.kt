package app.clipforge.media

import com.arthenica.ffmpegkit.ReturnCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.roundToLong

enum class SceneMarkerKind {
    SCENE_CHANGE,
    BLACK,
}

data class SceneMarker(
    val timeMs: Long,
    val kind: SceneMarkerKind,
)

data class SceneDetectionResult(
    val markers: List<SceneMarker>,
    val scannedStartMs: Long,
    val scannedEndMs: Long,
)

private val showInfoPtsRegex = Regex("""pts_time:([0-9]+(?:\.[0-9]+)?)""")
private val blackStartRegex = Regex("""black_start:([0-9]+(?:\.[0-9]+)?)""")

internal fun parseSceneDetectionLog(log: String, offsetMs: Long): List<SceneMarker> = buildList {
    log.lineSequence().forEach { line ->
        if ("showinfo" in line) {
            showInfoPtsRegex.find(line)
                ?.groupValues
                ?.getOrNull(1)
                ?.toDoubleOrNull()
                ?.takeIf { it.isFinite() && it >= 0.0 }
                ?.let { seconds ->
                    add(SceneMarker(offsetMs + (seconds * 1000.0).roundToLong(), SceneMarkerKind.SCENE_CHANGE))
                }
        }
        blackStartRegex.find(line)
            ?.groupValues
            ?.getOrNull(1)
            ?.toDoubleOrNull()
            ?.takeIf { it.isFinite() && it >= 0.0 }
            ?.let { seconds ->
                add(SceneMarker(offsetMs + (seconds * 1000.0).roundToLong(), SceneMarkerKind.BLACK))
            }
    }
}.sortedWith(compareBy(SceneMarker::timeMs, SceneMarker::kind))
    .fold(mutableListOf()) { result, marker ->
        val duplicate = result.lastOrNull { it.kind == marker.kind }?.let { previous ->
            kotlin.math.abs(previous.timeMs - marker.timeMs) <= 120L
        } ?: false
        if (!duplicate) result += marker
        result
    }

class SceneChangeDetector {
    suspend fun detectPath(
        path: String,
        durationMs: Long,
        startMs: Long,
        endMs: Long,
        onProgress: (Double) -> Unit = {},
    ): SceneDetectionResult = withContext(Dispatchers.IO) {
        detect(
            inputArguments = listOf("-ss", seconds(startMs), "-i", path),
            durationMs = durationMs,
            startMs = startMs,
            endMs = endMs,
            onProgress = onProgress,
        )
    }

    suspend fun detectDescriptor(
        fd: Int,
        durationMs: Long,
        startMs: Long,
        endMs: Long,
        onProgress: (Double) -> Unit = {},
    ): SceneDetectionResult = withContext(Dispatchers.IO) {
        detect(
            inputArguments = listOf("-ss", seconds(startMs), "-fd", fd.toString(), "-i", "fd:"),
            durationMs = durationMs,
            startMs = startMs,
            endMs = endMs,
            onProgress = onProgress,
        )
    }

    private suspend fun detect(
        inputArguments: List<String>,
        durationMs: Long,
        startMs: Long,
        endMs: Long,
        onProgress: (Double) -> Unit,
    ): SceneDetectionResult {
        val safeDuration = durationMs.coerceAtLeast(1L)
        val safeStart = startMs.coerceIn(0L, safeDuration)
        val safeEnd = endMs.coerceIn(safeStart, safeDuration)
        if (safeEnd <= safeStart) {
            onProgress(1.0)
            return SceneDetectionResult(emptyList(), safeStart, safeEnd)
        }

        val arguments = mutableListOf(
            "-hide_banner",
            "-nostats",
        )
        arguments += inputArguments
        arguments += listOf(
            "-t", seconds(safeEnd - safeStart),
            "-map", "0:V:0",
            "-an", "-sn", "-dn",
            "-vf",
            "scale=320:-2:flags=fast_bilinear,setpts=PTS-STARTPTS," +
                "blackdetect=d=0.08:pic_th=0.98:pix_th=0.10," +
                "select=gt(scene\\,0.35),showinfo",
            "-f", "null",
            "-",
        )
        val session = executeCancellableFfmpeg(
            arguments = arguments,
            expectedDurationMs = safeEnd - safeStart,
            onProgress = onProgress,
        )
        if (!ReturnCode.isSuccess(session.returnCode)) {
            throw MediaCommandException(
                session.allLogsAsString.ifBlank { "シーン候補の解析に失敗しました" },
            )
        }
        return SceneDetectionResult(
            markers = parseSceneDetectionLog(session.allLogsAsString, safeStart)
                .filter { it.timeMs in safeStart..safeEnd },
            scannedStartMs = safeStart,
            scannedEndMs = safeEnd,
        )
    }

    private fun seconds(ms: Long): String =
        "%.3f".format(Locale.US, ms.coerceAtLeast(0L) / 1000.0)
}
