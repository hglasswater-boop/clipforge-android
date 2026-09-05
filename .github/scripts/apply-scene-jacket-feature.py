from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def text(path: str) -> str:
    return (ROOT / path).read_text()


def write(path: str, value: str) -> None:
    target = ROOT / path
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(value)


def replace_once(path: str, old: str, new: str) -> None:
    value = text(path)
    count = value.count(old)
    if count != 1:
        raise RuntimeError(f"{path}: expected one occurrence, found {count}: {old[:80]!r}")
    write(path, value.replace(old, new, 1))


def replace_block(path: str, start: str, end: str, new_block: str) -> None:
    value = text(path)
    start_index = value.find(start)
    if start_index < 0:
        raise RuntimeError(f"{path}: start marker not found: {start!r}")
    end_index = value.find(end, start_index)
    if end_index < 0:
        raise RuntimeError(f"{path}: end marker not found: {end!r}")
    write(path, value[:start_index] + new_block + value[end_index:])


# ---------------------------------------------------------------------------
# Scene / black-frame detection
# ---------------------------------------------------------------------------
write(
    "app/src/main/java/app/clipforge/media/SceneChangeDetector.kt",
    r'''package app.clipforge.media

import com.arthenica.ffmpegkit.FFmpegKit
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

private val showInfoPtsRegex = Regex("""pts_time:([0-9]+(?:\\.[0-9]+)?)""")
private val blackStartRegex = Regex("""black_start:([0-9]+(?:\\.[0-9]+)?)""")

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
        val duplicate = result.lastOrNull()?.let { previous ->
            previous.kind == marker.kind && kotlin.math.abs(previous.timeMs - marker.timeMs) <= 120L
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
    ): SceneDetectionResult = withContext(Dispatchers.IO) {
        detect(
            inputArguments = listOf("-ss", seconds(startMs), "-i", path),
            durationMs = durationMs,
            startMs = startMs,
            endMs = endMs,
        )
    }

    suspend fun detectDescriptor(
        fd: Int,
        durationMs: Long,
        startMs: Long,
        endMs: Long,
    ): SceneDetectionResult = withContext(Dispatchers.IO) {
        detect(
            inputArguments = listOf("-ss", seconds(startMs), "-fd", fd.toString(), "-i", "fd:"),
            durationMs = durationMs,
            startMs = startMs,
            endMs = endMs,
        )
    }

    private fun detect(
        inputArguments: List<String>,
        durationMs: Long,
        startMs: Long,
        endMs: Long,
    ): SceneDetectionResult {
        val safeDuration = durationMs.coerceAtLeast(1L)
        val safeStart = startMs.coerceIn(0L, safeDuration)
        val safeEnd = endMs.coerceIn(safeStart, safeDuration)
        if (safeEnd <= safeStart) {
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
        val session = FFmpegKit.executeWithArguments(arguments.toTypedArray())
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
''',
)

write(
    "app/src/test/java/app/clipforge/media/SceneChangeDetectorTest.kt",
    r'''package app.clipforge.media

import org.junit.Assert.assertEquals
import org.junit.Test

class SceneChangeDetectorTest {
    @Test
    fun parsesSceneAndBlackMarkersWithWindowOffset() {
        val log = """
            [Parsed_showinfo_4 @ 0x1] n:1 pts:112 pts_time:1.250 pos:1234
            [blackdetect @ 0x2] black_start:2.500 black_end:2.800 black_duration:0.300
        """.trimIndent()

        assertEquals(
            listOf(
                SceneMarker(11_250L, SceneMarkerKind.SCENE_CHANGE),
                SceneMarker(12_500L, SceneMarkerKind.BLACK),
            ),
            parseSceneDetectionLog(log, 10_000L),
        )
    }

    @Test
    fun nearlyIdenticalMarkersAreDeduplicatedPerKind() {
        val log = """
            [Parsed_showinfo_4 @ 0x1] pts_time:1.000
            [Parsed_showinfo_4 @ 0x1] pts_time:1.050
            [blackdetect @ 0x2] black_start:1.040 black_end:1.200 black_duration:0.160
        """.trimIndent()

        assertEquals(
            listOf(
                SceneMarker(1_000L, SceneMarkerKind.SCENE_CHANGE),
                SceneMarker(1_040L, SceneMarkerKind.BLACK),
            ),
            parseSceneDetectionLog(log, 0L),
        )
    }
}
''',
)

# ---------------------------------------------------------------------------
# Preserve attached pictures / jackets through seek, smart cut and concat.
# ---------------------------------------------------------------------------
engine = "app/src/main/java/app/clipforge/media/FfmpegMediaEngine.kt"
replace_once(
    engine,
    '''internal fun MediaSignature.playbackVideoStreams(): List<StreamSignature> =
    streams.filter { it.type == "video" && !it.attachedPic }
''',
    '''internal fun MediaSignature.playbackVideoStreams(): List<StreamSignature> =
    streams.filter { it.type == "video" && !it.attachedPic }

internal fun MediaSignature.hasAttachedPicture(): Boolean =
    streams.any { it.type == "video" && it.attachedPic }

/**
 * Maps streams in their original order while sourcing attached pictures from a separate, unseeked
 * input. This is required because an input-level `-ss` or ffconcat `inpoint` can seek past the
 * single attached-picture packet even when `-map 0 -c copy` is used.
 */
internal fun preservedStreamMapArguments(
    signature: MediaSignature,
    primaryInputIndex: Int,
    jacketInputIndex: Int?,
): List<String> {
    if (!signature.hasAttachedPicture() || jacketInputIndex == null) {
        return listOf("-map", primaryInputIndex.toString())
    }
    return buildList {
        var videoOrdinal = 0
        signature.streams.forEachIndexed { streamIndex, stream ->
            add("-map")
            val inputIndex = if (stream.attachedPic) jacketInputIndex else primaryInputIndex
            add("$inputIndex:$streamIndex")
            if (stream.type == "video") {
                if (stream.attachedPic) {
                    add("-disposition:v:$videoOrdinal")
                    add("attached_pic")
                }
                videoOrdinal += 1
            }
        }
    }
}
''',
)

replace_block(
    engine,
    "    suspend fun cutLosslessToPath(",
    "    suspend fun cutLosslessToDescriptor(",
    '''    suspend fun cutLosslessToPath(
        inputPath: String,
        outputPath: String,
        outputName: String,
        startMs: Long,
        endMs: Long?,
    ) = withContext(Dispatchers.IO) {
        require(startMs >= 0) { "startMs must be >= 0" }
        require(endMs == null || endMs > startMs) { "endMs must be greater than startMs" }

        val sourceSignature = probePath(inputPath)
        val hasJacket = sourceSignature.hasAttachedPicture()
        val args = mutableListOf(
            "-hide_banner", "-y",
            "-noaccurate_seek",
            "-ss", seconds(startMs),
            "-i", inputPath,
        )
        if (hasJacket) args += listOf("-i", inputPath)
        endMs?.let { end ->
            args += listOf("-t", seconds(end - startMs))
        }
        args += preservedStreamMapArguments(
            signature = sourceSignature,
            primaryInputIndex = 0,
            jacketInputIndex = if (hasJacket) 1 else null,
        )
        args += listOf(
            "-c", "copy",
            "-avoid_negative_ts", "make_zero",
            "-f", muxerFor(outputName),
            outputPath,
        )
        runFfmpeg(args)
    }

''',
)

