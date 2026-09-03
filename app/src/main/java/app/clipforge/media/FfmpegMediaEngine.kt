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

class FfmpegMediaEngine {

    suspend fun probe(file: File): MediaSignature = probePath(file.absolutePath, file.name)

    suspend fun probePath(path: String, displayName: String = path): MediaSignature = withContext(Dispatchers.IO) {
        val session = FFprobeKit.executeWithArguments(
            arrayOf(
                "-v", "error",
                "-show_entries", "format=format_name,duration:stream=codec_type,codec_name,codec_tag_string,width,height,sample_rate,channels,time_base",
                "-of", "json",
                path,
            ),
        )
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
        MediaSignature(formats, streams, durationMs)
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
        val signatures = inputs.map { probePath(it.path, it.displayName) }
        val expected = signatures.first().streams
        signatures.drop(1).forEachIndexed { index, signature ->
            if (signature.streams != expected) {
                throw IncompatibleMediaException(
                    "${inputs[index + 1].displayName} has a different stream layout/codec. " +
                        "ClipForge will not silently re-encode it.",
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

    /**
     * Runs concat after compatibility has already been verified. This split is required for
     * FFmpegKit SAF urls because each SAF id is removed when FFprobe/FFmpeg closes it, making the
     * url intentionally single-use.
     */
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
                    // The concat demuxer restricts nested protocols by default. Direct XFiles/SMB
                    // inputs use FFmpegKit's custom saf: protocol, so explicitly allow it.
                    "-protocol_whitelist", "file,saf,crypto,data",
                    "-f", "concat",
                    "-safe", "0",
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
