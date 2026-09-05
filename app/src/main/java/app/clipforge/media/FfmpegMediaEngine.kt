package app.clipforge.media

import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegSession
import com.arthenica.ffmpegkit.FFprobeKit
import com.arthenica.ffmpegkit.ReturnCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import kotlin.math.roundToInt
import kotlin.math.roundToLong

data class NamedMediaPath(
    val path: String,
    val displayName: String,
)

data class NamedMediaDescriptor(
    val fd: Int,
    val displayName: String,
)

data class NamedMediaSignature(
    val displayName: String,
    val signature: MediaSignature,
)

sealed interface SmartConcatInput {
    data class SourceSegment(val segment: MediaSegment) : SmartConcatInput
    data class RenderedFile(val path: String) : SmartConcatInput
}

internal fun fdConcatScript(inputs: List<NamedMediaDescriptor>): String = buildString {
    appendLine("ffconcat version 1.0")
    inputs.forEach { input ->
        appendLine("file 'fd:'")
        appendLine("option fd ${input.fd}")
    }
}

internal fun fdSegmentConcatScript(fd: Int, segments: List<MediaSegment>): String = buildString {
    appendLine("ffconcat version 1.0")
    segments.forEach { segment ->
        appendLine("file 'fd:'")
        appendLine("option fd $fd")
        appendLine("inpoint ${concatTimestamp(segment.startMs)}")
        appendLine("outpoint ${concatTimestamp(segment.endMs)}")
    }
}

private fun pathSegmentConcatScript(path: String, segments: List<MediaSegment>): String = buildString {
    appendLine("ffconcat version 1.0")
    segments.forEach { segment ->
        appendLine("file '${escapeConcatPathValue(path)}'")
        appendLine("inpoint ${concatTimestamp(segment.startMs)}")
        appendLine("outpoint ${concatTimestamp(segment.endMs)}")
    }
}

private fun pathSmartConcatScript(path: String, parts: List<SmartConcatInput>): String = buildString {
    appendLine("ffconcat version 1.0")
    parts.forEach { part ->
        when (part) {
            is SmartConcatInput.SourceSegment -> {
                appendLine("file '${escapeConcatPathValue(path)}'")
                appendLine("inpoint ${concatTimestamp(part.segment.startMs)}")
                appendLine("outpoint ${concatTimestamp(part.segment.endMs)}")
            }
            is SmartConcatInput.RenderedFile -> {
                appendLine("file '${escapeConcatPathValue(part.path)}'")
            }
        }
    }
}

private fun fdSmartConcatScript(fd: Int, parts: List<SmartConcatInput>): String = buildString {
    appendLine("ffconcat version 1.0")
    parts.forEach { part ->
        when (part) {
            is SmartConcatInput.SourceSegment -> {
                appendLine("file 'fd:'")
                appendLine("option fd $fd")
                appendLine("inpoint ${concatTimestamp(part.segment.startMs)}")
                appendLine("outpoint ${concatTimestamp(part.segment.endMs)}")
            }
            is SmartConcatInput.RenderedFile -> {
                appendLine("file '${escapeConcatPathValue(part.path)}'")
            }
        }
    }
}

private fun concatTimestamp(ms: Long): String =
    "%.3f".format(java.util.Locale.US, ms / 1000.0)

private fun escapeConcatPathValue(path: String): String = path.replace("'", "'\\''")

private val mp4FamilyFormats = setOf("mov", "mp4", "m4a", "3gp", "3g2", "mj2")

/**
 * Returns a user-facing reason when two inputs cannot be joined safely with stream copy.
 *
 * Do not compare every ffprobe field byte-for-byte here. In particular, codec_tag is a container
 * detail, and FFmpeg's concat demuxer can auto-insert h264_mp4toannexb for H.264 MP4 streams. That
 * conversion is specifically intended to keep stream-copy concatenation working across H.264
 * parameter changes such as a resolution switch without re-encoding the video.
 */