replace_block(
    engine,
    "    suspend fun cutLosslessToDescriptor(",
    "    /**\n     * Lossless cut from one already-open seekable descriptor",
    '''    suspend fun cutLosslessToDescriptor(
        inputPath: String,
        outputFd: Int,
        outputName: String,
        startMs: Long,
        endMs: Long?,
        onProgressPercent: (Int) -> Unit = {},
    ) = withContext(Dispatchers.IO) {
        require(startMs >= 0) { "startMs must be >= 0" }
        require(endMs == null || endMs > startMs) { "endMs must be greater than startMs" }

        val sourceSignature = probePath(inputPath)
        val hasJacket = sourceSignature.hasAttachedPicture()
        val args = mutableListOf(
            "-hide_banner", "-y",
            "-noaccurate_seek",
            "-ss", seconds(startMs),
            "-i", inputPath,
        )
        if (hasJacket) args += listOf("-i", inputPath)
        endMs?.let { end ->
            args += listOf("-t", seconds(end - startMs))
        }
        args += preservedStreamMapArguments(
            signature = sourceSignature,
            primaryInputIndex = 0,
            jacketInputIndex = if (hasJacket) 1 else null,
        )
        args += listOf(
            "-c", "copy",
            "-avoid_negative_ts", "make_zero",
            "-f", muxerFor(outputName),
            "-fd", outputFd.toString(),
            "fd:",
        )
        runFfmpeg(
            arguments = args,
            expectedDurationMs = endMs?.minus(startMs),
            onProgressPercent = onProgressPercent,
        )
    }

''',
)

replace_block(
    engine,
    "    suspend fun cutLosslessDescriptors(",
    "    /**\n     * Reencodes only one short GOP fragment",
    '''    suspend fun cutLosslessDescriptors(
        inputFd: Int,
        outputFd: Int,
        outputName: String,
        startMs: Long,
        endMs: Long?,
        sourceSignature: MediaSignature,
        jacketFd: Int? = null,
        onProgressPercent: (Int) -> Unit = {},
    ) = withContext(Dispatchers.IO) {
        require(startMs >= 0) { "startMs must be >= 0" }
        require(endMs == null || endMs > startMs) { "endMs must be greater than startMs" }
        val hasJacket = sourceSignature.hasAttachedPicture()
        if (hasJacket) require(jacketFd != null) { "Attached picture requires an independent source fd" }

        val args = mutableListOf(
            "-hide_banner", "-y",
            "-noaccurate_seek",
            "-ss", seconds(startMs),
            "-fd", inputFd.toString(),
            "-i", "fd:",
        )
        if (hasJacket) {
            args += listOf("-fd", requireNotNull(jacketFd).toString(), "-i", "fd:")
        }
        endMs?.let { end ->
            args += listOf("-t", seconds(end - startMs))
        }
        args += preservedStreamMapArguments(
            signature = sourceSignature,
            primaryInputIndex = 0,
            jacketInputIndex = if (hasJacket) 1 else null,
        )
        args += listOf(
            "-c", "copy",
            "-avoid_negative_ts", "make_zero",
            "-f", muxerFor(outputName),
            "-fd", outputFd.toString(),
            "fd:",
        )
        runFfmpeg(
            arguments = args,
            expectedDurationMs = endMs?.minus(startMs),
            onProgressPercent = onProgressPercent,
        )
    }

''',
)

replace_block(
    engine,
    "    suspend fun renderSmartBoundaryToPath(",
    "    suspend fun concatSmartPartsToDescriptor(",
    '''    suspend fun renderSmartBoundaryToPath(
        inputPath: String,
        output: File,
        sourceSignature: MediaSignature,
        segment: MediaSegment,
        onProgressPercent: (Int) -> Unit = {},
    ) = withContext(Dispatchers.IO) {
        renderSmartBoundary(
            inputArguments = listOf("-i", inputPath),
            jacketInputArguments = if (sourceSignature.hasAttachedPicture()) listOf("-i", inputPath) else null,
            output = output,
            sourceSignature = sourceSignature,
            segment = segment,
            onProgressPercent = onProgressPercent,
        )
    }

    suspend fun renderSmartBoundaryDescriptor(
        inputFd: Int,
        output: File,
        sourceSignature: MediaSignature,
        segment: MediaSegment,
        jacketFd: Int? = null,
        onProgressPercent: (Int) -> Unit = {},
    ) = withContext(Dispatchers.IO) {
        if (sourceSignature.hasAttachedPicture()) {
            require(jacketFd != null) { "Attached picture requires an independent source fd" }
        }
        renderSmartBoundary(
            inputArguments = listOf("-fd", inputFd.toString(), "-i", "fd:"),
            jacketInputArguments = jacketFd?.let { listOf("-fd", it.toString(), "-i", "fd:") },
            output = output,
            sourceSignature = sourceSignature,
            segment = segment,
            onProgressPercent = onProgressPercent,
        )
    }

    private suspend fun renderSmartBoundary(
        inputArguments: List<String>,
        jacketInputArguments: List<String>?,
        output: File,
        sourceSignature: MediaSignature,
        segment: MediaSegment,
        onProgressPercent: (Int) -> Unit,
    ) {
        val videoStreams = sourceSignature.playbackVideoStreams()
        require(videoStreams.size == 1) {
            "正確カットは映像トラックが1本の動画に対応しています。完全無劣化モードを使用してください"
        }
        if (sourceSignature.hasAttachedPicture()) {
            require(jacketInputArguments != null) { "Attached picture source is required" }
        }
        val video = videoStreams.single()
        val encoder = when (video.codec) {
            "h264" -> "libopenh264"
            "hevc", "h265" -> "libkvazaar"
            else -> throw IncompatibleMediaException(
                "正確カットは現在 H.264 / H.265 に対応しています（${video.codec}）。完全無劣化モードを使用してください",
            )
        }
        output.parentFile?.mkdirs()

        val args = mutableListOf(
            "-hide_banner", "-y",
            "-ss", seconds(segment.startMs),
        )
        args += inputArguments
        jacketInputArguments?.let(args::addAll)
        args += listOf("-t", seconds(segment.durationMs))
        args += preservedStreamMapArguments(
            signature = sourceSignature,
            primaryInputIndex = 0,
            jacketInputIndex = if (jacketInputArguments != null) 1 else null,
        )
        args += listOf(
            "-c", "copy",
            "-c:V:0", encoder,
            "-b:V:0", smartBoundaryBitrate(video, video.codec).toString(),
        )
        if (video.codec == "h264") {
            args += listOf("-pix_fmt:V:0", "yuv420p")
        }
        if (output.extension.equals("mp4", ignoreCase = true)) {
            video.codecTag
                ?.takeIf { it == "avc1" || it == "hvc1" || it == "hev1" }
                ?.let { args += listOf("-tag:V:0", it) }
            videoTimeScale(video.timeBase)?.let { scale ->
                args += listOf("-video_track_timescale", scale.toString())
            }
        }
        args += listOf(
            "-avoid_negative_ts", "make_zero",
            "-f", muxerFor(output.name),
            output.absolutePath,
        )
        runFfmpeg(
            arguments = args,
            expectedDurationMs = segment.durationMs,
            onProgressPercent = onProgressPercent,
        )
    }

''',
)

