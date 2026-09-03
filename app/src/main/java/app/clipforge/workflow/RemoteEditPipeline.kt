package app.clipforge.workflow

import app.clipforge.media.FfmpegMediaEngine
import app.clipforge.media.LosslessCutRequest
import app.clipforge.smb.SmbClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.UUID

class RemoteEditPipeline(
    private val cacheRoot: File,
    private val smbClient: SmbClient,
    private val mediaEngine: FfmpegMediaEngine
) {
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
                onProgress("取得中 ${index + 1}/${remoteInputs.size}: ${remote.substringAfterLast('/')}")
                File(workDir, "%03d-%s".format(index, remote.substringAfterLast('/'))).also {
                    smbClient.download(remote, it)
                }
            }
            val output = File(workDir, remoteOutput.substringAfterLast('/'))
            onProgress("無劣化で結合中")
            mediaEngine.concatLossless(localInputs, output)
            onProgress("SMBへ保存中")
            smbClient.uploadAtomically(output, remoteOutput)
            onProgress("完了: $remoteOutput")
        } finally {
            workDir.deleteRecursively()
        }
    }

    suspend fun cut(
        remoteInput: String,
        remoteOutput: String,
        startMs: Long,
        endMs: Long?,
        onProgress: (String) -> Unit = {}
    ) = withContext(Dispatchers.IO) {
        ensureSafeOutput(listOf(remoteInput), remoteOutput)
        ensureCacheCapacity(listOf(remoteInput))

        val workDir = createWorkDir()
        try {
            val input = File(workDir, remoteInput.substringAfterLast('/'))
            val output = File(workDir, remoteOutput.substringAfterLast('/'))
            onProgress("SMBから取得中")
            smbClient.download(remoteInput, input)
            onProgress("無劣化でカット中")
            mediaEngine.cutLossless(LosslessCutRequest(input, output, startMs, endMs))
            onProgress("SMBへ保存中")
            smbClient.uploadAtomically(output, remoteOutput)
            onProgress("完了: $remoteOutput")
        } finally {
            workDir.deleteRecursively()
        }
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

    private fun saturatingAdd(left: Long, right: Long): Long {
        if (right <= 0L) return left
        return if (left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right
    }

    private fun formatGiB(bytes: Long): String =
        String.format(Locale.US, "%.1f", bytes / (1024.0 * 1024.0 * 1024.0))

    private fun createWorkDir(): File = File(cacheRoot, "clipforge/${UUID.randomUUID()}").apply { mkdirs() }
}
