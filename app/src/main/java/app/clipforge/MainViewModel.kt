package app.clipforge

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.clipforge.media.FfmpegMediaEngine
import app.clipforge.processing.ClipForgeProcessingService
import app.clipforge.processing.ProcessingState
import app.clipforge.processing.ProcessingStateStore
import app.clipforge.workflow.ExternalEditPipeline
import app.clipforge.workflow.PickedVideo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
    val sessionPath: String,
    val localPath: String?,
    val durationMs: Long,
    val startMs: Long,
    val endMs: Long,
    val thumbnailPaths: List<String>,
)

data class PendingOutput(
    val localPath: String,
    val fileName: String,
    val mimeType: String,
)

enum class PendingDestinationKind { CONCAT, CUT }

data class PendingDestinationRequest(
    val token: Long,
    val kind: PendingDestinationKind,
    val outputName: String,
    val mimeType: String,
)

data class MainUiState(
    val busy: Boolean = false,
    val progressPercent: Int? = null,
    val selectedVideos: List<PickedVideo> = emptyList(),
    val trimEditor: TrimEditorState? = null,
    val pendingOutput: PendingOutput? = null,
    val pendingDestination: PendingDestinationRequest? = null,
    val status: String = "動画を選択してください",
    val error: String? = null,
) {
    val suggestedConcatOutputName: String
        get() = selectedVideos.firstOrNull()?.let { suggestedConcatName(it.displayName) }.orEmpty()
}

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val pipeline = ExternalEditPipeline(
        cacheRoot = application.cacheDir,
        context = application,
        mediaEngine = FfmpegMediaEngine(),
    )
    private val _uiState = MutableStateFlow(MainUiState())
    val uiState = _uiState.asStateFlow()
    private var thumbnailJob: Job? = null
    private var thumbnailJobSessionPath: String? = null

    init {
        viewModelScope.launch {
            ProcessingStateStore.state.collect { processing ->
                when (processing) {
                    ProcessingState.Idle -> Unit
                    is ProcessingState.Running -> _uiState.update {
                        it.copy(
                            busy = true,
                            progressPercent = processing.progressPercent,
                            status = processing.message,
                            error = null,
                        )
                    }
                    is ProcessingState.CutPrepared -> _uiState.update { state ->
                        val preparedSource = PickedVideo(
                            uri = processing.sourceUri,
                            displayName = processing.sourceName,
                            sizeBytes = state.selectedVideos
                                .firstOrNull { it.uri == processing.sourceUri }
                                ?.sizeBytes,
                        )
                        state.copy(
                            busy = false,
                            progressPercent = null,
                            selectedVideos = state.selectedVideos.ifEmpty { listOf(preparedSource) },
                            trimEditor = TrimEditorState(
                                sourceUri = processing.sourceUri,
                                sourceName = processing.sourceName,
                                sessionPath = processing.sessionPath,
                                localPath = processing.localPath,
                                durationMs = processing.durationMs,
                                startMs = 0L,
                                endMs = processing.durationMs,
                                thumbnailPaths = processing.thumbnailPaths,
                            ),
                            pendingDestination = null,
                            status = "範囲を選択してください",
                            error = null,
                        )
                    }
                    is ProcessingState.Success -> _uiState.update {
                        it.copy(
                            busy = false,
                            progressPercent = null,
                            status = processing.message,
                            error = null,
                        )
                    }
                    is ProcessingState.Failure -> _uiState.update {
                        it.copy(
                            busy = false,
                            progressPercent = null,
                            status = "失敗",
                            error = processing.message,
                        )
                    }
                }
            }
        }
    }

    fun acceptPickedUris(uriStrings: List<String>) {
        if (_uiState.value.busy) return
        val unique = uriStrings.filter { it.isNotBlank() }.distinct()
        if (unique.isEmpty()) return
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    busy = true,
                    progressPercent = null,
                    status = "選択した動画を確認中",
                    error = null,
                )
            }
            try {
                val videos = withContext(Dispatchers.IO) {
                    unique.mapNotNull(::describeVideo)
                }
                if (videos.isEmpty()) {
                    throw IllegalArgumentException("MP4 / MKV の動画を選択してください")
                }
                val oldSession = _uiState.value.trimEditor?.sessionPath
                cancelThumbnailLoading()
                _uiState.update {
                    it.copy(
                        selectedVideos = videos,
                        trimEditor = null,
                        pendingOutput = null,
                        pendingDestination = null,
                        status = "${videos.size}本の動画を選択しました",
                        error = if (videos.size != unique.size) "MP4 / MKV 以外のファイルは除外しました" else null,
                    )
                }
                ProcessingStateStore.idle()
                if (oldSession != null) {
                    withContext(Dispatchers.IO) { pipeline.discardPreparedSession(oldSession) }
                }
            } catch (error: Throwable) {
                _uiState.update {
                    it.copy(error = error.message ?: error.javaClass.simpleName, status = "選択に失敗しました")
                }
            } finally {
                _uiState.update { it.copy(busy = false, progressPercent = null) }
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
                pendingDestination = null,
                status = if (next.isEmpty()) "動画を選択してください" else "${next.size}本の動画を選択しました",
                error = null,
            )
        }
    }

    fun requestConcatDestination(outputName: String) {
        val selected = _uiState.value.selectedVideos
        if (selected.size < 2) {
            showError("結合するMP4/MKVを2本以上選択してください")
            return
        }
        val name = validatedOutputName(outputName, suggestedConcatName(selected.first().displayName)) ?: return
        requestDestination(PendingDestinationKind.CONCAT, name)
    }

    fun requestCutDestination(outputName: String) {
        val editor = _uiState.value.trimEditor ?: return
        val name = validatedOutputName(outputName, suggestedCutName(editor.sourceName)) ?: return
        requestDestination(PendingDestinationKind.CUT, name)
    }

    private fun requestDestination(kind: PendingDestinationKind, outputName: String) {
        if (_uiState.value.busy) return
        _uiState.update {
            it.copy(
                pendingDestination = PendingDestinationRequest(
                    token = System.nanoTime(),
                    kind = kind,
                    outputName = outputName,
                    mimeType = mimeFor(outputName),
                ),
                status = "XFilesでSMB保存先を選択してください",
                error = null,
            )
        }
    }

    fun destinationPickerCancelled() {
        if (_uiState.value.pendingDestination == null) return
        _uiState.update {
            it.copy(
                pendingDestination = null,
                status = "保存先の選択をキャンセルしました",
                error = null,
            )
        }
    }

    fun destinationPickerFailed(message: String) {
        _uiState.update {
            it.copy(
                pendingDestination = null,
                status = "保存先を開けませんでした",
                error = message,
            )
        }
    }

    fun startPendingDestination(outputUri: String) {
        val state = _uiState.value
        val pending = state.pendingDestination ?: return
        val app = getApplication<Application>()
        val started = runCatching {
            when (pending.kind) {
                PendingDestinationKind.CONCAT -> {
                    require(state.selectedVideos.size >= 2) { "結合キューがありません" }
                    ClipForgeProcessingService.startConcat(
                        context = app,
                        inputs = state.selectedVideos,
                        outputUri = outputUri,
                        outputName = pending.outputName,
                    )
                }
                PendingDestinationKind.CUT -> {
                    val editor = requireNotNull(state.trimEditor) { "カット編集情報がありません" }
                    val source = sourceFor(editor, state)
                    ClipForgeProcessingService.startCut(
                        context = app,
                        source = source,
                        sessionPath = editor.sessionPath,
                        localInputPath = editor.localPath,
                        outputUri = outputUri,
                        outputName = pending.outputName,
                        startMs = editor.startMs,
                        endMs = editor.endMs,
                    )
                }
            }
        }
        started.fold(
            onSuccess = {
                if (pending.kind == PendingDestinationKind.CUT) cancelThumbnailLoading()
                _uiState.update {
                    it.copy(
                        busy = true,
                        progressPercent = null,
                        trimEditor = if (pending.kind == PendingDestinationKind.CUT) null else it.trimEditor,
                        pendingDestination = null,
                        status = "バックグラウンド処理を開始しました",
                        error = null,
                    )
                }
            },
            onFailure = { error ->
                runCatching { app.contentResolver.delete(Uri.parse(outputUri), null, null) }
                _uiState.update {
                    it.copy(
                        pendingDestination = null,
                        status = "処理を開始できませんでした",
                        error = error.message ?: error.javaClass.simpleName,
                    )
                }
            },
        )
    }

    fun concatSelected(outputName: String) {
        requestConcatDestination(outputName)
    }

    fun openTrimEditor() {
        val selected = _uiState.value.selectedVideos
        if (selected.size != 1) {
            showError("カットする動画を1本だけ選択してください")
            return
        }
        if (_uiState.value.busy) return
        val app = getApplication<Application>()
        val source = selected.single()
        cancelThumbnailLoading()
        ProcessingStateStore.idle()
        _uiState.update {
            it.copy(
                busy = true,
                progressPercent = 0,
                status = "編集画面を準備中",
                error = null,
            )
        }
        runCatching { ClipForgeProcessingService.startPrepareCut(app, source) }
            .onFailure { error ->
                _uiState.update {
                    it.copy(
                        busy = false,
                        progressPercent = null,
                        status = "編集画面を準備できませんでした",
                        error = error.message ?: error.javaClass.simpleName,
                    )
                }
            }
    }

    fun loadTrimThumbnails() {
        val editor = _uiState.value.trimEditor ?: return
        if (editor.thumbnailPaths.isNotEmpty()) return
        if (thumbnailJob?.isActive == true && thumbnailJobSessionPath == editor.sessionPath) return

        cancelThumbnailLoading()
        thumbnailJobSessionPath = editor.sessionPath
        val state = _uiState.value
        val source = sourceFor(editor, state)
        thumbnailJob = viewModelScope.launch {
            val paths = runCatching {
                pipeline.generateCutThumbnails(
                    source = source,
                    sessionPath = editor.sessionPath,
                    localInputPath = editor.localPath,
                    durationMs = editor.durationMs,
                )
            }.getOrNull() ?: return@launch

            _uiState.update { currentState ->
                val currentEditor = currentState.trimEditor ?: return@update currentState
                if (currentEditor.sessionPath != editor.sessionPath) return@update currentState
                currentState.copy(
                    trimEditor = currentEditor.copy(thumbnailPaths = paths),
                )
            }
        }
    }

    fun updateTrimRange(requestedStartMs: Long, requestedEndMs: Long): TrimEditorState? {
        val editor = _uiState.value.trimEditor ?: return null
        val duration = editor.durationMs.coerceAtLeast(1L)
        val start = requestedStartMs.coerceIn(0L, duration - 1L)
        val end = requestedEndMs.coerceIn(start + 1L, duration)
        val updated = editor.copy(startMs = start, endMs = end)
        _uiState.update { it.copy(trimEditor = updated, error = null) }
        return updated
    }

    fun snapTrimRangeToKeyframes() {
        val editor = _uiState.value.trimEditor ?: return
        val requestedStart = editor.startMs
        val requestedEnd = editor.endMs
        val source = sourceFor(editor, _uiState.value)
        viewModelScope.launch {
            runCatching {
                pipeline.snapCutRange(
                    source = source,
                    localInputPath = editor.localPath,
                    durationMs = editor.durationMs,
                    requestedStartMs = requestedStart,
                    requestedEndMs = requestedEnd,
                )
            }.onSuccess { snapped ->
                _uiState.update { state ->
                    val current = state.trimEditor ?: return@update state
                    if (
                        current.sourceUri != editor.sourceUri ||
                        current.sessionPath != editor.sessionPath ||
                        current.startMs != requestedStart ||
                        current.endMs != requestedEnd
                    ) {
                        return@update state
                    }
                    state.copy(
                        trimEditor = current.copy(startMs = snapped.first, endMs = snapped.last),
                        error = null,
                    )
                }
            }
        }
    }

    fun applyTrim(outputName: String) {
        requestCutDestination(outputName)
    }

    fun cancelTrimEditor() {
        if (_uiState.value.busy) return
        val editor = _uiState.value.trimEditor ?: return
        cancelThumbnailLoading()
        _uiState.update {
            it.copy(
                trimEditor = null,
                progressPercent = null,
                status = "編集をキャンセルしました",
                error = null,
            )
        }
        ProcessingStateStore.idle()
        viewModelScope.launch(Dispatchers.IO) { pipeline.discardPreparedSession(editor.sessionPath) }
    }

    fun outputHandedOff() {
        _uiState.update { it.copy(pendingOutput = null, status = "XFilesで保存先を選択してください", error = null) }
    }

    fun outputHandoffFailed(message: String) {
        _uiState.update { it.copy(pendingOutput = null, status = "出力の受け渡しに失敗しました", error = message) }
    }

    private fun sourceFor(editor: TrimEditorState, state: MainUiState): PickedVideo =
        PickedVideo(
            uri = editor.sourceUri,
            displayName = editor.sourceName,
            sizeBytes = state.selectedVideos.firstOrNull { it.uri == editor.sourceUri }?.sizeBytes,
        )

    private fun cancelThumbnailLoading() {
        thumbnailJob?.cancel()
        thumbnailJob = null
        thumbnailJobSessionPath = null
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
}