replace_block(
    engine,
    "    suspend fun concatSmartPartsToDescriptor(",
    "    /**\n     * Keeps several ranges from one local source",
    '''    suspend fun concatSmartPartsToDescriptor(
        inputPath: String,
        outputFd: Int,
        outputName: String,
        parts: List<SmartConcatInput>,
        expectedDurationMs: Long,
        sourceSignature: MediaSignature,
        workingDirectory: File,
        onProgressPercent: (Int) -> Unit = {},
    ) = withContext(Dispatchers.IO) {
        concatSmartParts(
            script = pathSmartConcatScript(inputPath, parts),
            usesFdSource = false,
            jacketInputArguments = if (sourceSignature.hasAttachedPicture()) listOf("-i", inputPath) else null,
            sourceSignature = sourceSignature,
            outputFd = outputFd,
            outputName = outputName,
            expectedDurationMs = expectedDurationMs,
            workingDirectory = workingDirectory,
            onProgressPercent = onProgressPercent,
        )
    }

    suspend fun concatSmartPartsDescriptors(
        inputFds: List<Int>,
        outputFd: Int,
        outputName: String,
        parts: List<SmartConcatInput>,
        expectedDurationMs: Long,
        sourceSignature: MediaSignature,
        jacketFd: Int? = null,
        workingDirectory: File,
        onProgressPercent: (Int) -> Unit = {},
    ) = withContext(Dispatchers.IO) {
        if (sourceSignature.hasAttachedPicture()) {
            require(jacketFd != null) { "Attached picture requires an independent source fd" }
        }
        concatSmartParts(
            script = fdSmartConcatScript(inputFds, parts),
            usesFdSource = inputFds.isNotEmpty() || jacketFd != null,
            jacketInputArguments = jacketFd?.let { listOf("-fd", it.toString(), "-i", "fd:") },
            sourceSignature = sourceSignature,
            outputFd = outputFd,
            outputName = outputName,
            expectedDurationMs = expectedDurationMs,
            workingDirectory = workingDirectory,
            onProgressPercent = onProgressPercent,
        )
    }

    private suspend fun concatSmartParts(
        script: String,
        usesFdSource: Boolean,
        jacketInputArguments: List<String>?,
        sourceSignature: MediaSignature,
        outputFd: Int,
        outputName: String,
        expectedDurationMs: Long,
        workingDirectory: File,
        onProgressPercent: (Int) -> Unit,
    ) {
        require(script.isNotBlank()) { "smart cut script must not be empty" }
        workingDirectory.mkdirs()
        val listFile = File(workingDirectory, ".clipforge-smart-${System.nanoTime()}.ffconcat")
        try {
            listFile.writeText(script)
            val args = mutableListOf("-hide_banner", "-y")
            if (usesFdSource) {
                args += listOf("-protocol_whitelist", "file,fd,crypto,data")
            }
            args += listOf(
                "-f", "concat",
                "-safe", "0",
                "-auto_convert", "1",
                "-i", listFile.absolutePath,
            )
            jacketInputArguments?.let(args::addAll)
            args += preservedStreamMapArguments(
                signature = sourceSignature,
                primaryInputIndex = 0,
                jacketInputIndex = if (jacketInputArguments != null) 1 else null,
            )
            args += listOf(
                "-c", "copy",
                "-fflags", "+genpts",
                "-avoid_negative_ts", "make_zero",
                "-f", muxerFor(outputName),
                "-fd", outputFd.toString(),
                "fd:",
            )
            runFfmpeg(
                arguments = args,
                expectedDurationMs = expectedDurationMs,
                onProgressPercent = onProgressPercent,
            )
        } finally {
            listFile.delete()
        }
    }

''',
)

replace_block(
    engine,
    "    suspend fun concatSegmentsLosslessToDescriptor(",
    "    suspend fun concatLossless(inputs: List<File>, output: File)",
    '''    suspend fun concatSegmentsLosslessToDescriptor(
        inputPath: String,
        outputFd: Int,
        outputName: String,
        segments: List<MediaSegment>,
        workingDirectory: File,
        onProgressPercent: (Int) -> Unit = {},
    ) = withContext(Dispatchers.IO) {
        require(segments.size >= 2) { "At least two segments are required" }
        val sourceSignature = probePath(inputPath)
        val hasJacket = sourceSignature.hasAttachedPicture()
        workingDirectory.mkdirs()
        val listFile = File(workingDirectory, ".clipforge-segments-${System.nanoTime()}.ffconcat")
        try {
            listFile.writeText(pathSegmentConcatScript(inputPath, segments))
            val args = mutableListOf(
                "-hide_banner", "-y",
                "-f", "concat",
                "-safe", "0",
                "-auto_convert", "1",
                "-i", listFile.absolutePath,
            )
            if (hasJacket) args += listOf("-i", inputPath)
            args += preservedStreamMapArguments(
                signature = sourceSignature,
                primaryInputIndex = 0,
                jacketInputIndex = if (hasJacket) 1 else null,
            )
            args += listOf(
                "-c", "copy",
                "-fflags", "+genpts",
                "-avoid_negative_ts", "make_zero",
                "-f", muxerFor(outputName),
                "-fd", outputFd.toString(),
                "fd:",
            )
            runFfmpeg(
                arguments = args,
                expectedDurationMs = segments.sumOf(MediaSegment::durationMs),
                onProgressPercent = onProgressPercent,
            )
        } finally {
            listFile.delete()
        }
    }

    /** Same as [concatSegmentsLosslessToDescriptor], but the source is a seekable Android fd. */
    suspend fun concatSegmentsLosslessDescriptors(
        inputFds: List<Int>,
        outputFd: Int,
        outputName: String,
        segments: List<MediaSegment>,
        sourceSignature: MediaSignature,
        jacketFd: Int? = null,
        workingDirectory: File,
        onProgressPercent: (Int) -> Unit = {},
    ) = withContext(Dispatchers.IO) {
        require(segments.size >= 2) { "At least two segments are required" }
        require(inputFds.size == segments.size) { "Each source segment needs an independent fd" }
        val hasJacket = sourceSignature.hasAttachedPicture()
        if (hasJacket) require(jacketFd != null) { "Attached picture requires an independent source fd" }
        workingDirectory.mkdirs()
        val listFile = File(workingDirectory, ".clipforge-segments-${System.nanoTime()}.ffconcat")
        try {
            listFile.writeText(fdSegmentConcatScript(inputFds, segments))
            val args = mutableListOf(
                "-hide_banner", "-y",
                "-protocol_whitelist", "file,fd,crypto,data",
                "-f", "concat",
                "-safe", "0",
                "-auto_convert", "1",
                "-i", listFile.absolutePath,
            )
            if (hasJacket) {
                args += listOf("-fd", requireNotNull(jacketFd).toString(), "-i", "fd:")
            }
            args += preservedStreamMapArguments(
                signature = sourceSignature,
                primaryInputIndex = 0,
                jacketInputIndex = if (hasJacket) 1 else null,
            )
            args += listOf(
                "-c", "copy",
                "-fflags", "+genpts",
                "-avoid_negative_ts", "make_zero",
                "-f", muxerFor(outputName),
                "-fd", outputFd.toString(),
                "fd:",
            )
            runFfmpeg(
                arguments = args,
                expectedDurationMs = segments.sumOf(MediaSegment::durationMs),
                onProgressPercent = onProgressPercent,
            )
        } finally {
            listFile.delete()
        }
    }

''',
)

