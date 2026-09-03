package app.clipforge

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.clipforge.media.FfmpegMediaEngine
import app.clipforge.media.TrimRangeSnapper
import app.clipforge.workflow.ExternalEditPipeline
import app.clipforge.workflow.PickedVideo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun suggestedConcatName(firstFileName: String): String {
    val extension = firstFileName.substringAfterLast('.', "")
        .lowercase()
        .takeIf { it == "mp4" || it == "mkv" }
        ?: "mkv"
    val base = firstFileName.substringBeforeLast('.', firstFileName).ifBlank { "video" }
    return "${base}_concat.$extension"
}

private fun suggestedCutName(fileName: String): String {
    val extension = fileName.substringAfterLast('.', "")
        .lowercase()
        .takeIf { it == "mp4" || it == "mkv" }
        ?: "mkv"
    val base = fileName.substringBeforeLast('.', fileName).ifBlank { "video" }
    return "$base-cut.$extension"
}

data class TrimEditorState(
    val sourceUri: String,
    val sourceName: String,
    val localPath: String,
    val durationMs: Long,
    val startMs: Long,
    val endMs: Long,
    val keyframesMs: List<Long>,
    val thumbnailPaths: List<String>,
)

data class PendingOutput(
    val localPath: String,
    val fileName: String,
    val mimeType: String,
)

