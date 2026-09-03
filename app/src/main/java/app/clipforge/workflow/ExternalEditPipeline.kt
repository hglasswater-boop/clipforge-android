package app.clipforge.workflow

import android.content.ContentResolver
import android.net.Uri
import app.clipforge.media.FfmpegMediaEngine
import app.clipforge.media.LosslessCutRequest
import app.clipforge.media.TimelineThumbnailGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.UUID

/** A video selected through an external file manager/content provider. */
data class PickedVideo(
    val uri: String,
    val displayName: String,
    val sizeBytes: Long? = null,
)

data class PreparedExternalCutSession(
    val source: PickedVideo,
    val localFile: File,
    val durationMs: Long,
    val keyframesMs: List<Long>,
    val thumbnailPaths: List<String>,
)

/**
 * Stages content:// inputs locally, performs stream-copy editing, and leaves the result in the
 * app cache so FileProvider can hand it back to XFiles. XFiles remains responsible for SMB access
 * and the final destination, so ClipForge never needs SMB credentials.
 */
class ExternalEditPipeline(
    private val cacheRoot: File,
    private val contentResolver: ContentResolver,
    private val mediaEngine: FfmpegMediaEngine,
    private val thumbnailGenerator: TimelineThumbnailGenerator = TimelineThumbnailGenerator(),
) {
    private val workRoot = File(cacheRoot, "clipforge/external-work")
    private val editSessionRoot = File(cacheRoot, "clipforge/external-edit")
    private val outgoingRoot = File(cacheRoot, "clipforge/outgoing")

    init {
        cleanupOldOutgoing()
    }

    suspend fun concat(
        inputs: List<PickedVideo>,
        outputName: String,
        onProgress: (String) -> Unit = {},
    ): File = withContext(Dispatchers.IO) {
        require(inputs.size >= 2) { "結合する動画を2本以上選択してください" }
        ensureCacheCapacity(inputs)
        val workDir = createWorkDir()
        try {
            val localInputs = inputs.mapIndexed { index, source ->
                val local = File(workDir, "%03d-%s".format(index, safeLocalName(source.displayName)))
                onProgress("取得中 ${index + 1}/${inputs.size}: ${source.displayName}")
                copyToLocal(source, local)
                local
            }
            val output = createOutgoingFile(outputName)
            try {
                onProgress("無劣化で結合中")
                mediaEngine.concatLossless(localInputs, output)
                onProgress("結合完了。XFilesへ渡します")
                output
            } catch (error: Throwable) {
                output.delete()
                output.parentFile?.delete()
                throw error
            }
        } finally {
            workDir.deleteRecursively()
        }
    }

    suspend fun prepareCut(
        source: PickedVideo,
        onProgress: (String) -> Unit = {},
    ): PreparedExternalCutSession = withContext(Dispatchers.IO) {
        ensureCacheCapacity(listOf(source))
        val workDir = createEditWorkDir()
        try {
            val input = File(workDir, safeLocalName(source.displayName))
            onProgress("編集用に取得中: ${source.displayName}")
            copyToLocal(source, input)
            onProgress("動画情報を解析中")
            val signature = mediaEngine.probe(input)
            val durationMs = signature.durationMs
                ?: throw IllegalStateException("動画の長さを取得できませんでした")
            onProgress("キーフレームを解析中")
            val keyframes = mediaEngine.keyframeTimesMs(input)
            onProgress("タイムラインを作成中")
            val thumbnails = thumbnailGenerator
                .generate(input, File(workDir, "timeline"), durationMs)
                .map { it.absolutePath }
            PreparedExternalCutSession(source, input, durationMs, keyframes, thumbnails)
        } catch (error: Throwable) {
            workDir.deleteRecursively()
            throw error
        }
    }

    suspend fun cutPrepared(
        localInputPath: String,
        outputName: String,
        startMs: Long,
        endMs: Long,
        onProgress: (String) -> Unit = {},
    ): File = withContext(Dispatchers.IO) {
        val input = validatedPreparedFile(localInputPath)
        val output = createOutgoingFile(outputName)
        try {
            onProgress("無劣化でカット中")
            mediaEngine.cutLossless(LosslessCutRequest(input, output, startMs, endMs))
            onProgress("カット完了。XFilesへ渡します")
            output
        } catch (error: Throwable) {
            output.delete()
            output.parentFile?.delete()
            throw error
        }
    }

    fun discardPrepared(localInputPath: String) {
        runCatching {
            val input = validatedPreparedFile(localInputPath)
            input.parentFile?.deleteRecursively()
        }
    }

    fun cleanupPreparedSessions() {
        runCatching { editSessionRoot.deleteRecursively() }
        runCatching { workRoot.deleteRecursively() }
    }

    private fun copyToLocal(source: PickedVideo, target: File) {
        target.parentFile?.mkdirs()
        val uri = Uri.parse(source.uri)
        val input = contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("ファイルを開けません: ${source.displayName}")
        input.use { from ->
            target.outputStream().buffered().use { to -> from.copyTo(to) }
        }
    }

    private fun validatedPreparedFile(localInputPath: String): File {
        val root = editSessionRoot.canonicalFile
        val input = File(localInputPath).canonicalFile
        val insideRoot = input.path.startsWith(root.path + File.separator)
        require(insideRoot && input.isFile) { "編集用キャッシュが見つかりません" }
        return input
    }

    private fun ensureCacheCapacity(inputs: List<PickedVideo>) {
        var inputBytes = 0L
        var sizeKnown = true
        for (source in inputs) {
            val size = source.sizeBytes?.takeIf { it >= 0L }
            if (size == null) {
                sizeKnown = false
                break
            }
            inputBytes = saturatingAdd(inputBytes, size)
        }
        if (!sizeKnown) return

        val reserveBytes = 256L * 1024L * 1024L
        val requiredBytes = if (inputBytes > (Long.MAX_VALUE - reserveBytes) / 2L) {
            Long.MAX_VALUE
        } else {
            inputBytes * 2L + reserveBytes
        }
        val availableBytes = cacheRoot.usableSpace
        if (availableBytes < requiredBytes) {
            throw IllegalStateException(
                "端末の空き容量が不足しています。必要 約${formatGiB(requiredBytes)}GB / 空き 約${formatGiB(availableBytes)}GB",
            )
        }
    }

    private fun safeLocalName(name: String): String =
        name.replace(Regex("[\\/\\x00-\\x1F]"), "_").ifBlank { "video.mkv" }

    private fun createWorkDir(): File = File(workRoot, UUID.randomUUID().toString()).apply { mkdirs() }

    private fun createEditWorkDir(): File = File(editSessionRoot, UUID.randomUUID().toString()).apply { mkdirs() }

    private fun createOutgoingFile(outputName: String): File =
        File(File(outgoingRoot, UUID.randomUUID().toString()).apply { mkdirs() }, outputName)

    private fun cleanupOldOutgoing() {
        runCatching {
            if (!outgoingRoot.isDirectory) return@runCatching
            val cutoff = System.currentTimeMillis() - OUTGOING_RETENTION_MS
            outgoingRoot.listFiles().orEmpty().forEach { session ->
                if (session.lastModified() < cutoff) session.deleteRecursively()
            }
        }
    }

    private fun saturatingAdd(left: Long, right: Long): Long {
        if (right <= 0L) return left
        return if (left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right
    }

    private fun formatGiB(bytes: Long): String =
        String.format(Locale.US, "%.1f", bytes / (1024.0 * 1024.0 * 1024.0))

    private companion object {
        const val OUTGOING_RETENTION_MS = 24L * 60L * 60L * 1000L
    }
}
