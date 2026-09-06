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

enum class SceneScanMode {
    AUTO,
    COARSE,
    PRECISE,
}

private data class SceneFilterSpec(
    val scaleWidth: Int,
    val blackDurationSeconds: String,
    val sceneThreshold: String,
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

internal fun resolvedSceneScanMode(
    requested: SceneScanMode,
    scanDurationMs: Long,
): SceneScanMode = when (requested) {
    SceneScanMode.AUTO -> if (scanDurationMs >= COARSE_SCAN_THRESHOLD_MS) {
        SceneScanMode.COARSE
    } else {
        SceneScanMode.PRECISE
    }
    else -> requested
}

private fun sceneFilterSpec(mode: SceneScanMode): SceneFilterSpec = when (mode) {
    SceneScanMode.COARSE -> SceneFilterSpec(
        scaleWidth = 240,
        blackDurationSeconds = "0.40",
        sceneThreshold = "0.40",
    )
    SceneScanMode.PRECISE, SceneScanMode.AUTO -> SceneFilterSpec(
        scaleWidth = 320,
        blackDurationSeconds = "0.08",
        sceneThreshold = "0.35",
    )
}

internal fun sceneVideoFilter(mode: SceneScanMode): String {
    val spec = sceneFilterSpec(mode)
    return "scale=${spec.scaleWidth}:-2:flags=fast_bilinear,setpts=PTS-STARTPTS," +
        "blackdetect=d=${spec.blackDurationSeconds}:pic_th=0.98:pix_th=0.10," +
        "select='gt(scene,${spec.sceneThreshold})',showinfo"
}

/**
 * Precise scene analysis drops almost every frame with select(). FFmpegKit statistics are based
 * on output timestamps, so a quiet window with no scene changes can otherwise look frozen at 0%
 * even while decoding is still making progress.
 *
 * Keep a tiny 1 fps progress branch as the only muxed output. The scene-change branch ends in
 * nullsink after showinfo, so it still emits scene logs but cannot pin the output timestamp at 0
 * when there are no scene changes. Both branches reuse the already scaled frames and no media
 * file is created.
 *
 * Use 0:V:0 rather than 0:v:0 so attached pictures / cover art are never selected as the movie.
 */
internal fun preciseSceneFilterGraph(): String {
    val spec = sceneFilterSpec(SceneScanMode.PRECISE)
    return "[0:V:0]scale=${spec.scaleWidth}:-2:flags=fast_bilinear,setpts=PTS-STARTPTS," +
        "blackdetect=d=${spec.blackDurationSeconds}:pic_th=0.98:pix_th=0.10," +
        "split=2[progress_src][scene_src];" +
        "[progress_src]fps=1[progress];" +
        "[scene_src]select='gt(scene,${spec.sceneThreshold})',showinfo,nullsink"
}

class SceneChangeDetector {
    suspend fun detectPath(
        path: String,
        durationMs: Long,
        startMs: Long,
        endMs: Long,
        mode: SceneScanMode = SceneScanMode.AUTO,
        onProgress: (Double) -> Unit = {},
    ): SceneDetectionResult = withContext(Dispatchers.IO) {
        val actualMode = resolvedSceneScanMode(mode, endMs - startMs)
        val inputArguments = mutableListOf("-ss", seconds(startMs))
        if (actualMode == SceneScanMode.COARSE) {
            inputArguments += listOf("-skip_frame", "nokey")
        }
        inputArguments += listOf("-i", path)
        detect(
            inputArguments = inputArguments,
            durationMs = durationMs,
            startMs = startMs,
            endMs = endMs,
            mode = actualMode,
            onProgress = onProgress,
        )
    }

    suspend fun detectDescriptor(
        fd: Int,
        durationMs: Long,
        startMs: Long,
        endMs: Long,
        mode: SceneScanMode = SceneScanMode.AUTO,
        onProgress: (Double) -> Unit = {},
    ): SceneDetectionResult = withContext(Dispatchers.IO) {
        val actualMode = resolvedSceneScanMode(mode, endMs - startMs)
        val inputArguments = mutableListOf("-ss", seconds(startMs))
        if (actualMode == SceneScanMode.COARSE) {
            inputArguments += listOf("-skip_frame", "nokey")
        }
        inputArguments += listOf("-fd", fd.toString(), "-i", "fd:")
        detect(
            inputArguments = inputArguments,
            durationMs = durationMs,
            startMs = startMs,
            endMs = endMs,
            mode = actualMode,
            onProgress = onProgress,
        )
    }

    private suspend fun detect(
        inputArguments: List<String>,
        durationMs: Long,
        startMs: Long,
        endMs: Long,
        mode: SceneScanMode,
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
        arguments += listOf("-t", seconds(safeEnd - safeStart))
        if (mode == SceneScanMode.PRECISE) {
            arguments += listOf(
                "-filter_complex", preciseSceneFilterGraph(),
                "-map", "[progress]",
            )
        } else {
            arguments += listOf(
                "-map", "0:V:0",
                "-vf", sceneVideoFilter(mode),
            )
        }
        arguments += listOf(
            "-an", "-sn", "-dn",
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

private const val COARSE_SCAN_THRESHOLD_MS = 90_000L
