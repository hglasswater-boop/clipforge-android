package app.clipforge.media

import java.io.File

data class StreamSignature(
    val type: String,
    val codec: String,
    val codecTag: String?,
    val width: Int?,
    val height: Int?,
    val sampleRate: Int?,
    val channels: Int?,
    val timeBase: String?
)

data class MediaSignature(
    val formatNames: Set<String>,
    val streams: List<StreamSignature>
)

data class LosslessCutRequest(
    val input: File,
    val output: File,
    val startMs: Long,
    val endMs: Long?
)

class IncompatibleMediaException(message: String) : IllegalArgumentException(message)
class MediaCommandException(message: String) : IllegalStateException(message)