data class MainUiState(
    val busy: Boolean = false,
    val selectedVideos: List<PickedVideo> = emptyList(),
    val trimEditor: TrimEditorState? = null,
    val pendingOutput: PendingOutput? = null,
    val status: String = "動画を選択してください",
    val error: String? = null,
) {
    val suggestedConcatOutputName: String
        get() = selectedVideos.firstOrNull()?.let { suggestedConcatName(it.displayName) }.orEmpty()
}

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val pipeline = ExternalEditPipeline(
        cacheRoot = application.cacheDir,
        contentResolver = application.contentResolver,
        mediaEngine = FfmpegMediaEngine(),
    )
    private val _uiState = MutableStateFlow(MainUiState())
    val uiState = _uiState.asStateFlow()

    fun acceptPickedUris(uriStrings: List<String>) {
        if (_uiState.value.busy) return
        val unique = uriStrings.filter { it.isNotBlank() }.distinct()
        if (unique.isEmpty()) return
        viewModelScope.launch {
            _uiState.update { it.copy(busy = true, status = "選択した動画を確認中", error = null) }
            try {
                val videos = withContext(Dispatchers.IO) {
                    unique.mapNotNull(::describeVideo)
                }
                if (videos.isEmpty()) {
                    throw IllegalArgumentException("MP4 / MKV の動画を選択してください")
                }
                _uiState.update {
                    it.copy(
                        selectedVideos = videos,
                        trimEditor = null,
                        pendingOutput = null,
                        status = "${videos.size}本の動画を選択しました",
                        error = if (videos.size != unique.size) "MP4 / MKV 以外のファイルは除外しました" else null,
                    )
                }
            } catch (error: Throwable) {
                _uiState.update {
                    it.copy(error = error.message ?: error.javaClass.simpleName, status = "選択に失敗しました")
                }
            } finally {
                _uiState.update { it.copy(busy = false) }
            }
        }
    }

    fun moveSelected(uri: String, offset: Int) {
        if (_uiState.value.busy || offset == 0) return
        _uiState.update { state ->
            val next = state.selectedVideos.toMutableList()
            val from = next.indexOfFirst { it.uri == uri }
            if (from < 0) return@update state
            val to = (from + offset).coerceIn(0, next.lastIndex)
            if (to == from) return@update state
            val item = next.removeAt(from)
            next.add(to, item)
            state.copy(selectedVideos = next, error = null)
        }
    }

    fun removeSelected(uri: String) {
        if (_uiState.value.busy) return
        _uiState.update { state ->
            val next = state.selectedVideos.filterNot { it.uri == uri }
            state.copy(
                selectedVideos = next,
                status = if (next.isEmpty()) "動画を選択してください" else "${next.size}本の動画を選択しました",
                error = null,
            )
        }
    }

    fun concatSelected(outputName: String) {
        val selected = _uiState.value.selectedVideos
        if (selected.size < 2) {
            showError("結合するMP4/MKVを2本以上選択してください")
            return
        }
        val name = validatedOutputName(outputName, suggestedConcatName(selected.first().displayName)) ?: return
        runTask("結合を開始") {
            val output = pipeline.concat(selected, name) { message ->
                _uiState.update { it.copy(status = message) }
            }
            _uiState.update {
                it.copy(
                    pendingOutput = PendingOutput(
                        localPath = output.absolutePath,
                        fileName = output.name,
                        mimeType = mimeFor(output.name),
                    ),
                    status = "XFilesで保存先を選択します",
                )
            }
        }
    }

    fun openTrimEditor() {
        val selected = _uiState.value.selectedVideos
        if (selected.size != 1) {
            showError("カットする動画を1本だけ選択してください")
            return
        }
        val source = selected.single()
        runTask("編集画面を準備中") {
            val prepared = pipeline.prepareCut(source) { message ->
                _uiState.update { it.copy(status = message) }
            }
            _uiState.update {
                it.copy(
                    trimEditor = TrimEditorState(
                        sourceUri = prepared.source.uri,
                        sourceName = prepared.source.displayName,
                        localPath = prepared.localFile.absolutePath,
                        durationMs = prepared.durationMs,
                        startMs = 0L,
                        endMs = prepared.durationMs,
                        keyframesMs = prepared.keyframesMs,
                        thumbnailPaths = prepared.thumbnailPaths,
                    ),
                    status = "範囲を選択してください",
                )
            }
        }
    }

    fun updateTrimRange(requestedStartMs: Long, requestedEndMs: Long): TrimEditorState? {
        val editor = _uiState.value.trimEditor ?: return null
        val snapped = TrimRangeSnapper.snap(
            durationMs = editor.durationMs,
            keyframesMs = editor.keyframesMs,
            requestedStartMs = requestedStartMs,
            requestedEndMs = requestedEndMs,
        )
        val updated = editor.copy(startMs = snapped.startMs, endMs = snapped.endMs)
        _uiState.update { it.copy(trimEditor = updated, error = null) }
        return updated
    }

    fun applyTrim(outputName: String) {
        val editor = _uiState.value.trimEditor ?: return
        val name = validatedOutputName(outputName, suggestedCutName(editor.sourceName)) ?: return
        runTask("カットを開始") {
            val output = pipeline.cutPrepared(
                localInputPath = editor.localPath,
                outputName = name,
                startMs = editor.startMs,
                endMs = editor.endMs,
            ) { message -> _uiState.update { it.copy(status = message) } }
            pipeline.discardPrepared(editor.localPath)
            _uiState.update {
                it.copy(
                    trimEditor = null,
                    pendingOutput = PendingOutput(
                        localPath = output.absolutePath,
                        fileName = output.name,
                        mimeType = mimeFor(output.name),
                    ),
                    status = "XFilesで保存先を選択します",
                )
            }
        }
    }

    fun cancelTrimEditor() {
        if (_uiState.value.busy) return
        val editor = _uiState.value.trimEditor ?: return
        _uiState.update { it.copy(trimEditor = null, status = "編集をキャンセルしました", error = null) }
        viewModelScope.launch(Dispatchers.IO) { pipeline.discardPrepared(editor.localPath) }
    }

    fun outputHandedOff() {
        _uiState.update { it.copy(pendingOutput = null, status = "XFilesで保存先を選択してください", error = null) }
    }

    fun outputHandoffFailed(message: String) {
        _uiState.update { it.copy(pendingOutput = null, status = "出力の受け渡しに失敗しました", error = message) }
    }

    private fun describeVideo(uriString: String): PickedVideo? {
        val uri = Uri.parse(uriString)
        var displayName: String? = null
        var sizeBytes: Long? = null
        getApplication<Application>().contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0 && !cursor.isNull(nameIndex)) displayName = cursor.getString(nameIndex)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                    sizeBytes = cursor.getLong(sizeIndex).takeIf { it >= 0L }
                }
            }
        }
        val name = displayName?.takeIf { it.isNotBlank() }
            ?: uri.lastPathSegment?.substringAfterLast('/')
            ?: "video.mkv"
        val extension = name.substringAfterLast('.', "").lowercase()
        if (extension != "mp4" && extension != "mkv") return null
        return PickedVideo(uri = uriString, displayName = name, sizeBytes = sizeBytes)
    }

    private fun validatedOutputName(requestedName: String, fallbackName: String): String? {
        val name = requestedName.trim().ifBlank { fallbackName }
        val invalidCharacters = charArrayOf('/', '\\', ':', '*', '?', '"', '<', '>', '|')
        if (
            name == "." ||
            name == ".." ||
            name.endsWith('.') ||
            name.endsWith(' ') ||
            name.any { it.code < 32 || it in invalidCharacters }
        ) {
            showError("出力ファイル名に使用できない文字が含まれています")
            return null
        }

        val extension = name.substringAfterLast('.', "").lowercase()
        if (extension != "mp4" && extension != "mkv") {
            showError("出力形式は .mp4 または .mkv を指定してください")
            return null
        }
        return name
    }

    private fun mimeFor(name: String): String =
        if (name.substringAfterLast('.', "").equals("mp4", ignoreCase = true)) "video/mp4" else "video/x-matroska"

    private fun showError(message: String) {
        _uiState.update { it.copy(error = message) }
    }

    private fun runTask(initialStatus: String, block: suspend () -> Unit) {
        if (_uiState.value.busy) return
        viewModelScope.launch {
            _uiState.update { it.copy(busy = true, status = initialStatus, error = null) }
            try {
                block()
            } catch (error: Throwable) {
                _uiState.update { it.copy(error = error.message ?: error.javaClass.simpleName, status = "失敗") }
            } finally {
                _uiState.update { it.copy(busy = false) }
            }
        }
    }

    override fun onCleared() {
        pipeline.cleanupPreparedSessions()
        super.onCleared()
    }
}