replace_block(
    engine,
    "    suspend fun concatLosslessPathsValidated(",
    "    /**\n     * Lossless concat over already-open seekable descriptors.",
    '''    suspend fun concatLosslessPathsValidated(
        inputs: List<NamedMediaPath>,
        outputPath: String,
        outputName: String,
        workingDirectory: File,
    ) = withContext(Dispatchers.IO) {
        require(inputs.size >= 2) { "At least two files are required" }
        val sourceSignature = probePath(inputs.first().path, inputs.first().displayName)
        val hasJacket = sourceSignature.hasAttachedPicture()
        workingDirectory.mkdirs()
        val listFile = File(workingDirectory, ".clipforge-concat-${System.nanoTime()}.txt")
        try {
            listFile.writeText(inputs.joinToString("\\n") { "file '${escapeConcatPath(it.path)}'" })
            val args = mutableListOf(
                "-hide_banner", "-y",
                "-f", "concat",
                "-safe", "0",
                "-auto_convert", "1",
                "-i", listFile.absolutePath,
            )
            if (hasJacket) args += listOf("-i", inputs.first().path)
            args += preservedStreamMapArguments(
                signature = sourceSignature,
                primaryInputIndex = 0,
                jacketInputIndex = if (hasJacket) 1 else null,
            )
            args += listOf(
                "-c", "copy",
                "-fflags", "+genpts",
                "-f", muxerFor(outputName),
                outputPath,
            )
            runFfmpeg(args)
        } finally {
            listFile.delete()
        }
    }

''',
)

replace_block(
    engine,
    "    suspend fun concatLosslessDescriptorsValidated(",
    "    private suspend fun runFfmpeg(",
    '''    suspend fun concatLosslessDescriptorsValidated(
        inputs: List<NamedMediaDescriptor>,
        outputFd: Int,
        outputName: String,
        sourceSignature: MediaSignature,
        jacketFd: Int? = null,
        workingDirectory: File,
    ) = withContext(Dispatchers.IO) {
        require(inputs.size >= 2) { "At least two files are required" }
        val hasJacket = sourceSignature.hasAttachedPicture()
        if (hasJacket) require(jacketFd != null) { "Attached picture requires an independent source fd" }
        workingDirectory.mkdirs()
        val listFile = File(workingDirectory, ".clipforge-concat-${System.nanoTime()}.ffconcat")
        try {
            listFile.writeText(fdConcatScript(inputs))
            val args = mutableListOf(
                "-hide_banner", "-y",
                "-protocol_whitelist", "file,fd,crypto,data",
                "-f", "concat",
                "-safe", "0",
                "-auto_convert", "1",
                "-i", listFile.absolutePath,
            )
            if (hasJacket) {
                args += listOf("-fd", requireNotNull(jacketFd).toString(), "-i", "fd:")
            }
            args += preservedStreamMapArguments(
                signature = sourceSignature,
                primaryInputIndex = 0,
                jacketInputIndex = if (hasJacket) 1 else null,
            )
            args += listOf(
                "-c", "copy",
                "-fflags", "+genpts",
                "-f", muxerFor(outputName),
                "-fd", outputFd.toString(),
                "fd:",
            )
            runFfmpeg(args)
        } finally {
            listFile.delete()
        }
    }

''',
)

# Tests for stable stream ordering and explicit attached_pic disposition.
attached_test = "app/src/test/java/app/clipforge/media/AttachedPictureHandlingTest.kt"
replace_once(
    attached_test,
    "import org.junit.Assert.assertEquals\n",
    "import org.junit.Assert.assertEquals\n",
)
replace_once(
    attached_test,
    '''    @Test
    fun realSecondVideoTrackIsStillRejectedByCount() {
''',
    '''    @Test
    fun attachedPictureMappingKeepsOriginalStreamOrder() {
        val mainVideo = video(codec = "h264")
        val jacket = video(codec = "mjpeg", attachedPic = true)
        val audio = StreamSignature(
            type = "audio",
            codec = "aac",
            codecTag = null,
            width = null,
            height = null,
            sampleRate = 48_000,
            channels = 2,
            timeBase = "1/48000",
        )
        val signature = signature(mainVideo, jacket, audio)

        assertEquals(
            listOf(
                "-map", "0:0",
                "-map", "1:1",
                "-disposition:v:1", "attached_pic",
                "-map", "0:2",
            ),
            preservedStreamMapArguments(signature, primaryInputIndex = 0, jacketInputIndex = 1),
        )
    }

    @Test
    fun realSecondVideoTrackIsStillRejectedByCount() {
''',
)

# ---------------------------------------------------------------------------
# Workflow callers: supply independent descriptors for the jacket.
# ---------------------------------------------------------------------------
pipeline = "app/src/main/java/app/clipforge/workflow/ExternalEditPipeline.kt"
replace_once(
    pipeline,
    "import app.clipforge.media.TimelineThumbnailGenerator\n",
    "import app.clipforge.media.TimelineThumbnailGenerator\nimport app.clipforge.media.hasAttachedPicture\n",
)
replace_once(
    pipeline,
    '''            val inputDescriptors = mutableListOf<ParcelFileDescriptor>()
            var outputDescriptor: ParcelFileDescriptor? = null
            try {
                inputs.forEach { source -> inputDescriptors += openReadDescriptor(source) }
                outputDescriptor = openReadWriteDescriptor(destination)
''',
    '''            val inputDescriptors = mutableListOf<ParcelFileDescriptor>()
            var jacketDescriptor: ParcelFileDescriptor? = null
            var outputDescriptor: ParcelFileDescriptor? = null
            try {
                inputs.forEach { source -> inputDescriptors += openReadDescriptor(source) }
                val sourceSignature = signatures.first().signature
                if (sourceSignature.hasAttachedPicture()) {
                    jacketDescriptor = openReadDescriptor(inputs.first())
                }
                outputDescriptor = openReadWriteDescriptor(destination)
''',
)
replace_once(
    pipeline,
    '''                mediaEngine.concatLosslessDescriptorsValidated(
                    inputs = descriptorInputs,
                    outputFd = outputDescriptor.fd,
                    outputName = outputName,
                    workingDirectory = workDir,
                )
            } finally {
                runCatching { outputDescriptor?.close() }
                inputDescriptors.asReversed().forEach { descriptor ->
''',
    '''                mediaEngine.concatLosslessDescriptorsValidated(
                    inputs = descriptorInputs,
                    outputFd = outputDescriptor.fd,
                    outputName = outputName,
                    sourceSignature = sourceSignature,
                    jacketFd = jacketDescriptor?.fd,
                    workingDirectory = workDir,
                )
            } finally {
                runCatching { outputDescriptor?.close() }
                runCatching { jacketDescriptor?.close() }
                inputDescriptors.asReversed().forEach { descriptor ->
''',
)

