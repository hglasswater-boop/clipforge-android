package app.clipforge.workflow

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import app.clipforge.media.FfmpegMediaEngine
import app.clipforge.media.LosslessCutRequest
import app.clipforge.media.NamedMediaDescriptor
import app.clipforge.media.NamedMediaSignature
import app.clipforge.media.TimelineThumbnailGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
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
 * External-file pipeline. Legacy fallback operations stage content locally. Direct operations keep
 * XFiles' seekable SMB ParcelFileDescriptors open and hand their numeric descriptors to FFmpeg's
 * fd: protocol. FFmpeg dup()s each descriptor internally, so it never has to reopen /proc/self/fd
 * (blocked by Android SELinux on current devices) and never needs FFmpegKit's saf: JNI callbacks.
 */
class ExternalEditPipeline(
    private val cacheRoot: File,
    context: Context,
    private val mediaEngine: FfmpegMediaEngine,
    private val thumbnailGenerator: TimelineThumbnailGenerator = TimelineThumbnailGenerator(),
) {
    private val contentResolver = context.applicationContext.contentResolver
    private val workRoot = File(cacheRoot, "clipforge/external-work")
    private val editSessionRoot = File(cacheRoot, "clipforge/external-edit")
    private val outgoingRoot = File(cacheRoot, "clipforge/outgoing")

    init {
        cleanupOldOutgoing()
        cleanupOldPrepared()
    }

    /** Legacy fallback when the installed file manager cannot provide a direct writable output. */
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

    /**
     * Direct path: XFiles owns the SMB transport, while FFmpeg consumes its seekable descriptors.
     * No source file is copied to local storage and stream copy (`-c copy`) remains lossless.
     */
    suspend fun concatDirect(
        inputs: List<PickedVideo>,
        outputUri: String,
        outputName: String,
        onProgress: (String) -> Unit = {},
    ) = withContext(Dispatchers.IO) {
        require(inputs.size >= 2) { "結合する動画を2本以上選択してください" }
        val destination = Uri.parse(outputUri)
        val workDir = createWorkDir()
        try {
            val signatures = inputs.mapIndexed { index, source ->
                onProgress("SMB入力を確認しています ${index + 1}/${inputs.size}")
                openReadDescriptor(source).use { descriptor ->
                    NamedMediaSignature(
                        displayName = source.displayName,
                        signature = mediaEngine.probeDescriptor(descriptor.fd, source.displayName),
                    )
                }
            }
            onProgress("互換性を確認しています")
            mediaEngine.requireLosslessConcatCompatibility(signatures)

            val inputDescriptors = mutableListOf<ParcelFileDescriptor>()
            var outputDescriptor: ParcelFileDescriptor? = null
            try {
                inputs.forEach { source -> inputDescriptors += openReadDescriptor(source) }
                outputDescriptor = openReadWriteDescriptor(destination)
                val descriptorInputs = inputs.indices.map { index ->
                    NamedMediaDescriptor(
                        fd = inputDescriptors[index].fd,
                        displayName = inputs[index].displayName,
                    )
                }
                onProgress("無劣化で結合しながらSMBへ保存中")
                mediaEngine.concatLosslessDescriptorsValidated(
                    inputs = descriptorInputs,
                    outputFd = outputDescriptor.fd,
                    outputName = outputName,
                    workingDirectory = workDir,
                )
            } finally {
                runCatching { outputDescriptor?.close() }
                inputDescriptors.asReversed().forEach { descriptor ->
                    runCatching { descriptor.close() }
                }
            }
        } catch (error: Throwable) {
            abortRemoteOutput(destination)
            throw error
        } finally {
            workDir.deleteRecursively()
        }

        try {
            onProgress("SMB保存を確定しています")
            commitRemoteOutput(destination)
            onProgress("SMBへ直接保存しました")
        } catch (error: Throwable) {
            abortRemoteOutput(destination)
            throw error
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

    /** Legacy local-output fallback. */
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

    /** Writes the already-prepared local trim source straight into XFiles' SMB output descriptor. */
    suspend fun cutPreparedDirect(
        localInputPath: String,
        outputUri: String,
        outputName: String,
        startMs: Long,
        endMs: Long,
        onProgress: (String) -> Unit = {},
    ) = withContext(Dispatchers.IO) {
        val input = validatedPreparedFile(localInputPath)
        val destination = Uri.parse(outputUri)
        try {
            onProgress("SMB保存先を開いています")
            openReadWriteDescriptor(destination).use { outputDescriptor ->
                onProgress("無劣化でカットしながらSMBへ保存中")
                mediaEngine.cutLosslessToDescriptor(
                    inputPath = input.absolutePath,
                    outputFd = outputDescriptor.fd,
                    outputName = outputName,
                    startMs = startMs,
                    endMs = endMs,
                )
            }
        } catch (error: Throwable) {
            abortRemoteOutput(destination)
            throw error
        }

        try {
            onProgress("SMB保存を確定しています")
            commitRemoteOutput(destination)
            discardPrepared(localInputPath)
            onProgress("SMBへ直接保存しました")
        } catch (error: Throwable) {
            abortRemoteOutput(destination)
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

    private fun commitRemoteOutput(uri: Uri) {
        val values = ContentValues().apply { put(REMOTE_COMMIT_KEY, true) }
        val updated = contentResolver.update(uri, values, null, null)
        if (updated != 1) throw IOException("SMB出力を確定できませんでした")
    }

    private fun abortRemoteOutput(uri: Uri) {
        runCatching { contentResolver.delete(uri, null, null) }
    }

    private fun openReadDescriptor(source: PickedVideo): ParcelFileDescriptor {
        val uri = Uri.parse(source.uri)
        return try {
            val descriptor = contentResolver.openFileDescriptor(uri, "r")
                ?: throw IOException("ファイル記述子を取得できません")
            validateSeekableDescriptor(descriptor, "SMB入力 ${source.displayName}")
        } catch (error: Throwable) {
            val detail = error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName
            throw IOException("SMB入力を開けません: ${source.displayName} ($detail)", error)
        }
    }

    private fun openReadWriteDescriptor(uri: Uri): ParcelFileDescriptor = try {
        val descriptor = contentResolver.openFileDescriptor(uri, "rw")
            ?: throw IOException("SMB出力のファイル記述子を取得できません")
        validateSeekableDescriptor(descriptor, "SMB保存先")
    } catch (error: Throwable) {
        val detail = error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName
        throw IOException("SMB保存先を開けません: $detail", error)
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

    private fun copyToLocal(source: PickedVideo, target: File) {
        target.parentFile?.mkdirs()
        val uri = Uri.parse(source.uri)
        try {
            val input = contentResolver.openInputStream(uri)
                ?: throw IllegalStateException("ファイルを開けません")
            input.use { from ->
                target.outputStream().buffered().use { to -> from.copyTo(to) }
            }
        } catch (error: Throwable) {
            target.delete()
            val detail = error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName
            throw IOException("読み込みに失敗: ${source.displayName} ($detail)", error)
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
        cleanupOldChildren(outgoingRoot)
    }

    private fun cleanupOldPrepared() {
        cleanupOldChildren(editSessionRoot)
        cleanupOldChildren(workRoot)
    }

    private fun cleanupOldChildren(root: File) {
        runCatching {
            if (!root.isDirectory) return@runCatching
            val cutoff = System.currentTimeMillis() - RETENTION_MS
            root.listFiles().orEmpty().forEach { session ->
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
        const val REMOTE_COMMIT_KEY = "commit"
        const val RETENTION_MS = 24L * 60L * 60L * 1000L
    }
}