internal fun losslessConcatMismatch(
    expected: MediaSignature,
    actual: MediaSignature,
): String? {
    val expectedStreams = expected.streams
    val actualStreams = actual.streams
    if (expectedStreams.size != actualStreams.size) {
        return "ストリーム数が ${expectedStreams.size} と ${actualStreams.size} で異なります"
    }

    expectedStreams.indices.forEach { index ->
        val left = expectedStreams[index]
        val right = actualStreams[index]
        val streamNumber = index + 1

        if (left.type != right.type) {
            return "ストリーム#$streamNumber の種類が ${left.type} と ${right.type} で異なります"
        }
        if (left.codec != right.codec) {
            return "ストリーム#$streamNumber のコーデックが ${left.codec} と ${right.codec} で異なります"
        }
        if (left.timeBase != right.timeBase) {
            return "ストリーム#$streamNumber の time base が ${left.timeBase ?: "不明"} と ${right.timeBase ?: "不明"} で異なります"
        }

        when (left.type) {
            "audio" -> {
                if (left.sampleRate != right.sampleRate) {
                    return "音声#$streamNumber のサンプルレートが ${left.sampleRate ?: "不明"} と ${right.sampleRate ?: "不明"} で異なります"
                }
                if (left.channels != right.channels) {
                    return "音声#$streamNumber のチャンネル数が ${left.channels ?: "不明"} と ${right.channels ?: "不明"} で異なります"
                }
            }

            "video" -> {
                val dimensionsDiffer = left.width != right.width || left.height != right.height
                if (dimensionsDiffer && !canAutoConvertH264ResolutionChange(expected, actual, left, right)) {
                    return "映像#$streamNumber の解像度が ${formatDimensions(left)} と ${formatDimensions(right)} で異なります"
                }
            }
        }
    }

    return null
}

private fun canAutoConvertH264ResolutionChange(
    expected: MediaSignature,
    actual: MediaSignature,
    expectedStream: StreamSignature,
    actualStream: StreamSignature,
): Boolean =
    expectedStream.codec == "h264" &&
        actualStream.codec == "h264" &&
        expected.formatNames.any(mp4FamilyFormats::contains) &&
        actual.formatNames.any(mp4FamilyFormats::contains)

private fun formatDimensions(stream: StreamSignature): String =
    if (stream.width != null && stream.height != null) {
        "${stream.width}x${stream.height}"
    } else {
        "不明"
    }

internal fun MediaSignature.playbackVideoStreams(): List<StreamSignature> =
    streams.filter { it.type == "video" && !it.attachedPic }

class FfmpegMediaEngine {

    suspend fun probe(file: File): MediaSignature = probePath(file.absolutePath, file.name)

    suspend fun probePath(path: String, displayName: String = path): MediaSignature = withContext(Dispatchers.IO) {
        probeWithArguments(
            inputArguments = listOf(path),
            displayName = displayName,
        )
    }

    /**
     * Probe an already-open, seekable Android descriptor. FFmpeg's fd: protocol dup()s this fd,
     * so ownership stays with the caller and no /proc/self/fd path has to be reopened.
     */
    suspend fun probeDescriptor(fd: Int, displayName: String): MediaSignature = withContext(Dispatchers.IO) {
        probeWithArguments(
            inputArguments = listOf("-fd", fd.toString(), "fd:"),
            displayName = displayName,
        )
    }

    private fun probeWithArguments(inputArguments: List<String>, displayName: String): MediaSignature {
        val arguments = mutableListOf(
            "-v", "error",
            "-show_entries", "format=format_name,duration:stream=codec_type,codec_name,codec_tag_string,width,height,sample_rate,channels,time_base:stream_disposition=attached_pic",
            "-of", "json",
        )
        arguments += inputArguments
        val session = FFprobeKit.executeWithArguments(arguments.toTypedArray())
        if (!ReturnCode.isSuccess(session.returnCode)) {
            throw MediaCommandException(session.allLogsAsString.ifBlank { "ffprobe failed: $displayName" })
        }

        val json = JSONObject(session.output)
        val formatJson = json.optJSONObject("format")
        val formats = formatJson
            ?.optString("format_name")
            .orEmpty()
            .split(',')
            .filter { it.isNotBlank() }
            .toSet()
        val durationMs = formatJson
            ?.optString("duration")
            ?.toDoubleOrNull()
            ?.takeIf { it.isFinite() && it > 0.0 }
            ?.times(1000.0)
            ?.roundToLong()

        val streamsJson = json.optJSONArray("streams")
        val streams = buildList {
            if (streamsJson != null) {
                for (index in 0 until streamsJson.length()) {
                    val stream = streamsJson.getJSONObject(index)
                    add(
                        StreamSignature(
                            type = stream.optString("codec_type"),
                            codec = stream.optString("codec_name"),
                            codecTag = stream.optString("codec_tag_string").takeIf { it.isNotBlank() },
                            width = stream.optInt("width").takeIf { stream.has("width") },
                            height = stream.optInt("height").takeIf { stream.has("height") },
                            sampleRate = stream.optString("sample_rate").toIntOrNull(),
                            channels = stream.optInt("channels").takeIf { stream.has("channels") },
                            timeBase = stream.optString("time_base").takeIf { it.isNotBlank() },
                            attachedPic = stream.optJSONObject("disposition")
                                ?.optInt("attached_pic", 0) == 1,
                        ),
                    )
                }
            }
        }
        return MediaSignature(formats, streams, durationMs)
    }

