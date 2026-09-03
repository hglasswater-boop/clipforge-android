package app.clipforge.workflow

import app.clipforge.media.FfmpegMediaEngine
import app.clipforge.media.LosslessCutRequest
import app.clipforge.smb.SmbClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
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

    private fun createWorkDir(): File = File(cacheRoot, "clipforge/${UUID.randomUUID()}").apply { mkdirs() }
}
