package app.clipforge.media

import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFprobeKit
import com.arthenica.ffmpegkit.ReturnCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

class FfmpegMediaEngine {

    suspend fun probe(file: File): MediaSignature = withContext(Dispatchers.IO) {
        val session = FFprobeKit.executeWithArguments(
            arrayOf(
                "-v", "error",
                "-show_entries", "format=format_name:stream=codec_type,codec_name,codec_tag_string,width,height,sample_rate,channels,time_base",
                "-of", "json",
                file.absolutePath
            )
        )
        if (!ReturnCode.isSuccess(session.returnCode)) {
            throw MediaCommandException(session.allLogsAsString.ifBlank { "ffprobe failed: ${file.name}" })
        }

        val json = JSONObject(session.output)
        val formats = json.optJSONObject("format")
            ?.optString("format_name")
            .orEmpty()
            .split(',')
            .filter { it.isNotBlank() }
            .toSet()

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
                            timeBase = stream.optString("time_base").takeIf { it.isNotBlank() }
                        )
                    )
                }
            }
        }
        MediaSignature(formats, streams)
    }

    suspend fun cutLossless(request: LosslessCutRequest): File = withContext(Dispatchers.IO) {
        require(request.startMs >= 0) { "startMs must be >= 0" }
        require(request.endMs == null || request.endMs > request.startMs) { "endMs must be greater than startMs" }
        request.output.parentFile?.mkdirs()

        val args = mutableListOf(
            "-hide_banner", "-y",
            "-noaccurate_seek",
            "-ss", seconds(request.startMs),
            "-i", request.input.absolutePath
        )
        request.endMs?.let { endMs ->
            args += listOf("-t", seconds(endMs - request.startMs))
        }
        args += listOf(
            "-map", "0",
            "-c", "copy",
            "-avoid_negative_ts", "make_zero",
            request.output.absolutePath
        )
        runFfmpeg(args)
        request.output
    }

    suspend fun concatLossless(inputs: List<File>, output: File): File = withContext(Dispatchers.IO) {
        require(inputs.size >= 2) { "At least two files are required" }
        val signatures = inputs.map { probe(it) }
        val expected = signatures.first().streams
        signatures.drop(1).forEachIndexed { index, signature ->
            if (signature.streams != expected) {
                throw IncompatibleMediaException(
                    "${inputs[index + 1].name} has a different stream layout/codec. " +
                        "ClipForge will not silently re-encode it."
                )
            }
        }

        output.parentFile?.mkdirs()
        val listFile = File(output.parentFile, ".clipforge-concat-${System.nanoTime()}.txt")
        try {
            listFile.writeText(inputs.joinToString("\n") { "file '${escapeConcatPath(it.absolutePath)}'" })
            runFfmpeg(
                listOf(
                    "-hide_banner", "-y",
                    "-f", "concat",
                    "-safe", "0",
                    "-i", listFile.absolutePath,
                    "-map", "0",
                    "-c", "copy",
                    "-fflags", "+genpts",
                    output.absolutePath
                )
            )
        } finally {
            listFile.delete()
        }
        output
    }

    private fun runFfmpeg(arguments: List<String>) {
        val session = FFmpegKit.executeWithArguments(arguments.toTypedArray())
        if (!ReturnCode.isSuccess(session.returnCode)) {
            throw MediaCommandException(session.allLogsAsString.ifBlank { "FFmpeg command failed" })
        }
    }

    private fun seconds(ms: Long): String = "%.3f".format(java.util.Locale.US, ms / 1000.0)

    private fun escapeConcatPath(path: String): String = path.replace("'", "'\\''")
}