    suspend fun keyframeTimesMs(file: File): List<Long> = withContext(Dispatchers.IO) {
        val session = FFprobeKit.executeWithArguments(
            arrayOf(
                "-v", "error",
                "-select_streams", "V:0",
                "-skip_frame", "nokey",
                "-show_frames",
                "-show_entries", "frame=best_effort_timestamp_time",
                "-of", "json",
                file.absolutePath,
            ),
        )
        if (!ReturnCode.isSuccess(session.returnCode)) return@withContext emptyList()

        val frames = JSONObject(session.output).optJSONArray("frames") ?: return@withContext emptyList()
        buildList {
            for (index in 0 until frames.length()) {
                frames.getJSONObject(index)
                    .optString("best_effort_timestamp_time")
                    .toDoubleOrNull()
                    ?.takeIf { it.isFinite() && it >= 0.0 }
                    ?.times(1000.0)
                    ?.roundToLong()
                    ?.let(::add)
            }
        }.distinct().sorted()
    }

    suspend fun cutLossless(request: LosslessCutRequest): File = withContext(Dispatchers.IO) {
        request.output.parentFile?.mkdirs()
        cutLosslessToPath(
            inputPath = request.input.absolutePath,
            outputPath = request.output.absolutePath,
            outputName = request.output.name,
            startMs = request.startMs,
            endMs = request.endMs,
        )
        request.output
    }

    suspend fun cutLosslessToPath(
        inputPath: String,
        outputPath: String,
        outputName: String,
        startMs: Long,
        endMs: Long?,
    ) = withContext(Dispatchers.IO) {
        require(startMs >= 0) { "startMs must be >= 0" }
        require(endMs == null || endMs > startMs) { "endMs must be greater than startMs" }

        val args = mutableListOf(
            "-hide_banner", "-y",
            "-noaccurate_seek",
            "-ss", seconds(startMs),
            "-i", inputPath,
        )
        endMs?.let { end ->
            args += listOf("-t", seconds(end - startMs))
        }
        args += listOf(
            "-map", "0",
            "-c", "copy",
            "-avoid_negative_ts", "make_zero",
            "-f", muxerFor(outputName),
            outputPath,
        )
        runFfmpeg(args)
    }

