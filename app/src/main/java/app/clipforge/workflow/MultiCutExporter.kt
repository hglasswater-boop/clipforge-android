package app.clipforge.workflow

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import app.clipforge.media.FfmpegMediaEngine
import app.clipforge.media.MediaSegment
import app.clipforge.media.remainingSegments
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID

class MultiCutExporter(
    private val cacheRoot: File,
    context: Context,
    private val mediaEngine: FfmpegMediaEngine,
) {
    private val contentResolver = context.applicationContext.contentResolver

    suspend fun export(
        source: PickedVideo,
        localInputPath: String?,
        sessionPath: String,
        durationMs: Long,
        cutRanges: List<MediaSegment>,
        outputUri: String,
        outputName: String,
        onProgress: (String) -> Unit = {},
    ) = withContext(Dispatchers.IO) {
        val keepSegments = remainingSegments(durationMs, cutRanges)
        require(keepSegments.isNotEmpty()) { "動画全体を削除する指定になっています" }

        val destination = Uri.parse(outputUri)
        val remoteDestination = isXFilesRemoteOutput(destination)
        val destinationLabel = if (remoteDestination) "SMB" else "端末"
        val workDir = File(cacheRoot, "clipforge/multi-cut/${UUID.randomUUID()}").apply { mkdirs() }
        var inputDescriptor: ParcelFileDescriptor? = null
        var outputDescriptor: ParcelFileDescriptor? = null

        try {
            onProgress("入力と${destinationLabel}保存先を開いています")
            outputDescriptor = openReadWriteDescriptor(destination)

            if (localInputPath != null) {
                val input = File(localInputPath)
                require(input.isFile) { "編集用キャッシュが見つかりません" }
                onProgress("${cutRanges.size}箇所を反映して無劣化保存中")
                if (keepSegments.size == 1) {
                    val segment = keepSegments.single()
                    mediaEngine.cutLosslessToDescriptor(
                        inputPath = input.absolutePath,
                        outputFd = outputDescriptor.fd,
                        outputName = outputName,
                        startMs = segment.startMs,
                        endMs = segment.endMs,
                    )
                } else {
                    mediaEngine.concatSegmentsLosslessToDescriptor(
                        inputPath = input.absolutePath,
                        outputFd = outputDescriptor.fd,
                        outputName = outputName,
                        segments = keepSegments,
                        workingDirectory = workDir,
                    )
                }
            } else {
                inputDescriptor = openReadDescriptor(source)
                onProgress("${cutRanges.size}箇所を反映して無劣化保存中")
                if (keepSegments.size == 1) {
                    val segment = keepSegments.single()
                    mediaEngine.cutLosslessDescriptors(
                        inputFd = inputDescriptor.fd,
                        outputFd = outputDescriptor.fd,
                        outputName = outputName,
                        startMs = segment.startMs,
                        endMs = segment.endMs,
                    )
                } else {
                    mediaEngine.concatSegmentsLosslessDescriptors(
                        inputFd = inputDescriptor.fd,
                        outputFd = outputDescriptor.fd,
                        outputName = outputName,
                        segments = keepSegments,
                        workingDirectory = workDir,
                    )
                }
            }

            runCatching { outputDescriptor.close() }
            outputDescriptor = null
            runCatching { inputDescriptor?.close() }
            inputDescriptor = null

            if (remoteDestination) {
                onProgress("SMB保存を確定しています")
                commitRemoteOutput(destination)
                onProgress("SMBへ保存しました")
            } else {
                onProgress("端末へ保存しました")
            }
            discardSession(sessionPath)
        } catch (error: Throwable) {
            abortOutput(destination)
            throw error
        } finally {
            runCatching { outputDescriptor?.close() }
            runCatching { inputDescriptor?.close() }
            workDir.deleteRecursively()
        }
    }

    private fun openReadDescriptor(source: PickedVideo): ParcelFileDescriptor {
        val descriptor = contentResolver.openFileDescriptor(Uri.parse(source.uri), "r")
            ?: throw IOException("入力を開けません: ${source.displayName}")
        return validateSeekableDescriptor(descriptor, "入力 ${source.displayName}")
    }

    private fun openReadWriteDescriptor(uri: Uri): ParcelFileDescriptor {
        val descriptor = contentResolver.openFileDescriptor(uri, "rw")
            ?: throw IOException("保存先を開けません")
        return validateSeekableDescriptor(descriptor, "保存先")
    }

    private fun validateSeekableDescriptor(
        descriptor: ParcelFileDescriptor,
        label: String,
    ): ParcelFileDescriptor {
        try {
            Os.lseek(descriptor.fileDescriptor, 0L, OsConstants.SEEK_SET)
            return descriptor
        } catch (error: Throwable) {
            runCatching { descriptor.close() }
            throw IOException("$label がシーク可能なファイルとして開けません", error)
        }
    }

    private fun isXFilesRemoteOutput(uri: Uri): Boolean =
        uri.authority == XFILES_REMOTE_PROVIDER_AUTHORITY && uri.getQueryParameter("mode") == "output"

    private fun commitRemoteOutput(uri: Uri) {
        val values = ContentValues().apply { put(REMOTE_COMMIT_KEY, true) }
        val updated = contentResolver.update(uri, values, null, null)
        if (updated != 1) throw IOException("SMB出力を確定できませんでした")
    }

    private fun abortOutput(uri: Uri) {
        runCatching { contentResolver.delete(uri, null, null) }
    }

    private fun discardSession(sessionPath: String) {
        runCatching {
            val root = File(cacheRoot, "clipforge/external-edit").canonicalFile
            val session = File(sessionPath).canonicalFile
            if (session.path.startsWith(root.path + File.separator)) session.deleteRecursively()
        }
    }

    private companion object {
        const val XFILES_REMOTE_PROVIDER_AUTHORITY = "app.local1st.files.remotefileprovider"
        const val REMOTE_COMMIT_KEY = "commit"
    }
}