replace_block(
    pipeline,
    "    suspend fun cutSourceDirect(",
    "    fun discardPreparedSession(",
    '''    suspend fun cutSourceDirect(
        source: PickedVideo,
        sessionPath: String,
        outputUri: String,
        outputName: String,
        startMs: Long,
        endMs: Long,
        onProgress: (String) -> Unit = {},
    ) = withContext(Dispatchers.IO) {
        val destination = Uri.parse(outputUri)
        val remoteDestination = isXFilesRemoteOutput(destination)
        val destinationLabel = if (remoteDestination) "SMB" else "端末"
        val sourceSignature = openReadDescriptor(source).use { descriptor ->
            mediaEngine.probeDescriptor(descriptor.fd, source.displayName)
        }
        var inputDescriptor: ParcelFileDescriptor? = null
        var jacketDescriptor: ParcelFileDescriptor? = null
        var outputDescriptor: ParcelFileDescriptor? = null
        try {
            onProgress("入力と${destinationLabel}保存先を開いています")
            inputDescriptor = openReadDescriptor(source)
            if (sourceSignature.hasAttachedPicture()) {
                jacketDescriptor = openReadDescriptor(source)
            }
            outputDescriptor = openReadWriteDescriptor(destination)
            onProgress("無劣化でカットしながら${destinationLabel}へ保存中")
            mediaEngine.cutLosslessDescriptors(
                inputFd = inputDescriptor.fd,
                outputFd = outputDescriptor.fd,
                outputName = outputName,
                startMs = startMs,
                endMs = endMs,
                sourceSignature = sourceSignature,
                jacketFd = jacketDescriptor?.fd,
            )
            runCatching { outputDescriptor.close() }
            outputDescriptor = null
            runCatching { jacketDescriptor?.close() }
            jacketDescriptor = null
            runCatching { inputDescriptor.close() }
            inputDescriptor = null
            finishOutput(destination, remoteDestination, onProgress)
            discardPreparedSession(sessionPath)
        } catch (error: Throwable) {
            abortOutput(destination)
            throw error
        } finally {
            runCatching { outputDescriptor?.close() }
            runCatching { jacketDescriptor?.close() }
            runCatching { inputDescriptor?.close() }
        }
    }

''',
)

exporter = "app/src/main/java/app/clipforge/workflow/MultiCutExporter.kt"
replace_once(
    exporter,
    "import app.clipforge.media.MediaSegment\n",
    "import app.clipforge.media.MediaSegment\nimport app.clipforge.media.MediaSignature\n",
)
replace_once(
    exporter,
    "import app.clipforge.media.remainingSegments\n",
    "import app.clipforge.media.remainingSegments\nimport app.clipforge.media.hasAttachedPicture\n",
)
replace_once(
    exporter,
    '''        val keepSegments = remainingSegments(durationMs, cutRanges)
        require(keepSegments.isNotEmpty()) { "動画全体を削除する指定になっています" }

        val destination = Uri.parse(outputUri)
''',
    '''        val keepSegments = remainingSegments(durationMs, cutRanges)
        require(keepSegments.isNotEmpty()) { "動画全体を削除する指定になっています" }
        val sourceSignature = if (localInputPath != null) {
            val input = File(localInputPath)
            require(input.isFile) { "編集用キャッシュが見つかりません" }
            mediaEngine.probe(input)
        } else {
            withReadDescriptors(source, 1) { descriptors ->
                mediaEngine.probeDescriptor(descriptors.single().fd, source.displayName)
            }
        }

        val destination = Uri.parse(outputUri)
''',
)
replace_once(
    exporter,
    '''                    exportSmartLocal(
                        input = input,
                        source = source,
                        durationMs = durationMs,
''',
    '''                    exportSmartLocal(
                        input = input,
                        source = source,
                        durationMs = durationMs,
                        sourceSignature = sourceSignature,
''',
)
replace_once(
    exporter,
    '''                    exportSmartDescriptor(
                        source = source,
                        durationMs = durationMs,
''',
    '''                    exportSmartDescriptor(
                        source = source,
                        durationMs = durationMs,
                        sourceSignature = sourceSignature,
''',
)
replace_once(
    exporter,
    '''                    exportLosslessDescriptor(
                        source = source,
                        keepSegments = keepSegments,
''',
    '''                    exportLosslessDescriptor(
                        source = source,
                        sourceSignature = sourceSignature,
                        keepSegments = keepSegments,
''',
)

replace_block(
    exporter,
    "    private suspend fun exportLosslessDescriptor(",
    "    private suspend fun exportSmartLocal(",
    '''    private suspend fun exportLosslessDescriptor(
        source: PickedVideo,
        sourceSignature: MediaSignature,
        keepSegments: List<MediaSegment>,
        outputFd: Int,
        outputName: String,
        workDir: File,
        onProgress: (Int) -> Unit,
    ) {
        val mediaDescriptorCount = if (keepSegments.size == 1) 1 else keepSegments.size
        val totalDescriptorCount = mediaDescriptorCount + if (sourceSignature.hasAttachedPicture()) 1 else 0
        withReadDescriptors(source, totalDescriptorCount) { descriptors ->
            val mediaDescriptors = descriptors.take(mediaDescriptorCount)
            val jacketFd = descriptors.getOrNull(mediaDescriptorCount)?.fd
            if (keepSegments.size == 1) {
                val segment = keepSegments.single()
                mediaEngine.cutLosslessDescriptors(
                    inputFd = mediaDescriptors.single().fd,
                    outputFd = outputFd,
                    outputName = outputName,
                    startMs = segment.startMs,
                    endMs = segment.endMs,
                    sourceSignature = sourceSignature,
                    jacketFd = jacketFd,
                    onProgressPercent = onProgress,
                )
            } else {
                mediaEngine.concatSegmentsLosslessDescriptors(
                    inputFds = mediaDescriptors.map { it.fd },
                    outputFd = outputFd,
                    outputName = outputName,
                    segments = keepSegments,
                    sourceSignature = sourceSignature,
                    jacketFd = jacketFd,
                    workingDirectory = workDir,
                    onProgressPercent = onProgress,
                )
            }
        }
    }

''',
)

replace_once(
    exporter,
    '''    private suspend fun exportSmartLocal(
        input: File,
        source: PickedVideo,
        durationMs: Long,
        keepSegments: List<MediaSegment>,
''',
    '''    private suspend fun exportSmartLocal(
        input: File,
        source: PickedVideo,
        durationMs: Long,
        sourceSignature: MediaSignature,
        keepSegments: List<MediaSegment>,
''',
)
replace_once(exporter, "        val signature = mediaEngine.probe(input)\n", "")
replace_once(
    exporter,
    "                    sourceSignature = signature,\n",
    "                    sourceSignature = sourceSignature,\n",
)
replace_once(
    exporter,
    '''            expectedDurationMs = keepSegments.sumOf(MediaSegment::durationMs),
            workingDirectory = workDir,
''',
    '''            expectedDurationMs = keepSegments.sumOf(MediaSegment::durationMs),
            sourceSignature = sourceSignature,
            workingDirectory = workDir,
''',
)

