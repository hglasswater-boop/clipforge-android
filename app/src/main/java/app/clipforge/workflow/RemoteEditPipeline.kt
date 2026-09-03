package app.clipforge.workflow

import app.clipforge.media.FfmpegMediaEngine
import app.clipforge.media.LosslessCutRequest
import app.clipforge.media.TimelineThumbnailGenerator
import app.clipforge.smb.SmbClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.UUID

data class PreparedCutSession(
    val remoteInput: String,
    val localFile: File,
    val durationMs: Long,
    val keyframesMs: List<Long>,
    val thumbnailPaths: List<String>
)

class RemoteEditPipeline(
    private val cacheRoot: File,
    private val smbClient: SmbClient,
    private val mediaEngine: FfmpegMediaEngine,
    private val thumbnailGenerator: TimelineThumbnailGenerator = TimelineThumbnailGenerator()
) {
    private val editSessionRoot = File(cacheRoot, "clipforge/edit")

    suspend fun concat(
        remoteInputs: List<String>,
        remoteOutput: String,
        onProgress: (String) -> Unit = {}
    ) = withContext(Dispatchers.IO) {
        ensureSafeOutput(remoteInputs, remoteOutput)
        ensureCacheCapacity(remoteInputs)

        val workDir = createWorkDir()
        try {
            val localInputs = remoteInputs.mapIndexed { index, remote ->
                val fileName = remote.substringAfterLast('/')
                File(workDir, "%03d-%s".format(index, fileName)).also { local ->
                    smbClient.download(remote, local) { done, total ->
                        onProgress("取得中 ${index + 1}/${remoteInputs.size} ${percent(done, total)}%: $fileName")
                    }
                }
            }
            val output = File(workDir, remoteOutput.substringAfterLast('/'))
            onProgress("無劣化で結合中")
            mediaEngine.concatLossless(localInputs, output)
            smbClient.uploadAtomically(output, remoteOutput) { done, total ->
                onProgress("SMBへ保存中 ${percent(done, total)}%")
            }
            onProgress("完了: $remoteOutput")
        } finally {
            workDir.deleteRecursively()
        }
    }

    suspend fun prepareCut(
        remoteInput: String,
        onProgress: (String) -> Unit = {}
    ): PreparedCutSession = withContext(Dispatchers.IO) {
        ensureCacheCapacity(listOf(remoteInput))
        val workDir = createEditWorkDir()
        try {
            val input = File(workDir, remoteInput.substringAfterLast('/'))
            smbClient.download(remoteInput, input) { done, total ->
                onProgress("編集用にSMBから取得中 ${percent(done, total)}%: ${remoteInput.substringAfterLast('/')}")
            }
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
            PreparedCutSession(remoteInput, input, durationMs, keyframes, thumbnails)
        } catch (t: Throwable) {
            workDir.deleteRecursively()
            throw t
        }
    }

    suspend fun cutPrepared(
        localInputPath: String,
        remoteInput: String,
        remoteOutput: String,
        startMs: Long,
        endMs: Long,
        onProgress: (String) -> Unit = {}
    ) = withContext(Dispatchers.IO) {
        ensureSafeOutput(listOf(remoteInput), remoteOutput)
        val input = validatedPreparedFile(localInputPath)
        val output = File(input.parentFile, ".clipforge-output-${System.nanoTime()}-${remoteOutput.substringAfterLast('/')}")
        try {
            onProgress("無劣化でカット中")
            mediaEngine.cutLossless(LosslessCutRequest(input, output, startMs, endMs))
            smbClient.uploadAtomically(output, remoteOutput) { done, total ->
                onProgress("SMBへ保存中 ${percent(done, total)}%")
            }
            onProgress("完了: $remoteOutput")
        } finally {
            output.delete()
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
    }

    private fun validatedPreparedFile(localInputPath: String): File {
        val root = editSessionRoot.canonicalFile
        val input = File(localInputPath).canonicalFile
        val insideRoot = input.path.startsWith(root.path + File.separator)
        require(insideRoot && input.isFile) { "編集用キャッシュが見つかりません" }
        return input
    }

    private fun ensureSafeOutput(remoteInputs: List<String>, remoteOutput: String) {
        if (remoteInputs.any { it.equals(remoteOutput, ignoreCase = true) }) {
            throw IllegalArgumentException("入力動画と同じ名前には出力できません。原本保護のため処理を中止しました。")
        }
        if (smbClient.exists(remoteOutput)) {
            throw IllegalArgumentException("出力先に同名ファイルが既にあります: $remoteOutput")
        }
    }

    private fun ensureCacheCapacity(remoteInputs: List<String>) {
        var inputBytes = 0L
        for (path in remoteInputs) {
            inputBytes = saturatingAdd(inputBytes, smbClient.size(path))
        }

        val reserveBytes = 256L * 1024L * 1024L
        val requiredBytes = if (inputBytes > (Long.MAX_VALUE - reserveBytes) / 2L) {
            Long.MAX_VALUE
        } else {
            inputBytes * 2L + reserveBytes
        }
        val availableBytes = cacheRoot.usableSpace

        if (availableBytes < requiredBytes) {
            throw IllegalStateException(
                "端末の空き容量が不足しています。必要 約${formatGiB(requiredBytes)}GB / 空き 約${formatGiB(availableBytes)}GB"
            )
        }
    }

    private fun percent(done: Long, total: Long): Int {
        if (total <= 0L) return 0
        return ((done.toDouble() / total.toDouble()) * 100.0).toInt().coerceIn(0, 100)
    }

    private fun saturatingAdd(left: Long, right: Long): Long {
        if (right <= 0L) return left
        return if (left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right
    }

    private fun formatGiB(bytes: Long): String =
        String.format(Locale.US, "%.1f", bytes / (1024.0 * 1024.0 * 1024.0))

    private fun createWorkDir(): File = File(cacheRoot, "clipforge/${UUID.randomUUID()}").apply { mkdirs() }

    private fun createEditWorkDir(): File = File(editSessionRoot, UUID.randomUUID().toString()).apply { mkdirs() }
}
