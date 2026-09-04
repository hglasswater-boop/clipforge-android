package app.clipforge.media

import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFprobeKit
import com.arthenica.ffmpegkit.ReturnCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
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

internal fun fdConcatScript(inputs: List<NamedMediaDescriptor>): String = buildString {
    appendLine("ffconcat version 1.0")
    inputs.forEach { input ->
        appendLine("file 'fd:'")
        appendLine("option fd ${input.fd}")
    }
}

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
            "-show_entries", "format=format_name,duration:stream=codec_type,codec_name,codec_tag_string,width,height,sample_rate,channels,time_base",
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
                "-select_streams", "v:0",
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
        runFfmpeg(args)
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

    private fun runFfmpeg(arguments: List<String>) {
        val session = FFmpegKit.executeWithArguments(arguments.toTypedArray())
        if (!ReturnCode.isSuccess(session.returnCode)) {
            throw MediaCommandException(session.allLogsAsString.ifBlank { "FFmpeg command failed" })
        }
    }

    private fun muxerFor(outputName: String): String = when (
        outputName.substringAfterLast('.', "").lowercase()
    ) {
        "mp4" -> "mp4"
        "mkv" -> "matroska"
        else -> throw IllegalArgumentException("Output must end in .mp4 or .mkv")
    }

    private fun seconds(ms: Long): String = "%.3f".format(java.util.Locale.US, ms / 1000.0)

    private fun escapeConcatPath(path: String): String = path.replace("'", "'\\''")
}