replace_once(
    exporter,
    '''    private suspend fun exportSmartDescriptor(
        source: PickedVideo,
        durationMs: Long,
        keepSegments: List<MediaSegment>,
''',
    '''    private suspend fun exportSmartDescriptor(
        source: PickedVideo,
        durationMs: Long,
        sourceSignature: MediaSignature,
        keepSegments: List<MediaSegment>,
''',
)
replace_once(
    exporter,
    '''        val signature = withReadDescriptors(source, 1) { descriptors ->
            mediaEngine.probeDescriptor(descriptors.single().fd, source.displayName)
        }
''',
    "",
)
# The remaining sourceSignature = signature occurrence belongs to descriptor rendering.
replace_once(
    exporter,
    '''                withReadDescriptors(source, 1) { descriptors ->
                    mediaEngine.renderSmartBoundaryDescriptor(
                        inputFd = descriptors.single().fd,
                        output = file,
                        sourceSignature = signature,
                        segment = segment,
                        onProgressPercent = callback,
                    )
                }
''',
    '''                val descriptorCount = if (sourceSignature.hasAttachedPicture()) 2 else 1
                withReadDescriptors(source, descriptorCount) { descriptors ->
                    mediaEngine.renderSmartBoundaryDescriptor(
                        inputFd = descriptors.first().fd,
                        output = file,
                        sourceSignature = sourceSignature,
                        segment = segment,
                        jacketFd = descriptors.getOrNull(1)?.fd,
                        onProgressPercent = callback,
                    )
                }
''',
)
replace_once(
    exporter,
    '''        val sourcePartCount = concatParts.count { it is SmartConcatInput.SourceSegment }
        withReadDescriptors(source, sourcePartCount) { descriptors ->
            mediaEngine.concatSmartPartsDescriptors(
                inputFds = descriptors.map { it.fd },
                outputFd = outputFd,
                outputName = outputName,
                parts = concatParts,
                expectedDurationMs = keepSegments.sumOf(MediaSegment::durationMs),
                workingDirectory = workDir,
                onProgressPercent = onConcatProgress,
            )
        }
''',
    '''        val sourcePartCount = concatParts.count { it is SmartConcatInput.SourceSegment }
        val descriptorCount = sourcePartCount + if (sourceSignature.hasAttachedPicture()) 1 else 0
        withReadDescriptors(source, descriptorCount) { descriptors ->
            mediaEngine.concatSmartPartsDescriptors(
                inputFds = descriptors.take(sourcePartCount).map { it.fd },
                outputFd = outputFd,
                outputName = outputName,
                parts = concatParts,
                expectedDurationMs = keepSegments.sumOf(MediaSegment::durationMs),
                sourceSignature = sourceSignature,
                jacketFd = descriptors.getOrNull(sourcePartCount)?.fd,
                workingDirectory = workDir,
                onProgressPercent = onConcatProgress,
            )
        }
''',
)

# ---------------------------------------------------------------------------
# Navigator: bounded, on-demand scene detection over local or seekable SMB sources.
# ---------------------------------------------------------------------------
navigator = "app/src/main/java/app/clipforge/workflow/CutSessionNavigator.kt"
replace_once(
    navigator,
    "import app.clipforge.media.SyncFrameResolver\n",
    "import app.clipforge.media.SceneChangeDetector\nimport app.clipforge.media.SceneDetectionResult\nimport app.clipforge.media.SyncFrameResolver\n",
)
replace_once(
    navigator,
    '''class CutSessionNavigator(
    context: Context,
    private val syncFrameResolver: SyncFrameResolver = SyncFrameResolver(),
) {
''',
    '''class CutSessionNavigator(
    context: Context,
    private val syncFrameResolver: SyncFrameResolver = SyncFrameResolver(),
    private val sceneChangeDetector: SceneChangeDetector = SceneChangeDetector(),
) {
''',
)
replace_once(
    navigator,
    "    private fun openSeekable(source: PickedVideo): ParcelFileDescriptor {\n",
    '''    suspend fun detectSceneWindow(
        source: PickedVideo,
        localInputPath: String?,
        durationMs: Long,
        startMs: Long,
        endMs: Long,
    ): SceneDetectionResult = withContext(Dispatchers.IO) {
        if (localInputPath != null) {
            val input = File(localInputPath)
            if (!input.isFile) return@withContext SceneDetectionResult(emptyList(), startMs, endMs)
            return@withContext sceneChangeDetector.detectPath(
                path = input.absolutePath,
                durationMs = durationMs,
                startMs = startMs,
                endMs = endMs,
            )
        }
        openSeekable(source).use { descriptor ->
            sceneChangeDetector.detectDescriptor(
                fd = descriptor.fd,
                durationMs = durationMs,
                startMs = startMs,
                endMs = endMs,
            )
        }
    }

    private fun openSeekable(source: PickedVideo): ParcelFileDescriptor {
''',
)

# ---------------------------------------------------------------------------
# View-model cache: accumulate discovered markers and analyzed windows.
# ---------------------------------------------------------------------------
vm = "app/src/main/java/app/clipforge/MainViewModel.kt"
replace_once(
    vm,
    "import app.clipforge.media.MediaSegment\n",
    "import app.clipforge.media.MediaSegment\nimport app.clipforge.media.SceneMarker\nimport app.clipforge.media.SceneMarkerKind\n",
)
replace_once(
    vm,
    '''    val canUndoEdit: Boolean = false,
    val selectedVideos: List<PickedVideo> = emptyList(),
''',
    '''    val canUndoEdit: Boolean = false,
    val sceneSearchBusy: Boolean = false,
    val sceneMarkers: List<SceneMarker> = emptyList(),
    val sceneScannedRanges: List<MediaSegment> = emptyList(),
    val selectedVideos: List<PickedVideo> = emptyList(),
''',
)
replace_once(
    vm,
    '''                                canUndoEdit = false,
                                selectedVideos = state.selectedVideos.ifEmpty { listOf(preparedSource) },
''',
    '''                                canUndoEdit = false,
                                sceneSearchBusy = false,
                                sceneMarkers = emptyList(),
                                sceneScannedRanges = emptyList(),
                                selectedVideos = state.selectedVideos.ifEmpty { listOf(preparedSource) },
''',
)
replace_once(
    vm,
    '''                                canUndoEdit = false,
                                status = processing.message,
''',
    '''                                canUndoEdit = false,
                                sceneSearchBusy = false,
                                sceneMarkers = emptyList(),
                                sceneScannedRanges = emptyList(),
                                status = processing.message,
''',
)
# Failure and cancellation must release the navigation lock without discarding accumulated markers.
replace_once(
    vm,
    '''                                canCancelProcessing = false,
                                trimEditor = it.trimEditor ?: editorToRestore,
                                canUndoEdit = editUndoStack.isNotEmpty(),
                                status = "失敗",
''',
    '''                                canCancelProcessing = false,
                                sceneSearchBusy = false,
                                trimEditor = it.trimEditor ?: editorToRestore,
                                canUndoEdit = editUndoStack.isNotEmpty(),
                                status = "失敗",
''',
)
replace_once(
    vm,
    '''                                canCancelProcessing = false,
                                trimEditor = it.trimEditor ?: editorToRestore,
                                canUndoEdit = editUndoStack.isNotEmpty(),
                                status = processing.message,
''',
    '''                                canCancelProcessing = false,
                                sceneSearchBusy = false,
                                trimEditor = it.trimEditor ?: editorToRestore,
                                canUndoEdit = editUndoStack.isNotEmpty(),
                                status = processing.message,
''',
)
replace_once(
    vm,
    '''                        pendingDestination = null,
                        canUndoEdit = false,
                        status = "${videos.size}本の動画を選択しました",
''',
    '''                        pendingDestination = null,
                        canUndoEdit = false,
                        sceneSearchBusy = false,
                        sceneMarkers = emptyList(),
                        sceneScannedRanges = emptyList(),
                        status = "${videos.size}本の動画を選択しました",
''',
)