    suspend fun cutLosslessToDescriptor(
        inputPath: String,
        outputFd: Int,
        outputName: String,
        startMs: Long,
        endMs: Long?,
        onProgressPercent: (Int) -> Unit = {},
    ) = withContext(Dispatchers.IO) {
        require(startMs >= 0) { "startMs must be >= 0" }
        require(endMs == null || endMs > startMs) { "endMs must be greater than startMs" }

        val args = mutableListOf(
            "-hide_banner", "-y",
            "-noaccurate_seek",
            "-ss", seconds(startMs),
            "-i", inputPath,
        )
        endMs?.let { end ->
            args += listOf("-t", seconds(end - startMs))
        }
        args += listOf(
            "-map", "0",
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

    /**
     * Lossless cut from one already-open seekable descriptor to another. This is the fast path for
     * XFiles SMB input: no multi-gigabyte staging copy is required before editing or export.
     */
    suspend fun cutLosslessDescriptors(
        inputFd: Int,
        outputFd: Int,
        outputName: String,
        startMs: Long,
        endMs: Long?,
        onProgressPercent: (Int) -> Unit = {},
    ) = withContext(Dispatchers.IO) {
        require(startMs >= 0) { "startMs must be >= 0" }
        require(endMs == null || endMs > startMs) { "endMs must be greater than startMs" }

        val args = mutableListOf(
            "-hide_banner", "-y",
            "-noaccurate_seek",
            "-ss", seconds(startMs),
            "-fd", inputFd.toString(),
            "-i", "fd:",
        )
        endMs?.let { end ->
            args += listOf("-t", seconds(end - startMs))
        }
        args += listOf(
            "-map", "0",
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

    /**
     * Reencodes only one short GOP fragment around an exact smart-cut boundary. All non-video
     * streams remain stream-copied. The output is a temporary piece that is later interleaved with
     * untouched source pieces by the concat demuxer.
     */
    suspend fun renderSmartBoundaryToPath(
        inputPath: String,
        output: File,
        sourceSignature: MediaSignature,
        segment: MediaSegment,
        onProgressPercent: (Int) -> Unit = {},
    ) = withContext(Dispatchers.IO) {
        renderSmartBoundary(
            inputArguments = listOf("-i", inputPath),
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
        onProgressPercent: (Int) -> Unit = {},
    ) = withContext(Dispatchers.IO) {
        renderSmartBoundary(
            inputArguments = listOf("-fd", inputFd.toString(), "-i", "fd:"),
            output = output,
            sourceSignature = sourceSignature,
            segment = segment,
            onProgressPercent = onProgressPercent,
        )
    }

    private suspend fun renderSmartBoundary(
        inputArguments: List<String>,
        output: File,
        sourceSignature: MediaSignature,
        segment: MediaSegment,
        onProgressPercent: (Int) -> Unit,
    ) {
        val videoStreams = sourceSignature.playbackVideoStreams()
        require(videoStreams.size == 1) {
            "正確カットは映像トラックが1本の動画に対応しています。完全無劣化モードを使用してください"
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
        // `V` excludes attached pictures; `-map 0` still copies the jacket unchanged.
        args += listOf(
            "-t", seconds(segment.durationMs),
            "-map", "0",
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

    suspend fun concatSmartPartsToDescriptor(
        inputPath: String,
        outputFd: Int,
        outputName: String,
        parts: List<SmartConcatInput>,
        expectedDurationMs: Long,
        workingDirectory: File,
        onProgressPercent: (Int) -> Unit = {},
    ) = withContext(Dispatchers.IO) {
        concatSmartParts(
            script = pathSmartConcatScript(inputPath, parts),
            usesFdSource = false,
            outputFd = outputFd,
            outputName = outputName,
            expectedDurationMs = expectedDurationMs,
            workingDirectory = workingDirectory,
            onProgressPercent = onProgressPercent,
        )
    }

    suspend fun concatSmartPartsDescriptors(
        inputFd: Int,
        outputFd: Int,
        outputName: String,
        parts: List<SmartConcatInput>,
        expectedDurationMs: Long,
        workingDirectory: File,
        onProgressPercent: (Int) -> Unit = {},
    ) = withContext(Dispatchers.IO) {
        concatSmartParts(
            script = fdSmartConcatScript(inputFd, parts),
            usesFdSource = true,
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
                "-map", "0",
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

    /**
     * Keeps several ranges from one local source and joins them into one output in a single FFmpeg
     * command. Callers must snap range boundaries to sync frames before reaching this method.
     */
    suspend fun concatSegmentsLosslessToDescriptor(
        inputPath: String,
        outputFd: Int,
        outputName: String,
        segments: List<MediaSegment>,
        workingDirectory: File,
        onProgressPercent: (Int) -> Unit = {},
    ) = withContext(Dispatchers.IO) {
        require(segments.size >= 2) { "At least two segments are required" }
        workingDirectory.mkdirs()
        val listFile = File(workingDirectory, ".clipforge-segments-${System.nanoTime()}.ffconcat")
        try {
            listFile.writeText(pathSegmentConcatScript(inputPath, segments))
            runFfmpeg(
                arguments = listOf(
                    "-hide_banner", "-y",
                    "-f", "concat",
                    "-safe", "0",
                    "-auto_convert", "1",
                    "-i", listFile.absolutePath,
                    "-map", "0",
                    "-c", "copy",
                    "-fflags", "+genpts",
                    "-avoid_negative_ts", "make_zero",
                    "-f", muxerFor(outputName),
                    "-fd", outputFd.toString(),
                    "fd:",
                ),
                expectedDurationMs = segments.sumOf(MediaSegment::durationMs),
                onProgressPercent = onProgressPercent,
            )
        } finally {
            listFile.delete()
        }
    }

    /** Same as [concatSegmentsLosslessToDescriptor], but the source is a seekable Android fd. */
    suspend fun concatSegmentsLosslessDescriptors(
        inputFd: Int,
        outputFd: Int,
        outputName: String,
        segments: List<MediaSegment>,
        workingDirectory: File,
        onProgressPercent: (Int) -> Unit = {},
    ) = withContext(Dispatchers.IO) {
        require(segments.size >= 2) { "At least two segments are required" }
        workingDirectory.mkdirs()
        val listFile = File(workingDirectory, ".clipforge-segments-${System.nanoTime()}.ffconcat")
        try {
            listFile.writeText(fdSegmentConcatScript(inputFd, segments))
            runFfmpeg(
                arguments = listOf(
                    "-hide_banner", "-y",
                    "-protocol_whitelist", "file,fd,crypto,data",
                    "-f", "concat",
                    "-safe", "0",
                    "-auto_convert", "1",
                    "-i", listFile.absolutePath,
                    "-map", "0",
                    "-c", "copy",
                    "-fflags", "+genpts",
                    "-avoid_negative_ts", "make_zero",
                    "-f", muxerFor(outputName),
                    "-fd", outputFd.toString(),
                    "fd:",
                ),
                expectedDurationMs = segments.sumOf(MediaSegment::durationMs),
                onProgressPercent = onProgressPercent,
            )
        } finally {
            listFile.delete()
        }
    }

    suspend fun concatLossless(inputs: List<File>, output: File): File = withContext(Dispatchers.IO) {
        require(inputs.size >= 2) { "At least two files are required" }
        output.parentFile?.mkdirs()
        concatLosslessPaths(
            inputs = inputs.map { NamedMediaPath(it.absolutePath, it.name) },
            outputPath = output.absolutePath,
            outputName = output.name,
            workingDirectory = requireNotNull(output.parentFile),
        )
        output
    }

    suspend fun requireLosslessConcatCompatibility(
        inputs: List<NamedMediaPath>,
    ) = withContext(Dispatchers.IO) {
        require(inputs.size >= 2) { "At least two files are required" }
        val signatures = inputs.map { input ->
            NamedMediaSignature(input.displayName, probePath(input.path, input.displayName))
        }
        requireLosslessConcatCompatibility(signatures)
    }

    fun requireLosslessConcatCompatibility(
        inputs: List<NamedMediaSignature>,
    ) {
        require(inputs.size >= 2) { "At least two files are required" }
        val baseline = inputs.first()
        inputs.drop(1).forEach { input ->
            val mismatch = losslessConcatMismatch(baseline.signature, input.signature)
            if (mismatch != null) {
                throw IncompatibleMediaException(
                    "${input.displayName} は ${baseline.displayName} と無劣化結合条件が一致しません: $mismatch。" +
                        "ClipForge は自動で再エンコードしません。",
                )
            }
        }
    }

    suspend fun concatLosslessPaths(
        inputs: List<NamedMediaPath>,
        outputPath: String,
        outputName: String,
        workingDirectory: File,
    ) = withContext(Dispatchers.IO) {
        requireLosslessConcatCompatibility(inputs)
        concatLosslessPathsValidated(
            inputs = inputs,
            outputPath = outputPath,
            outputName = outputName,
            workingDirectory = workingDirectory,
        )
    }

    suspend fun concatLosslessPathsValidated(
        inputs: List<NamedMediaPath>,
        outputPath: String,
        outputName: String,
        workingDirectory: File,
    ) = withContext(Dispatchers.IO) {
        require(inputs.size >= 2) { "At least two files are required" }
        workingDirectory.mkdirs()
        val listFile = File(workingDirectory, ".clipforge-concat-${System.nanoTime()}.txt")
        try {
            listFile.writeText(inputs.joinToString("\n") { "file '${escapeConcatPath(it.path)}'" })
            runFfmpeg(
                listOf(
                    "-hide_banner", "-y",
                    "-f", "concat",
                    "-safe", "0",
                    "-auto_convert", "1",
                    "-i", listFile.absolutePath,
                    "-map", "0",
                    "-c", "copy",
                    "-fflags", "+genpts",
                    "-f", muxerFor(outputName),
                    outputPath,
                ),
            )
        } finally {
            listFile.delete()
        }
    }

    /**
     * Lossless concat over already-open seekable descriptors. The ffconcat `option fd` directive
     * lets each entry select its own descriptor while FFmpeg's fd: protocol dup()s the supplied fd.
     * This keeps descriptor ownership in Kotlin, avoids Android procfs/SELinux, and avoids the old
     * FFmpegKit saf: JNI worker-thread bug.
     */
    suspend fun concatLosslessDescriptorsValidated(
        inputs: List<NamedMediaDescriptor>,
        outputFd: Int,
        outputName: String,
        workingDirectory: File,
    ) = withContext(Dispatchers.IO) {
        require(inputs.size >= 2) { "At least two files are required" }
        workingDirectory.mkdirs()
        val listFile = File(workingDirectory, ".clipforge-concat-${System.nanoTime()}.ffconcat")
        try {
            listFile.writeText(fdConcatScript(inputs))
            runFfmpeg(
                listOf(
                    "-hide_banner", "-y",
                    "-protocol_whitelist", "file,fd,crypto,data",
                    "-f", "concat",
                    "-safe", "0",
                    "-auto_convert", "1",
                    "-i", listFile.absolutePath,
                    "-map", "0",
                    "-c", "copy",
                    "-fflags", "+genpts",
                    "-f", muxerFor(outputName),
                    "-fd", outputFd.toString(),
                    "fd:",
                ),
            )
        } finally {
            listFile.delete()
        }
    }

    private suspend fun runFfmpeg(
        arguments: List<String>,
        expectedDurationMs: Long? = null,
        onProgressPercent: (Int) -> Unit = {},
    ) {
        val completion = CompletableDeferred<FFmpegSession>()
        val session = FFmpegKit.executeWithArgumentsAsync(
            arguments.toTypedArray(),
            { completed -> completion.complete(completed) },
            null,
            { statistics ->
                expectedDurationMs
                    ?.takeIf { it > 0L && statistics.time.isFinite() }
                    ?.let { durationMs ->
                        val percent = ((statistics.time / durationMs.toDouble()) * 100.0)
                            .roundToInt()
                            .coerceIn(0, 99)
                        onProgressPercent(percent)
                    }
            },
        )

        try {
            val completed = completion.await()
            if (!ReturnCode.isSuccess(completed.returnCode)) {
                throw MediaCommandException(
                    completed.allLogsAsString.ifBlank { "FFmpeg command failed" },
                )
            }
            if (expectedDurationMs != null && expectedDurationMs > 0L) {
                onProgressPercent(100)
            }
        } catch (cancelled: CancellationException) {
            FFmpegKit.cancel(session.sessionId)
            throw cancelled
        }
    }

    private fun smartBoundaryBitrate(stream: StreamSignature, codec: String): Long {
        val width = stream.width ?: 1920
        val height = stream.height ?: 1080
        val pixels = width.toLong() * height.toLong()
        val h264 = when {
            pixels >= 3_840L * 2_160L -> 30_000_000L
            pixels >= 1_920L * 1_080L -> 12_000_000L
            pixels >= 1_280L * 720L -> 6_000_000L
            else -> 3_000_000L
        }
        return if (codec == "h264") h264 else (h264 * 2L / 3L)
    }

    private fun videoTimeScale(timeBase: String?): Int? {
        val pieces = timeBase?.split('/') ?: return null
        if (pieces.size != 2 || pieces[0] != "1") return null
        return pieces[1].toIntOrNull()?.takeIf { it in 1..1_000_000 }
    }

    private fun muxerFor(outputName: String): String = when (
        outputName.substringAfterLast('.', "").lowercase()
    ) {
        "mp4" -> "mp4"
        "mkv" -> "matroska"
        else -> throw IllegalArgumentException("Output must end in .mp4 or .mkv")
    }

    private fun seconds(ms: Long): String = concatTimestamp(ms)

    private fun escapeConcatPath(path: String): String = escapeConcatPathValue(path)
}
