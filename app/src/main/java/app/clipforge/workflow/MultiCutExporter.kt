package app.clipforge.workflow

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import app.clipforge.media.CutMode
import app.clipforge.media.FfmpegMediaEngine
import app.clipforge.media.MediaSegment
import app.clipforge.media.SmartConcatInput
import app.clipforge.media.SmartCutPart
import app.clipforge.media.SyncFrameResolver
import app.clipforge.media.planSmartCutSegment
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
    private val syncFrameResolver: SyncFrameResolver = SyncFrameResolver(),
) {
    private val contentResolver = context.applicationContext.contentResolver

    suspend fun export(
        source: PickedVideo,
        localInputPath: String?,
        sessionPath: String,
        durationMs: Long,
        cutRanges: List<MediaSegment>,
        cutMode: CutMode,
        outputUri: String,
        outputName: String,
        onProgress: (message: String, progressPercent: Int?) -> Unit = { _, _ -> },
    ) = withContext(Dispatchers.IO) {
        onProgress("保存内容を確認しています", 2)
        val keepSegments = remainingSegments(durationMs, cutRanges)
        require(keepSegments.isNotEmpty()) { "動画全体を削除する指定になっています" }

        val destination = Uri.parse(outputUri)
        val remoteDestination = isXFilesRemoteOutput(destination)
        val destinationLabel = if (remoteDestination) "SMB" else "端末"
        val workDir = File(cacheRoot, "clipforge/multi-cut/${UUID.randomUUID()}").apply { mkdirs() }
        var outputDescriptor: ParcelFileDescriptor? = null
        var lastWritePercent = -1

        fun reportLosslessWriteProgress(percent: Int) {
            val safe = percent.coerceIn(0, 100)
            if (safe == lastWritePercent) return
            lastWritePercent = safe
            val overall = (10 + (safe * 84 / 100)).coerceIn(10, 94)
            onProgress(
                "無劣化で書き出し中 $safe%（削除 ${cutRanges.size}箇所）",
                overall,
            )
        }

        fun reportSmartConcatProgress(percent: Int) {
            val safe = percent.coerceIn(0, 100)
            val overall = (58 + (safe * 36 / 100)).coerceIn(58, 94)
            onProgress("スマートカットを書き出し中 $safe%", overall)
        }

        try {
            onProgress("${destinationLabel}保存先を開いています", 5)
            outputDescriptor = openReadWriteDescriptor(destination)

            if (localInputPath != null) {
                val input = File(localInputPath)
                require(input.isFile) { "編集用キャッシュが見つかりません" }
                onProgress("編集データを読み込んでいます", 8)
                if (cutMode == CutMode.SMART) {
                    exportSmartLocal(
                        input = input,
                        source = source,
                        durationMs = durationMs,
                        keepSegments = keepSegments,
                        outputFd = outputDescriptor.fd,
                        outputName = outputName,
                        workDir = workDir,
                        onProgress = onProgress,
                        onConcatProgress = ::reportSmartConcatProgress,
                    )
                } else {
                    exportLosslessLocal(
                        input = input,
                        keepSegments = keepSegments,
                        outputFd = outputDescriptor.fd,
                        outputName = outputName,
                        workDir = workDir,
                        onProgress = ::reportLosslessWriteProgress,
                    )
                }
            } else {
                onProgress("元動画を直接開いています", 8)
                if (cutMode == CutMode.SMART) {
                    exportSmartDescriptor(
                        source = source,
                        durationMs = durationMs,
                        keepSegments = keepSegments,
                        outputFd = outputDescriptor.fd,
                        outputName = outputName,
                        workDir = workDir,
                        onProgress = onProgress,
                        onConcatProgress = ::reportSmartConcatProgress,
                    )
                } else {
                    exportLosslessDescriptor(
                        source = source,
                        keepSegments = keepSegments,
                        outputFd = outputDescriptor.fd,
                        outputName = outputName,
                        workDir = workDir,
                        onProgress = ::reportLosslessWriteProgress,
                    )
                }
            }

            onProgress("書き出しを完了しています", 95)
            runCatching { outputDescriptor.close() }
            outputDescriptor = null

            if (remoteDestination) {
                onProgress("SMB保存を確定しています", 98)
                commitRemoteOutput(destination)
                onProgress("SMBへ保存しました", 100)
            } else {
                onProgress("端末へ保存しました", 100)
            }
            discardSession(sessionPath)
        } catch (error: Throwable) {
            abortOutput(destination)
            throw error
        } finally {
            runCatching { outputDescriptor?.close() }
            workDir.deleteRecursively()
        }
    }

    private suspend fun exportLosslessLocal(
        input: File,
        keepSegments: List<MediaSegment>,
        outputFd: Int,
        outputName: String,
        workDir: File,
        onProgress: (Int) -> Unit,
    ) {
        if (keepSegments.size == 1) {
            val segment = keepSegments.single()
            mediaEngine.cutLosslessToDescriptor(
                inputPath = input.absolutePath,
                outputFd = outputFd,
                outputName = outputName,
                startMs = segment.startMs,
                endMs = segment.endMs,
                onProgressPercent = onProgress,
            )
        } else {
            mediaEngine.concatSegmentsLosslessToDescriptor(
                inputPath = input.absolutePath,
                outputFd = outputFd,
                outputName = outputName,
                segments = keepSegments,
                workingDirectory = workDir,
                onProgressPercent = onProgress,
            )
        }
    }

    private suspend fun exportLosslessDescriptor(
        source: PickedVideo,
        keepSegments: List<MediaSegment>,
        outputFd: Int,
        outputName: String,
        workDir: File,
        onProgress: (Int) -> Unit,
    ) {
        val descriptorCount = if (keepSegments.size == 1) 1 else keepSegments.size
        withReadDescriptors(source, descriptorCount) { descriptors ->
            if (keepSegments.size == 1) {
                val segment = keepSegments.single()
                mediaEngine.cutLosslessDescriptors(
                    inputFd = descriptors.single().fd,
                    outputFd = outputFd,
                    outputName = outputName,
                    startMs = segment.startMs,
                    endMs = segment.endMs,
                    onProgressPercent = onProgress,
                )
            } else {
                mediaEngine.concatSegmentsLosslessDescriptors(
                    inputFds = descriptors.map { it.fd },
                    outputFd = outputFd,
                    outputName = outputName,
                    segments = keepSegments,
                    workingDirectory = workDir,
                    onProgressPercent = onProgress,
                )
            }
        }
    }

    private suspend fun exportSmartLocal(
        input: File,
        source: PickedVideo,
        durationMs: Long,
        keepSegments: List<MediaSegment>,
        outputFd: Int,
        outputName: String,
        workDir: File,
        onProgress: (String, Int?) -> Unit,
        onConcatProgress: (Int) -> Unit,
    ) {
        onProgress("スマートカットの境界を確認しています", 10)
        val signature = mediaEngine.probe(input)
        val planned = planSmartParts(
            durationMs = durationMs,
            keepSegments = keepSegments,
            before = { position -> syncFrameResolver.syncFrameAtOrBefore(input, durationMs, position) },
            after = { position -> syncFrameResolver.syncFrameAtOrAfter(input, durationMs, position) },
        )
        val reencodeCount = planned.count { it is SmartCutPart.Reencode }
        if (reencodeCount == 0) {
            exportLosslessLocal(input, keepSegments, outputFd, outputName, workDir) { percent ->
                val overall = (12 + percent * 82 / 100).coerceIn(12, 94)
                onProgress("キーフレーム一致のため無劣化で書き出し中 $percent%", overall)
            }
            return
        }

        val concatParts = renderSmartParts(
            planned = planned,
            workDir = workDir,
            sourceExtension = sourceExtension(source.displayName),
            render = { segment, file, callback ->
                mediaEngine.renderSmartBoundaryToPath(
                    inputPath = input.absolutePath,
                    output = file,
                    sourceSignature = signature,
                    segment = segment,
                    onProgressPercent = callback,
                )
            },
            onProgress = onProgress,
        )
        mediaEngine.concatSmartPartsToDescriptor(
            inputPath = input.absolutePath,
            outputFd = outputFd,
            outputName = outputName,
            parts = concatParts,
            expectedDurationMs = keepSegments.sumOf(MediaSegment::durationMs),
            workingDirectory = workDir,
            onProgressPercent = onConcatProgress,
        )
    }

    private suspend fun exportSmartDescriptor(
        source: PickedVideo,
        durationMs: Long,
        keepSegments: List<MediaSegment>,
        outputFd: Int,
        outputName: String,
        workDir: File,
        onProgress: (String, Int?) -> Unit,
        onConcatProgress: (Int) -> Unit,
    ) {
        onProgress("スマートカットの境界を確認しています", 10)
        val signature = withReadDescriptors(source, 1) { descriptors ->
            mediaEngine.probeDescriptor(descriptors.single().fd, source.displayName)
        }
        val planned = planSmartParts(
            durationMs = durationMs,
            keepSegments = keepSegments,
            before = { position ->
                openReadDescriptor(source).use { descriptor ->
                    syncFrameResolver.syncFrameAtOrBefore(descriptor.fileDescriptor, durationMs, position)
                }
            },
            after = { position ->
                openReadDescriptor(source).use { descriptor ->
                    syncFrameResolver.syncFrameAtOrAfter(descriptor.fileDescriptor, durationMs, position)
                }
            },
        )
        val reencodeCount = planned.count { it is SmartCutPart.Reencode }
        if (reencodeCount == 0) {
            exportLosslessDescriptor(source, keepSegments, outputFd, outputName, workDir) { percent ->
                val overall = (12 + percent * 82 / 100).coerceIn(12, 94)
                onProgress("キーフレーム一致のため無劣化で書き出し中 $percent%", overall)
            }
            return
        }

        val concatParts = renderSmartParts(
            planned = planned,
            workDir = workDir,
            sourceExtension = sourceExtension(source.displayName),
            render = { segment, file, callback ->
                withReadDescriptors(source, 1) { descriptors ->
                    mediaEngine.renderSmartBoundaryDescriptor(
                        inputFd = descriptors.single().fd,
                        output = file,
                        sourceSignature = signature,
                        segment = segment,
                        onProgressPercent = callback,
                    )
                }
            },
            onProgress = onProgress,
        )
        val sourcePartCount = concatParts.count { it is SmartConcatInput.SourceSegment }
        withReadDescriptors(source, sourcePartCount) { descriptors ->
            mediaEngine.concatSmartPartsDescriptors(
                inputFds = descriptors.map { it.fd },
                outputFd = outputFd,
                outputName = outputName,
                parts = concatParts,
                expectedDurationMs = keepSegments.sumOf(MediaSegment::durationMs),
                workingDirectory = workDir,
                onProgressPercent = onConcatProgress,
            )
        }
    }

    private fun planSmartParts(
        durationMs: Long,
        keepSegments: List<MediaSegment>,
        before: (Long) -> Long?,
        after: (Long) -> Long?,
    ): List<SmartCutPart> = buildList {
        keepSegments.forEach { segment ->
            val startBefore = if (segment.startMs == 0L) null else before(segment.startMs)
            val startAfter = if (segment.startMs == 0L) null else after(segment.startMs)
            val endBefore = if (segment.endMs == durationMs) null else before(segment.endMs)
            val endAfter = if (segment.endMs == durationMs) null else after(segment.endMs)
            addAll(
                planSmartCutSegment(
                    segment = segment,
                    durationMs = durationMs,
                    startPreviousSyncMs = startBefore,
                    startNextSyncMs = startAfter,
                    endPreviousSyncMs = endBefore,
                    endNextSyncMs = endAfter,
                ),
            )
        }
    }

    private suspend fun renderSmartParts(
        planned: List<SmartCutPart>,
        workDir: File,
        sourceExtension: String,
        render: suspend (MediaSegment, File, (Int) -> Unit) -> Unit,
        onProgress: (String, Int?) -> Unit,
    ): List<SmartConcatInput> {
        val totalReencode = planned.count { it is SmartCutPart.Reencode }.coerceAtLeast(1)
        var reencodeIndex = 0
        val result = mutableListOf<SmartConcatInput>()
        for (part in planned) {
            when (part) {
                is SmartCutPart.Copy -> result += SmartConcatInput.SourceSegment(part.segment)
                is SmartCutPart.Reencode -> {
                    reencodeIndex += 1
                    val currentIndex = reencodeIndex
                    val file = File(
                        workDir,
                        "smart-boundary-%03d.%s".format(currentIndex, sourceExtension),
                    )
                    render(part.segment, file) { percent ->
                        val completedBefore = currentIndex - 1
                        val fraction = (completedBefore + percent / 100.0) / totalReencode.toDouble()
                        val overall = (12 + (fraction * 44.0).toInt()).coerceIn(12, 56)
                        onProgress(
                            "カット境界を高精度処理中 $currentIndex/$totalReencode  $percent%",
                            overall,
                        )
                    }
                    result += SmartConcatInput.RenderedFile(file.absolutePath)
                }
            }
        }
        return result
    }

    private suspend fun <T> withReadDescriptors(
        source: PickedVideo,
        count: Int,
        block: suspend (List<ParcelFileDescriptor>) -> T,
    ): T {
        require(count >= 0) { "Descriptor count must not be negative" }
        val descriptors = mutableListOf<ParcelFileDescriptor>()
        try {
            repeat(count) { descriptors += openReadDescriptor(source) }
            return block(descriptors)
        } finally {
            descriptors.asReversed().forEach { descriptor ->
                runCatching { descriptor.close() }
            }
        }
    }

    private fun sourceExtension(displayName: String): String =
        displayName.substringAfterLast('.', "").lowercase().takeIf { it == "mp4" || it == "mkv" } ?: "mkv"

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