replace_once(
    vm,
    "    fun snapTrimRangeToKeyframes() {\n",
    r'''    suspend fun adjacentSceneMarker(
        positionMs: Long,
        forward: Boolean,
        kind: SceneMarkerKind,
    ): Long? {
        val initialState = _uiState.value
        val editor = initialState.trimEditor ?: return null
        if (initialState.sceneSearchBusy) return null
        val pivot = positionMs.coerceIn(0L, editor.durationMs)
        val tolerance = 250L

        fun nearest(markers: List<SceneMarker>): SceneMarker? = markers
            .asSequence()
            .filter { it.kind == kind }
            .filter { marker ->
                if (forward) marker.timeMs > pivot + tolerance else marker.timeMs < pivot - tolerance
            }
            .let { sequence ->
                if (forward) sequence.minByOrNull(SceneMarker::timeMs) else sequence.maxByOrNull(SceneMarker::timeMs)
            }

        val cached = nearest(initialState.sceneMarkers)
        if (cached != null && intervalCovered(
                fromMs = minOf(pivot, cached.timeMs),
                toMs = maxOf(pivot, cached.timeMs),
                ranges = initialState.sceneScannedRanges,
            )
        ) {
            return cached.timeMs
        }

        val searchAnchor = if (forward) {
            (pivot + tolerance).coerceAtMost(editor.durationMs)
        } else {
            (pivot - tolerance).coerceAtLeast(0L)
        }
        val frontier = if (forward) {
            coveredForwardEdge(searchAnchor, initialState.sceneScannedRanges)
        } else {
            coveredBackwardEdge(searchAnchor, initialState.sceneScannedRanges)
        }
        val scanStart = if (forward) {
            frontier
        } else {
            (frontier - SCENE_SEARCH_WINDOW_MS).coerceAtLeast(0L)
        }
        val scanEnd = if (forward) {
            (frontier + SCENE_SEARCH_WINDOW_MS).coerceAtMost(editor.durationMs)
        } else {
            frontier
        }
        if (scanEnd <= scanStart) {
            _uiState.update {
                it.copy(
                    status = if (forward) "この先に候補はありません" else "この手前に候補はありません",
                    error = null,
                )
            }
            return null
        }

        _uiState.update {
            it.copy(
                sceneSearchBusy = true,
                status = if (forward) "次のカット候補を探しています" else "前のカット候補を探しています",
                error = null,
            )
        }
        return try {
            val result = navigator.detectSceneWindow(
                source = sourceFor(editor, initialState),
                localInputPath = editor.localPath,
                durationMs = editor.durationMs,
                startMs = scanStart,
                endMs = scanEnd,
            )
            var mergedMarkers = emptyList<SceneMarker>()
            _uiState.update { state ->
                val current = state.trimEditor ?: return@update state
                if (current.sessionPath != editor.sessionPath) return@update state
                mergedMarkers = mergeSceneMarkers(state.sceneMarkers + result.markers)
                state.copy(
                    sceneMarkers = mergedMarkers,
                    sceneScannedRanges = mergeScanRanges(
                        state.sceneScannedRanges + MediaSegment(result.scannedStartMs, result.scannedEndMs),
                    ),
                    error = null,
                )
            }
            val target = nearest(mergedMarkers)
            _uiState.update { state ->
                val label = when (kind) {
                    SceneMarkerKind.SCENE_CHANGE -> "シーン切替"
                    SceneMarkerKind.BLACK -> "黒画面"
                }
                state.copy(
                    status = if (target != null) {
                        "$label 候補へ移動します"
                    } else {
                        "この${SCENE_SEARCH_WINDOW_MS / 1_000}秒には${label}候補なし。もう一度押すと続きから探します"
                    },
                    error = null,
                )
            }
            target?.timeMs
        } catch (error: Throwable) {
            _uiState.update {
                it.copy(
                    status = "カット候補を解析できませんでした",
                    error = error.message ?: error.javaClass.simpleName,
                )
            }
            null
        } finally {
            _uiState.update { it.copy(sceneSearchBusy = false) }
        }
    }

    fun snapTrimRangeToKeyframes() {
''',
)

replace_once(
    vm,
    '''                canUndoEdit = false,
                status = "編集をキャンセルしました",
''',
    '''                canUndoEdit = false,
                sceneSearchBusy = false,
                sceneMarkers = emptyList(),
                sceneScannedRanges = emptyList(),
                status = "編集をキャンセルしました",
''',
)
replace_once(
    vm,
    "    private fun recordEditUndo(editor: TrimEditorState) {\n",
    r'''    private fun mergeSceneMarkers(markers: List<SceneMarker>): List<SceneMarker> = markers
        .sortedWith(compareBy(SceneMarker::timeMs, SceneMarker::kind))
        .fold(mutableListOf()) { result, marker ->
            val duplicate = result.lastOrNull { it.kind == marker.kind }
                ?.let { previous -> kotlin.math.abs(previous.timeMs - marker.timeMs) <= 120L }
                ?: false
            if (!duplicate) result += marker
            result
        }

    private fun mergeScanRanges(ranges: List<MediaSegment>): List<MediaSegment> {
        if (ranges.isEmpty()) return emptyList()
        val sorted = ranges.sortedBy(MediaSegment::startMs)
        val merged = mutableListOf<MediaSegment>()
        sorted.forEach { range ->
            val last = merged.lastOrNull()
            if (last == null || range.startMs > last.endMs + 1L) {
                merged += range
            } else {
                merged[merged.lastIndex] = MediaSegment(last.startMs, maxOf(last.endMs, range.endMs))
            }
        }
        return merged
    }

    private fun intervalCovered(fromMs: Long, toMs: Long, ranges: List<MediaSegment>): Boolean {
        val start = minOf(fromMs, toMs)
        val end = maxOf(fromMs, toMs)
        return ranges.any { range -> start >= range.startMs && end <= range.endMs }
    }

    private fun coveredForwardEdge(positionMs: Long, ranges: List<MediaSegment>): Long {
        var edge = positionMs
        ranges.forEach { range ->
            if (edge in range.startMs..range.endMs) edge = maxOf(edge, range.endMs)
        }
        return edge
    }

    private fun coveredBackwardEdge(positionMs: Long, ranges: List<MediaSegment>): Long {
        var edge = positionMs
        ranges.asReversed().forEach { range ->
            if (edge in range.startMs..range.endMs) edge = minOf(edge, range.startMs)
        }
        return edge
    }

    private fun recordEditUndo(editor: TrimEditorState) {
''',
)
replace_once(
    vm,
    '''    private companion object {
        const val MAX_EDIT_UNDO = 20
    }
''',
    '''    private companion object {
        const val MAX_EDIT_UNDO = 20
        const val SCENE_SEARCH_WINDOW_MS = 90_000L
    }
''',
)

