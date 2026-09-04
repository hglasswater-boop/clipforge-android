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
import app.clipforge.media.SyncFrameResolver
import app.clipforge.media.TimelineThumbnailGenerator
import kotlinx.coroutines.CancellationException
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
    val sessionDir: File,
    val localFile: File?,
    val durationMs: Long,
    val thumbnailPaths: List<String>,
)

/**
 * External-file pipeline. Seekable content providers are consumed directly through file
 * descriptors. Local staging is retained only as a compatibility fallback for providers that
 * cannot expose a seekable descriptor.
 */
class ExternalEditPipeline(
    private val cacheRoot: File,
    context: Context,
    private val mediaEngine: FfmpegMediaEngine,
    private val thumbnailGenerator: TimelineThumbnailGenerator = TimelineThumbnailGenerator(),
    private val syncFrameResolver: SyncFrameResolver = SyncFrameResolver(),
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

    /** Direct seekable-descriptor concat. No source is copied to local storage. */
    suspend fun concatDirect(
        inputs: List<PickedVideo>,
        outputUri: String,
        outputName: String,
        onProgress: (String) -> Unit = {},
    ) = withContext(Dispatchers.IO) {
        require(inputs.size >= 2) { "結合する動画を2本以上選択してください" }
        val destination = Uri.parse(outputUri)
        val remoteDestination = isXFilesRemoteOutput(destination)
        val destinationLabel = if (remoteDestination) "SMB" else "端末"
        val workDir = createWorkDir()
        try {
            val signatures = inputs.mapIndexed { index, source ->
                onProgress("入力を確認しています ${index + 1}/${inputs.size}")
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
                onProgress("無劣化で結合しながら${destinationLabel}へ保存中")
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
            abortOutput(destination)
            throw error
        } finally {
            workDir.deleteRecursively()
        }

        try {
            if (remoteDestination) {
                onProgress("SMB保存を確定しています")
                commitRemoteOutput(destination)
                onProgress("SMBへ直接保存しました")
            } else {
                onProgress("端末へ保存しました")
            }
        } catch (error: Throwable) {
            abortOutput(destination)
            throw error
        }
    }

    /**
     * Prepares the minimum state required to open the trim editor. Seekable providers, including
     * XFiles SMB, only need a metadata probe here. Timeline thumbnails are intentionally deferred
     * until after the editor is visible, so remote thumbnail seeks never block entry to the editor.
     */
    suspend fun prepareCut(
        source: PickedVideo,
        onProgress: (message: String, progressPercent: Int?) -> Unit = { _, _ -> },
    ): PreparedExternalCutSession = withContext(Dispatchers.IO) {
        val sessionDir = createEditWorkDir()
        try {
            val direct = runCatching {
                onProgress("動画を直接開いています", 10)
                openReadDescriptor(source).use { descriptor ->
                    onProgress("動画情報を解析中", 45)
                    val signature = mediaEngine.probeDescriptor(descriptor.fd, source.displayName)
                    val durationMs = signature.durationMs
                        ?: throw IllegalStateException("動画の長さを取得できませんでした")
                    PreparedExternalCutSession(
                        source = source,
                        sessionDir = sessionDir,
                        localFile = null,
                        durationMs = durationMs,
                        thumbnailPaths = emptyList(),
                    )
                }
            }
            direct.exceptionOrNull()?.let { error ->
                if (error is CancellationException) throw error
            }
            if (direct.isSuccess) {
                onProgress("編集画面を開いています", 100)
                return@withContext direct.getOrThrow()
            }

            ensureStagingCapacity(source)
            val input = File(sessionDir, safeLocalName(source.displayName))
            copyToLocalWithProgress(source, input) { copiedBytes, totalBytes, bytesPerSecond ->
                val percent = totalBytes?.takeIf { it > 0L }
                    ?.let { total -> ((copiedBytes * 88L) / total).toInt().coerceIn(0, 88) }
                val copied = formatBytes(copiedBytes)
                val total = totalBytes?.takeIf { it > 0L }?.let(::formatBytes)
                val speed = formatSpeed(bytesPerSecond)
                val message = buildString {
                    append("編集用に取得中 ")
                    append(copied)
                    if (total != null) append(" / $total")
                    if (speed != null) append(" · $speed")
                }
                onProgress(message, percent)
            }

            onProgress("動画情報を解析中", 94)
            val signature = mediaEngine.probe(input)
            val durationMs = signature.durationMs
                ?: throw IllegalStateException("動画の長さを取得できませんでした")
            onProgress("編集画面を開いています", 100)
            PreparedExternalCutSession(
                source = source,
                sessionDir = sessionDir,
                localFile = input,
                durationMs = durationMs,
                thumbnailPaths = emptyList(),
            )
        } catch (error: Throwable) {
            sessionDir.deleteRecursively()
            throw error
        }
    }

    /** Generates timeline images after the editor has already opened. */
    suspend fun generateCutThumbnails(
        source: PickedVideo,
        sessionPath: String,
        localInputPath: String?,
        durationMs: Long,
        count: Int = 8,
    ): List<String> = withContext(Dispatchers.IO) {
        val sessionDir = validatedSessionDir(sessionPath)
        val timelineDir = File(sessionDir, "timeline")
        timelineDir.deleteRecursively()
        if (localInputPath != null) {
            thumbnailGenerator.generate(
                source = validatedPreparedFile(localInputPath),
                outputDir = timelineDir,
                durationMs = durationMs,
                count = count,
            ).map { it.absolutePath }
        } else {
            openReadDescriptor(source).use { descriptor ->
                thumbnailGenerator.generate(
                    sourceFd = descriptor.fileDescriptor,
                    outputDir = timelineDir,
                    durationMs = durationMs,
                    count = count,
                ).map { it.absolutePath }
            }
        }
    }

    suspend fun snapCutRange(
        source: PickedVideo,
        localInputPath: String?,
        durationMs: Long,
        requestedStartMs: Long,
        requestedEndMs: Long,
    ): LongRange = withContext(Dispatchers.IO) {
        if (localInputPath != null) {
            syncFrameResolver.snapRange(
                source = validatedPreparedFile(localInputPath),
                durationMs = durationMs,
                requestedStartMs = requestedStartMs,
                requestedEndMs = requestedEndMs,
            )
        } else {
            openReadDescriptor(source).use { descriptor ->
                syncFrameResolver.snapRange(
                    sourceFd = descriptor.fileDescriptor,
                    durationMs = durationMs,
                    requestedStartMs = requestedStartMs,
                    requestedEndMs = requestedEndMs,
                )
            }
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

    suspend fun cutPreparedDirect(
        localInputPath: String,
        sessionPath: String,
        outputUri: String,
        outputName: String,
        startMs: Long,
        endMs: Long,
        onProgress: (String) -> Unit = {},
    ) = withContext(Dispatchers.IO) {
        val input = validatedPreparedFile(localInputPath)
        val destination = Uri.parse(outputUri)
        val remoteDestination = isXFilesRemoteOutput(destination)
        val destinationLabel = if (remoteDestination) "SMB" else "端末"
        try {
            onProgress("${destinationLabel}保存先を開いています")
            openReadWriteDescriptor(destination).use { outputDescriptor ->
                onProgress("無劣化でカットしながら${destinationLabel}へ保存中")
                mediaEngine.cutLosslessToDescriptor(
                    inputPath = input.absolutePath,
                    outputFd = outputDescriptor.fd,
                    outputName = outputName,
                    startMs = startMs,
                    endMs = endMs,
                )
            }
            finishOutput(destination, remoteDestination, onProgress)
            discardPreparedSession(sessionPath)
        } catch (error: Throwable) {
            abortOutput(destination)
            throw error
        }
    }

    suspend fun cutSourceDirect(
        source: PickedVideo,
        sessionPath: String,
        outputUri: String,
        outputName: String,
        startMs: Long,
        endMs: Long,
        onProgress: (String) -> Unit = {},
    ) = withContext(Dispatchers.IO) {
        val destination = Uri.parse(outputUri)
        val remoteDestination = isXFilesRemoteOutput(destination)
        val destinationLabel = if (remoteDestination) "SMB" else "端末"
        var inputDescriptor: ParcelFileDescriptor? = null
        var outputDescriptor: ParcelFileDescriptor? = null
        try {
            onProgress("入力と${destinationLabel}保存先を開いています")
            inputDescriptor = openReadDescriptor(source)
            outputDescriptor = openReadWriteDescriptor(destination)
            onProgress("無劣化でカットしながら${destinationLabel}へ保存中")
            mediaEngine.cutLosslessDescriptors(
                inputFd = inputDescriptor.fd,
                outputFd = outputDescriptor.fd,
                outputName = outputName,
                startMs = startMs,
                endMs = endMs,
            )
            runCatching { outputDescriptor.close() }
            outputDescriptor = null
            runCatching { inputDescriptor.close() }
            inputDescriptor = null
            finishOutput(destination, remoteDestination, onProgress)
            discardPreparedSession(sessionPath)
        } catch (error: Throwable) {
            abortOutput(destination)
            throw error
        } finally {
            runCatching { outputDescriptor?.close() }
            runCatching { inputDescriptor?.close() }
        }
    }

    fun discardPreparedSession(sessionPath: String) {
        runCatching { validatedSessionDir(sessionPath).deleteRecursively() }
    }

    fun cleanupPreparedSessions() {
        runCatching { editSessionRoot.deleteRecursively() }
        runCatching { workRoot.deleteRecursively() }
    }

    private fun finishOutput(
        destination: Uri,
        remoteDestination: Boolean,
        onProgress: (String) -> Unit,
    ) {
        if (remoteDestination) {
            onProgress("SMB保存を確定しています")
            commitRemoteOutput(destination)
            onProgress("SMBへ直接保存しました")
        } else {
            onProgress("端末へ保存しました")
        }
    }

    private fun commitRemoteOutput(uri: Uri) {
        check(isXFilesRemoteOutput(uri)) { "XFiles以外の保存先にSMB確定処理は実行できません" }
        val values = ContentValues().apply { put(REMOTE_COMMIT_KEY, true) }
        val updated = contentResolver.update(uri, values, null, null)
        if (updated != 1) throw IOException("SMB出力を確定できませんでした")
    }

    private fun abortOutput(uri: Uri) {
        runCatching { contentResolver.delete(uri, null, null) }
    }

    private fun isXFilesRemoteOutput(uri: Uri): Boolean =
        uri.authority == XFILES_REMOTE_PROVIDER_AUTHORITY && uri.getQueryParameter("mode") == "output"

    private fun openReadDescriptor(source: PickedVideo): ParcelFileDescriptor {
        val uri = Uri.parse(source.uri)
        return try {
            val descriptor = contentResolver.openFileDescriptor(uri, "r")
                ?: throw IOException("ファイル記述子を取得できません")
            validateSeekableDescriptor(descriptor, "入力 ${source.displayName}")
        } catch (error: Throwable) {
            val detail = error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName
            throw IOException("入力を直接開けません: ${source.displayName} ($detail)", error)
        }
    }

    private fun openReadWriteDescriptor(uri: Uri): ParcelFileDescriptor = try {
        val descriptor = contentResolver.openFileDescriptor(uri, "rw")
            ?: throw IOException("保存先のファイル記述子を取得できません")
        validateSeekableDescriptor(descriptor, "保存先")
    } catch (error: Throwable) {
        val detail = error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName
        throw IOException("保存先を開けません: $detail", error)
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
        copyToLocalWithProgress(source, target) { _, _, _ -> }
    }

    private fun copyToLocalWithProgress(
        source: PickedVideo,
        target: File,
        onProgress: (copiedBytes: Long, totalBytes: Long?, bytesPerSecond: Double?) -> Unit,
    ) {
        target.parentFile?.mkdirs()
        val uri = Uri.parse(source.uri)
        try {
            val input = contentResolver.openInputStream(uri)
                ?: throw IllegalStateException("ファイルを開けません")
            val totalBytes = source.sizeBytes?.takeIf { it > 0L }
            input.use { from ->
                target.outputStream().buffered(BUFFER_SIZE).use { to ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    val startedNs = System.nanoTime()
                    var lastReportNs = startedNs
                    var copied = 0L
                    onProgress(0L, totalBytes, null)
                    while (true) {
                        val read = from.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        to.write(buffer, 0, read)
                        copied += read
                        val now = System.nanoTime()
                        if (now - lastReportNs >= PROGRESS_INTERVAL_NS) {
                            val elapsedSeconds = (now - startedNs) / 1_000_000_000.0
                            val speed = copied / elapsedSeconds.coerceAtLeast(0.001)
                            onProgress(copied, totalBytes, speed)
                            lastReportNs = now
                        }
                    }
                    val elapsedSeconds = (System.nanoTime() - startedNs) / 1_000_000_000.0
                    val speed = copied / elapsedSeconds.coerceAtLeast(0.001)
                    onProgress(copied, totalBytes, speed)
                }
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

    private fun validatedSessionDir(sessionPath: String): File {
        val root = editSessionRoot.canonicalFile
        val session = File(sessionPath).canonicalFile
        val insideRoot = session.path.startsWith(root.path + File.separator)
        require(insideRoot && session.isDirectory) { "編集セッションが見つかりません" }
        return session
    }

    private fun ensureStagingCapacity(source: PickedVideo) {
        val inputBytes = source.sizeBytes?.takeIf { it >= 0L } ?: return
        val requiredBytes = saturatingAdd(inputBytes, STAGING_RESERVE_BYTES)
        val availableBytes = cacheRoot.usableSpace
        if (availableBytes < requiredBytes) {
            throw IllegalStateException(
                "端末の空き容量が不足しています。必要 約${formatGiB(requiredBytes)}GB / 空き 約${formatGiB(availableBytes)}GB",
            )
        }
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

        val requiredBytes = if (inputBytes > (Long.MAX_VALUE - STAGING_RESERVE_BYTES) / 2L) {
            Long.MAX_VALUE
        } else {
            inputBytes * 2L + STAGING_RESERVE_BYTES
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

    private fun formatBytes(bytes: Long): String {
        val gib = bytes / (1024.0 * 1024.0 * 1024.0)
        return if (gib >= 1.0) {
            String.format(Locale.US, "%.2f GB", gib)
        } else {
            String.format(Locale.US, "%.0f MB", bytes / (1024.0 * 1024.0))
        }
    }

    private fun formatSpeed(bytesPerSecond: Double?): String? {
        val speed = bytesPerSecond?.takeIf { it.isFinite() && it > 0.0 } ?: return null
        return String.format(Locale.US, "%.1f MB/s", speed / (1024.0 * 1024.0))
    }

    private companion object {
        const val XFILES_REMOTE_PROVIDER_AUTHORITY = "app.local1st.files.remotefileprovider"
        const val REMOTE_COMMIT_KEY = "commit"
        const val RETENTION_MS = 24L * 60L * 60L * 1000L
        const val BUFFER_SIZE = 1024 * 1024
        const val PROGRESS_INTERVAL_NS = 250_000_000L
        const val STAGING_RESERVE_BYTES = 256L * 1024L * 1024L
    }
}