# ---------------------------------------------------------------------------
# Compose UI: candidate navigation + accumulated timeline markers.
# ---------------------------------------------------------------------------
ui = "app/src/main/java/app/clipforge/ui/ClipForgeApp.kt"
replace_once(
    ui,
    "import app.clipforge.media.MediaSegment\n",
    "import app.clipforge.media.MediaSegment\nimport app.clipforge.media.SceneMarker\nimport app.clipforge.media.SceneMarkerKind\n",
)
replace_once(
    ui,
    '''    fun jumpKeyframe(forward: Boolean) {
        if (keyframeNavigationBusy) return
''',
    '''    fun jumpKeyframe(forward: Boolean) {
        if (keyframeNavigationBusy || state.sceneSearchBusy) return
''',
)
replace_once(
    ui,
    '''    fun requestCloseEditor() {
''',
    '''    fun jumpSceneCandidate(kind: SceneMarkerKind, forward: Boolean) {
        if (keyframeNavigationBusy || state.sceneSearchBusy) return
        scope.launch {
            val target = viewModel.adjacentSceneMarker(player.currentPosition, forward, kind)
            if (target != null) {
                player.pause()
                player.seekTo(target)
                playheadMs = target
            }
        }
    }

    fun requestCloseEditor() {
''',
)
replace_once(
    ui,
    '''                    keyframeNavigationBusy = keyframeNavigationBusy,
                    onSeekBy = ::seekBy,
                    onPreviousKeyframe = { jumpKeyframe(false) },
                    onNextKeyframe = { jumpKeyframe(true) },
''',
    '''                    keyframeNavigationBusy = keyframeNavigationBusy,
                    sceneNavigationBusy = state.sceneSearchBusy,
                    sceneMarkerCount = state.sceneMarkers.count { it.kind == SceneMarkerKind.SCENE_CHANGE },
                    blackMarkerCount = state.sceneMarkers.count { it.kind == SceneMarkerKind.BLACK },
                    onSeekBy = ::seekBy,
                    onPreviousKeyframe = { jumpKeyframe(false) },
                    onNextKeyframe = { jumpKeyframe(true) },
                    onPreviousScene = { jumpSceneCandidate(SceneMarkerKind.SCENE_CHANGE, false) },
                    onNextScene = { jumpSceneCandidate(SceneMarkerKind.SCENE_CHANGE, true) },
                    onPreviousBlack = { jumpSceneCandidate(SceneMarkerKind.BLACK, false) },
                    onNextBlack = { jumpSceneCandidate(SceneMarkerKind.BLACK, true) },
''',
)
replace_once(
    ui,
    '''                            playheadMs = playheadMs,
                        )
''',
    '''                            playheadMs = playheadMs,
                            sceneMarkers = state.sceneMarkers,
                        )
''',
)

replace_block(
    ui,
    "private fun EditorTransportControls(",
    "@Composable\nprivate fun CutModeSelector(",
    r'''private fun EditorTransportControls(
    playheadMs: Long,
    durationMs: Long,
    enabled: Boolean,
    keyframeNavigationBusy: Boolean,
    sceneNavigationBusy: Boolean,
    sceneMarkerCount: Int,
    blackMarkerCount: Int,
    onSeekBy: (Long) -> Unit,
    onPreviousKeyframe: () -> Unit,
    onNextKeyframe: () -> Unit,
    onPreviousScene: () -> Unit,
    onNextScene: () -> Unit,
    onPreviousBlack: () -> Unit,
    onNextBlack: () -> Unit,
    onSetIn: () -> Unit,
    onSetOut: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("現在位置", style = MaterialTheme.typography.labelLarge)
                Text(
                    "${formatTime(playheadMs)} / ${formatTime(durationMs)}",
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            listOf(10L, 5L, 1L).forEach { seconds ->
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = { onSeekBy(-seconds * 1_000L) },
                        enabled = enabled,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("◀ ${seconds}秒")
                    }
                    OutlinedButton(
                        onClick = { onSeekBy(seconds * 1_000L) },
                        enabled = enabled,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("${seconds}秒 ▶")
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = onPreviousKeyframe,
                    enabled = enabled && !keyframeNavigationBusy && !sceneNavigationBusy,
                    modifier = Modifier.weight(1f),
                ) { Text("前のキーフレーム") }
                OutlinedButton(
                    onClick = onNextKeyframe,
                    enabled = enabled && !keyframeNavigationBusy && !sceneNavigationBusy,
                    modifier = Modifier.weight(1f),
                ) { Text("次のキーフレーム") }
            }
            Text(
                "カット候補  シーン $sceneMarkerCount / 黒画面 $blackMarkerCount",
                style = MaterialTheme.typography.labelLarge,
            )
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = onPreviousScene,
                    enabled = enabled && !keyframeNavigationBusy && !sceneNavigationBusy,
                    modifier = Modifier.weight(1f),
                ) { Text("◀ 前のシーン") }
                OutlinedButton(
                    onClick = onNextScene,
                    enabled = enabled && !keyframeNavigationBusy && !sceneNavigationBusy,
                    modifier = Modifier.weight(1f),
                ) { Text("次のシーン ▶") }
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = onPreviousBlack,
                    enabled = enabled && !keyframeNavigationBusy && !sceneNavigationBusy,
                    modifier = Modifier.weight(1f),
                ) { Text("◀ 前の黒画面") }
                OutlinedButton(
                    onClick = onNextBlack,
                    enabled = enabled && !keyframeNavigationBusy && !sceneNavigationBusy,
                    modifier = Modifier.weight(1f),
                ) { Text("次の黒画面 ▶") }
            }
            if (sceneNavigationBusy) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text("周辺だけ解析して候補を蓄積しています", style = MaterialTheme.typography.bodySmall)
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = onSetIn,
                    enabled = enabled,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("開始位置に設定")
                }
                Button(
                    onClick = onSetOut,
                    enabled = enabled,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("終了位置に設定")
                }
            }
        }
    }
}

@Composable
''',
)

replace_once(
    ui,
    '''    editor: TrimEditorState,
    playheadMs: Long,
) {
''',
    '''    editor: TrimEditorState,
    playheadMs: Long,
    sceneMarkers: List<SceneMarker>,
) {
''',
)
replace_once(
    ui,
    '''            selectionEndMs = editor.endMs,
            cutRanges = editor.cutRanges,
            onSeek = { targetMs ->
''',
    '''            selectionEndMs = editor.endMs,
            cutRanges = editor.cutRanges,
            sceneMarkers = sceneMarkers,
            onSeek = { targetMs ->
''',
)
replace_once(
    ui,
    '''        Text(
            "削除範囲は塗りつぶし、選択範囲は両端線、現在位置は中央線で表示します。",
            style = MaterialTheme.typography.bodySmall,
        )
''',
    '''        Text(
            "削除範囲は塗りつぶし、選択範囲は両端線、現在位置は中央線。蓄積したシーン/黒画面候補も細線で残します。",
            style = MaterialTheme.typography.bodySmall,
        )
''',
)
replace_once(
    ui,
    '''    selectionEndMs: Long,
    cutRanges: List<MediaSegment>,
    onSeek: (Long) -> Unit,
) {
''',
    '''    selectionEndMs: Long,
    cutRanges: List<MediaSegment>,
    sceneMarkers: List<SceneMarker>,
    onSeek: (Long) -> Unit,
) {
''',
)
replace_once(
    ui,
    '''    val selectionColor = MaterialTheme.colorScheme.primary
    val playheadColor = MaterialTheme.colorScheme.onSurface
''',
    '''    val selectionColor = MaterialTheme.colorScheme.primary
    val playheadColor = MaterialTheme.colorScheme.onSurface
    val sceneColor = MaterialTheme.colorScheme.tertiary
    val blackColor = MaterialTheme.colorScheme.outline
''',
)
replace_once(
    ui,
    '''            val startX = xFor(selectionStartMs)
''',
    '''            sceneMarkers.forEach { marker ->
                val markerX = xFor(marker.timeMs)
                drawLine(
                    color = if (marker.kind == SceneMarkerKind.SCENE_CHANGE) sceneColor else blackColor,
                    start = Offset(markerX, 0f),
                    end = Offset(markerX, size.height),
                    strokeWidth = 2f,
                )
            }

            val startX = xFor(selectionStartMs)
''',
)

# Remove one-shot automation files from the resulting commit after they execute.
for relative in [
    ".github/scripts/apply-scene-jacket-feature.py",
    ".github/workflows/apply-scene-jacket-feature.yml",
]:
    target = ROOT / relative
    if target.exists():
        target.unlink()
