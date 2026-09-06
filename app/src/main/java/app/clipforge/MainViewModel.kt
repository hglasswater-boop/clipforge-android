package app.clipforge

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.clipforge.media.CutMode
import app.clipforge.media.FfmpegMediaEngine
import app.clipforge.media.MediaSegment
import app.clipforge.media.SceneMarker
import app.clipforge.media.SceneMarkerKind
import app.clipforge.media.normalizeCutRanges
import app.clipforge.media.rangeAfterSettingEnd
import app.clipforge.media.rangeAfterSettingStart
import app.clipforge.media.remainingSegments
import app.clipforge.processing.ClipForgeProcessingService
import app.clipforge.processing.ProcessingState
import app.clipforge.processing.ProcessingStateStore
import app.clipforge.workflow.CutSessionNavigator
import app.clipforge.workflow.ExternalEditPipeline
import app.clipforge.workflow.PickedVideo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
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
    val cutRanges: List<MediaSegment> = emptyList(),
    val cutMode: CutMode = CutMode.SMART,
    val editingCutIndex: Int? = null,
) {
    val removedDurationMs: Long
        get() = cutRanges.sumOf(MediaSegment::durationMs)

    val resultDurationMs: Long
        get() = (durationMs - removedDurationMs).coerceAtLeast(0L)
}

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
    val canCancelProcessing: Boolean = false,
    val canUndoEdit: Boolean = false,
    val sceneSearchBusy: Boolean = false,
    val sceneMarkers: List<SceneMarker> = emptyList(),
    val sceneScannedRanges: List<MediaSegment> = emptyList(),
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
    private val navigator = CutSessionNavigator(application)
    private val cutDraftStore = CutDraftStore(application)
    private val _uiState = MutableStateFlow(MainUiState())
    val uiState = _uiState.asStateFlow()
    private var thumbnailJob: Job? = null
    private var thumbnailJobSessionPath: String? = null
    private var editorBeforeCutProcessing: TrimEditorState? = null
    private val editUndoStack = ArrayDeque<TrimEditorState>()

    init {
        viewModelScope.launch {
            ProcessingStateStore.state.collect { processing ->
                when (processing) {
                    ProcessingState.Idle -> Unit
                    is ProcessingState.Running -> _uiState.update {
                        it.copy(
                            busy = true,
                            progressPercent = processing.progressPercent,
                            canCancelProcessing = true,
                            status = processing.message,
                            error = null,
                        )
                    }
                    is ProcessingState.CutPrepared -> {
                        val knownSourceSize = _uiState.value.selectedVideos
                            .firstOrNull { it.uri == processing.sourceUri }
                            ?.sizeBytes
                        val savedDraft = withContext(Dispatchers.IO) {
                            cutDraftStore.load(
                                sourceUri = processing.sourceUri,
                                sourceName = processing.sourceName,
                                sourceSizeBytes = knownSourceSize,
                                durationMs = processing.durationMs,
                            )
                        }
                        resetEditHistory()
                        _uiState.update { state ->
                            val preparedSource = PickedVideo(
                                uri = processing.sourceUri,
                                displayName = processing.sourceName,
                                sizeBytes = knownSourceSize,
                            )
                            val freshEditor = TrimEditorState(
                                sourceUri = processing.sourceUri,
                                sourceName = processing.sourceName,
                                sessionPath = processing.sessionPath,
                                localPath = processing.localPath,
                                durationMs = processing.durationMs,
                                startMs = 0L,
                                endMs = processing.durationMs,
                                thumbnailPaths = processing.thumbnailPaths,
                                cutRanges = emptyList(),
                                cutMode = CutMode.SMART,
                            )
                            val restoredEditor = savedDraft?.restoreInto(freshEditor) ?: freshEditor
                            state.copy(
                                busy = false,
                                progressPercent = null,
                                canCancelProcessing = false,
                                canUndoEdit = false,
                                sceneSearchBusy = false,
                                sceneMarkers = emptyList(),
                                sceneScannedRanges = emptyList(),
                                selectedVideos = state.selectedVideos.ifEmpty { listOf(preparedSource) },
                                trimEditor = restoredEditor,
                                pendingDestination = null,
                                status = if (savedDraft != null && restoredEditor.cutRanges.isNotEmpty()) {
                                    "前回の削除範囲を自動復元しました（${restoredEditor.cutRanges.size}箇所）"
                                } else {
                                    "削除したい範囲を選んで追加してください。切断点は自動保存されます"
                                },
                                error = null,
                            )
                        }
                    }
                    is ProcessingState.Success -> {
                        editorBeforeCutProcessing = null
                        resetEditHistory()
                        _uiState.update {
                            it.copy(
                                busy = false,
                                progressPercent = null,
                                canCancelProcessing = false,
                                canUndoEdit = false,
                                sceneSearchBusy = false,
                                sceneMarkers = emptyList(),
                                sceneScannedRanges = emptyList(),
                                status = processing.message,
                                error = null,
                            )
                        }
                    }
                    is ProcessingState.Failure -> {
                        val editorToRestore = editorBeforeCutProcessing
                        editorBeforeCutProcessing = null
                        _uiState.update {
                            it.copy(
                                busy = false,
                                progressPercent = null,
                                canCancelProcessing = false,
                                sceneSearchBusy = false,
                                trimEditor = it.trimEditor ?: editorToRestore,
                                canUndoEdit = editUndoStack.isNotEmpty(),
                                status = "失敗",
                                error = processing.message,
                            )
                        }
                    }
                    is ProcessingState.Cancelled -> {
                        val editorToRestore = editorBeforeCutProcessing
                        editorBeforeCutProcessing = null
                        _uiState.update {
                            it.copy(
                                busy = false,
                                progressPercent = null,
                                canCancelProcessing = false,
                                sceneSearchBusy = false,
                                trimEditor = it.trimEditor ?: editorToRestore,
                                canUndoEdit = editUndoStack.isNotEmpty(),
                                status = processing.message,
                                error = null,
                            )
                        }
                    }
                }
            }
        }

        viewModelScope.launch {
            _uiState
                .map { state ->
                    state.trimEditor?.let { editor ->
                        cutDraftFrom(
                            editor = editor,
                            sourceSizeBytes = state.selectedVideos
                                .firstOrNull { it.uri == editor.sourceUri }
                                ?.sizeBytes,
                        )
                    }
                }
                .filterNotNull()
                .distinctUntilChanged()
                .collect { draft ->
                    withContext(Dispatchers.IO) { cutDraftStore.save(draft) }
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
                    canCancelProcessing = false,
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
                editorBeforeCutProcessing = null
                resetEditHistory()
                _uiState.update {
                    it.copy(
                        selectedVideos = videos,
                        trimEditor = null,
                        pendingOutput = null,
                        pendingDestination = null,
                        canUndoEdit = false,
                        sceneSearchBusy = false,
                        sceneMarkers = emptyList(),
                        sceneScannedRanges = emptyList(),
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
                _uiState.update {
                    it.copy(busy = false, progressPercent = null, canCancelProcessing = false)
                }
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
        if (editor.cutRanges.isEmpty()) {
            showError("削除する範囲を1箇所以上追加してください")
            return
        }
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
                    require(editor.cutRanges.isNotEmpty()) { "削除する範囲がありません" }
                    val source = sourceFor(editor, state)
                    ClipForgeProcessingService.startCut(
                        context = app,
                        source = source,
                        sessionPath = editor.sessionPath,
                        localInputPath = editor.localPath,
                        outputUri = outputUri,
                        outputName = pending.outputName,
                        durationMs = editor.durationMs,
                        cutRanges = editor.cutRanges,
                        cutMode = editor.cutMode,
                    )
                }
            }
        }
        started.fold(
            onSuccess = {
                if (pending.kind == PendingDestinationKind.CUT) {
                    editorBeforeCutProcessing = state.trimEditor
                    cancelThumbnailLoading()
                }
                _uiState.update {
                    it.copy(
                        busy = true,
                        progressPercent = null,
                        canCancelProcessing = true,
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

    fun cancelProcessing() {
        val state = _uiState.value
        if (!state.busy || !state.canCancelProcessing) return
        _uiState.update {
            it.copy(
                canCancelProcessing = false,
                status = "キャンセルしています…",
                error = null,
            )
        }
        runCatching {
            ClipForgeProcessingService.cancelProcessing(getApplication<Application>())
        }.onFailure { error ->
            _uiState.update {
                it.copy(
                    canCancelProcessing = true,
                    status = "キャンセルできませんでした",
                    error = error.message ?: error.javaClass.simpleName,
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
        if (_uiState.value.busy) return
        val app = getApplication<Application>()
        val source = selected.single()
        cancelThumbnailLoading()
        ProcessingStateStore.idle()
        resetEditHistory()
        _uiState.update {
            it.copy(
                busy = true,
                progressPercent = 0,
                canCancelProcessing = true,
                canUndoEdit = false,
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
                        canCancelProcessing = false,
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

    fun setTrimStartAt(positionMs: Long) {
        val editor = _uiState.value.trimEditor ?: return
        val range = rangeAfterSettingStart(
            durationMs = editor.durationMs,
            currentStartMs = editor.startMs,
            currentEndMs = editor.endMs,
            positionMs = positionMs,
        )
        _uiState.update {
            it.copy(
                trimEditor = editor.copy(startMs = range.startMs, endMs = range.endMs),
                status = "開始位置を設定しました",
                error = null,
            )
        }
    }

    fun setTrimEndAt(positionMs: Long) {
        val editor = _uiState.value.trimEditor ?: return
        val range = rangeAfterSettingEnd(
            durationMs = editor.durationMs,
            currentStartMs = editor.startMs,
            currentEndMs = editor.endMs,
            positionMs = positionMs,
        )
        _uiState.update {
            it.copy(
                trimEditor = editor.copy(startMs = range.startMs, endMs = range.endMs),
                status = "終了位置を設定しました",
                error = null,
            )
        }
    }

    fun setCutMode(mode: CutMode) {
        if (_uiState.value.busy) return
        val state = _uiState.value
        val editor = state.trimEditor ?: return
        if (editor.cutMode == mode) return

        if (mode == CutMode.SMART) {
            recordEditUndo(editor)
            _uiState.update {
                it.copy(
                    trimEditor = editor.copy(cutMode = CutMode.SMART, editingCutIndex = null),
                    canUndoEdit = true,
                    status = "正確カットに切り替えました",
                    error = null,
                )
            }
            return
        }

        val source = sourceFor(editor, state)
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    busy = true,
                    progressPercent = null,
                    canCancelProcessing = false,
                    status = "削除範囲をキーフレームに合わせています",
                    error = null,
                )
            }
            try {
                val snappedCuts = editor.cutRanges.map { range ->
                    val snapped = pipeline.snapCutRange(
                        source = source,
                        localInputPath = editor.localPath,
                        durationMs = editor.durationMs,
                        requestedStartMs = range.startMs,
                        requestedEndMs = range.endMs,
                    )
                    MediaSegment(snapped.first, snapped.last)
                }
                val normalized = normalizeCutRanges(editor.durationMs, snappedCuts)
                require(remainingSegments(editor.durationMs, normalized).isNotEmpty()) {
                    "キーフレームへ合わせると動画全体が削除対象になります。正確カットを使用してください"
                }
                val snappedSelection = pipeline.snapCutRange(
                    source = source,
                    localInputPath = editor.localPath,
                    durationMs = editor.durationMs,
                    requestedStartMs = editor.startMs,
                    requestedEndMs = editor.endMs,
                )
                recordEditUndo(editor)
                _uiState.update { currentState ->
                    val current = currentState.trimEditor ?: return@update currentState
                    if (current.sessionPath != editor.sessionPath) return@update currentState
                    currentState.copy(
                        trimEditor = current.copy(
                            cutMode = CutMode.LOSSLESS,
                            startMs = snappedSelection.first,
                            endMs = snappedSelection.last,
                            cutRanges = normalized,
                            editingCutIndex = null,
                        ),
                        canUndoEdit = true,
                        status = "完全無劣化に切り替えました（登録済み範囲も変換済み）",
                        error = null,
                    )
                }
            } catch (error: Throwable) {
                _uiState.update {
                    it.copy(
                        status = "完全無劣化へ切り替えられませんでした",
                        error = error.message ?: error.javaClass.simpleName,
                    )
                }
            } finally {
                _uiState.update {
                    it.copy(busy = false, progressPercent = null, canCancelProcessing = false)
                }
            }
        }
    }

    suspend fun adjacentKeyframe(positionMs: Long, forward: Boolean): Long? {
        val state = _uiState.value
        val editor = state.trimEditor ?: return null
        return runCatching {
            navigator.adjacentKeyframe(
                source = sourceFor(editor, state),
                localInputPath = editor.localPath,
                durationMs = editor.durationMs,
                positionMs = positionMs,
                forward = forward,
            )
        }.getOrNull()
    }

    suspend fun adjacentSceneMarker(
        positionMs: Long,
        forward: Boolean,
        kind: SceneMarkerKind,
    ): Long? {
        val initialState = _uiState.value
        val editor = initialState.trimEditor ?: return null
        if (initialState.sceneSearchBusy) return null
        val pivot = positionMs.coerceIn(0L, editor.durationMs)
        val tolerance = 250L

        fun nearest(markers: List<SceneMarker>): SceneMarker? = markers
            .asSequence()
            .filter { it.kind == kind }
            .filter { marker ->
                if (forward) marker.timeMs > pivot + tolerance else marker.timeMs < pivot - tolerance
            }
            .let { sequence ->
                if (forward) sequence.minByOrNull(SceneMarker::timeMs) else sequence.maxByOrNull(SceneMarker::timeMs)
            }

        val cached = nearest(initialState.sceneMarkers)
        if (cached != null && intervalCovered(
                fromMs = minOf(pivot, cached.timeMs),
                toMs = maxOf(pivot, cached.timeMs),
                ranges = initialState.sceneScannedRanges,
            )
        ) {
            return cached.timeMs
        }

        val searchAnchor = if (forward) {
            (pivot + tolerance).coerceAtMost(editor.durationMs)
        } else {
            (pivot - tolerance).coerceAtLeast(0L)
        }
        val frontier = if (forward) {
            coveredForwardEdge(searchAnchor, initialState.sceneScannedRanges)
        } else {
            coveredBackwardEdge(searchAnchor, initialState.sceneScannedRanges)
        }
        val scanStart = if (forward) {
            frontier
        } else {
            (frontier - SCENE_SEARCH_WINDOW_MS).coerceAtLeast(0L)
        }
        val scanEnd = if (forward) {
            (frontier + SCENE_SEARCH_WINDOW_MS).coerceAtMost(editor.durationMs)
        } else {
            frontier
        }
        if (scanEnd <= scanStart) {
            _uiState.update {
                it.copy(
                    status = if (forward) "この先に候補はありません" else "この手前に候補はありません",
                    error = null,
                )
            }
            return null
        }

        _uiState.update {
            it.copy(
                sceneSearchBusy = true,
                status = if (forward) "次のカット候補を探しています" else "前のカット候補を探しています",
                error = null,
            )
        }
        return try {
            val result = navigator.detectSceneWindow(
                source = sourceFor(editor, initialState),
                localInputPath = editor.localPath,
                durationMs = editor.durationMs,
                startMs = scanStart,
                endMs = scanEnd,
            )
            var mergedMarkers = emptyList<SceneMarker>()
            _uiState.update { state ->
                val current = state.trimEditor ?: return@update state
                if (current.sessionPath != editor.sessionPath) return@update state
                mergedMarkers = mergeSceneMarkers(state.sceneMarkers + result.markers)
                state.copy(
                    sceneMarkers = mergedMarkers,
                    sceneScannedRanges = mergeScanRanges(
                        state.sceneScannedRanges + MediaSegment(result.scannedStartMs, result.scannedEndMs),
                    ),
                    error = null,
                )
            }
            val target = nearest(mergedMarkers)
            _uiState.update { state ->
                val label = when (kind) {
                    SceneMarkerKind.SCENE_CHANGE -> "シーン切替"
                    SceneMarkerKind.BLACK -> "黒画面"
                }
                state.copy(
                    status = if (target != null) {
                        "$label 候補へ移動します"
                    } else {
                        "この${SCENE_SEARCH_WINDOW_MS / 1_000}秒には${label}候補なし。もう一度押すと続きから探します"
                    },
                    error = null,
                )
            }
            target?.timeMs
        } catch (error: Throwable) {
            _uiState.update {
                it.copy(
                    status = "カット候補を解析できませんでした",
                    error = error.message ?: error.javaClass.simpleName,
                )
            }
            null
        } finally {
            _uiState.update { it.copy(sceneSearchBusy = false) }
        }
    }

    fun snapTrimRangeToKeyframes() {
        val editor = _uiState.value.trimEditor ?: return
        if (editor.cutMode != CutMode.LOSSLESS) return
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
                        current.endMs != requestedEnd ||
                        current.cutMode != CutMode.LOSSLESS
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

    fun selectCutRange(index: Int) {
        if (_uiState.value.busy) return
        _uiState.update { state ->
            val editor = state.trimEditor ?: return@update state
            val range = editor.cutRanges.getOrNull(index) ?: return@update state
            state.copy(
                trimEditor = editor.copy(
                    startMs = range.startMs,
                    endMs = range.endMs,
                    editingCutIndex = index,
                ),
                status = "削除範囲 #${index + 1} を編集中",
                error = null,
            )
        }
    }

    fun startNewCutRange() {
        if (_uiState.value.busy) return
        _uiState.update { state ->
            val editor = state.trimEditor ?: return@update state
            state.copy(
                trimEditor = editor.copy(editingCutIndex = null),
                status = "新しい削除範囲を選択してください",
                error = null,
            )
        }
    }

    fun addCurrentCutRange() {
        val editor = _uiState.value.trimEditor ?: return
        if (_uiState.value.busy) return
        val requestedStart = editor.startMs
        val requestedEnd = editor.endMs
        val editIndex = editor.editingCutIndex?.takeIf { it in editor.cutRanges.indices }
        val baseRanges = if (editIndex == null) {
            editor.cutRanges
        } else {
            editor.cutRanges.filterIndexed { index, _ -> index != editIndex }
        }
        val action = if (editIndex == null) "追加" else "更新"

        if (editor.cutMode == CutMode.SMART) {
            val candidate = MediaSegment(requestedStart, requestedEnd)
            val normalized = normalizeCutRanges(editor.durationMs, baseRanges + candidate)
            if (remainingSegments(editor.durationMs, normalized).isEmpty()) {
                showError("動画全体を削除する範囲は追加できません")
                return
            }
            recordEditUndo(editor)
            _uiState.update { state ->
                val current = state.trimEditor ?: return@update state
                if (current.sessionPath != editor.sessionPath) return@update state
                state.copy(
                    trimEditor = current.copy(cutRanges = normalized, editingCutIndex = null),
                    canUndoEdit = true,
                    status = "削除範囲を${action}しました（${normalized.size}箇所）",
                    error = null,
                )
            }
            return
        }

        val source = sourceFor(editor, _uiState.value)
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    busy = true,
                    progressPercent = null,
                    canCancelProcessing = false,
                    status = "削除位置をキーフレームに合わせています",
                    error = null,
                )
            }
            try {
                val snapped = pipeline.snapCutRange(
                    source = source,
                    localInputPath = editor.localPath,
                    durationMs = editor.durationMs,
                    requestedStartMs = requestedStart,
                    requestedEndMs = requestedEnd,
                )
                val candidate = MediaSegment(snapped.first, snapped.last)
                val normalized = normalizeCutRanges(editor.durationMs, baseRanges + candidate)
                require(remainingSegments(editor.durationMs, normalized).isNotEmpty()) {
                    "動画全体を削除する範囲は追加できません"
                }
                recordEditUndo(editor)
                _uiState.update { state ->
                    val current = state.trimEditor ?: return@update state
                    if (current.sessionPath != editor.sessionPath) return@update state
                    state.copy(
                        trimEditor = current.copy(
                            startMs = candidate.startMs,
                            endMs = candidate.endMs,
                            cutRanges = normalized,
                            editingCutIndex = null,
                        ),
                        canUndoEdit = true,
                        status = "無劣化の削除範囲を${action}しました（${normalized.size}箇所）",
                        error = null,
                    )
                }
            } catch (error: Throwable) {
                _uiState.update {
                    it.copy(
                        status = "削除範囲を${action}できませんでした",
                        error = error.message ?: error.javaClass.simpleName,
                    )
                }
            } finally {
                _uiState.update {
                    it.copy(busy = false, progressPercent = null, canCancelProcessing = false)
                }
            }
        }
    }

    fun removeCutRange(index: Int) {
        if (_uiState.value.busy) return
        _uiState.update { state ->
            val editor = state.trimEditor ?: return@update state
            if (index !in editor.cutRanges.indices) return@update state
            recordEditUndo(editor)
            val next = editor.cutRanges.toMutableList().apply { removeAt(index) }
            val editingIndex = editor.editingCutIndex?.let { selected ->
                when {
                    selected == index -> null
                    selected > index -> selected - 1
                    else -> selected
                }
            }
            state.copy(
                trimEditor = editor.copy(cutRanges = next, editingCutIndex = editingIndex),
                canUndoEdit = true,
                status = if (next.isEmpty()) "削除範囲はありません" else "削除範囲: ${next.size}箇所",
                error = null,
            )
        }
    }

    fun clearCutRanges() {
        if (_uiState.value.busy) return
        _uiState.update { state ->
            val editor = state.trimEditor ?: return@update state
            if (editor.cutRanges.isEmpty()) return@update state
            recordEditUndo(editor)
            state.copy(
                trimEditor = editor.copy(cutRanges = emptyList(), editingCutIndex = null),
                canUndoEdit = true,
                status = "削除範囲をすべて解除しました。元に戻すこともできます",
                error = null,
            )
        }
    }

    fun undoEdit() {
        if (_uiState.value.busy || editUndoStack.isEmpty()) return
        val previous = editUndoStack.removeLast()
        _uiState.update { state ->
            val current = state.trimEditor ?: return@update state
            if (current.sessionPath != previous.sessionPath) {
                resetEditHistory()
                return@update state.copy(canUndoEdit = false)
            }
            state.copy(
                trimEditor = previous.copy(thumbnailPaths = current.thumbnailPaths),
                canUndoEdit = editUndoStack.isNotEmpty(),
                status = "元に戻しました",
                error = null,
            )
        }
    }

    fun applyTrim(outputName: String) {
        requestCutDestination(outputName)
    }

    fun cancelTrimEditor() {
        if (_uiState.value.busy) return
        val editor = _uiState.value.trimEditor ?: return
        cancelThumbnailLoading()
        editorBeforeCutProcessing = null
        resetEditHistory()
        _uiState.update {
            it.copy(
                trimEditor = null,
                progressPercent = null,
                canCancelProcessing = false,
                canUndoEdit = false,
                sceneSearchBusy = false,
                sceneMarkers = emptyList(),
                sceneScannedRanges = emptyList(),
                status = "編集画面を閉じました。切断点は自動保存されています",
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

    private fun mergeSceneMarkers(markers: List<SceneMarker>): List<SceneMarker> = markers
        .sortedWith(compareBy(SceneMarker::timeMs, SceneMarker::kind))
        .fold(mutableListOf()) { result, marker ->
            val duplicate = result.lastOrNull { it.kind == marker.kind }
                ?.let { previous -> kotlin.math.abs(previous.timeMs - marker.timeMs) <= 120L }
                ?: false
            if (!duplicate) result += marker
            result
        }

    private fun mergeScanRanges(ranges: List<MediaSegment>): List<MediaSegment> {
        if (ranges.isEmpty()) return emptyList()
        val sorted = ranges.sortedBy(MediaSegment::startMs)
        val merged = mutableListOf<MediaSegment>()
        sorted.forEach { range ->
            val last = merged.lastOrNull()
            if (last == null || range.startMs > last.endMs + 1L) {
                merged += range
            } else {
                merged[merged.lastIndex] = MediaSegment(last.startMs, maxOf(last.endMs, range.endMs))
            }
        }
        return merged
    }

    private fun intervalCovered(fromMs: Long, toMs: Long, ranges: List<MediaSegment>): Boolean {
        val start = minOf(fromMs, toMs)
        val end = maxOf(fromMs, toMs)
        return ranges.any { range -> start >= range.startMs && end <= range.endMs }
    }

    private fun coveredForwardEdge(positionMs: Long, ranges: List<MediaSegment>): Long {
        var edge = positionMs
        ranges.forEach { range ->
            if (edge in range.startMs..range.endMs) edge = maxOf(edge, range.endMs)
        }
        return edge
    }

    private fun coveredBackwardEdge(positionMs: Long, ranges: List<MediaSegment>): Long {
        var edge = positionMs
        ranges.asReversed().forEach { range ->
            if (edge in range.startMs..range.endMs) edge = minOf(edge, range.startMs)
        }
        return edge
    }

    private fun recordEditUndo(editor: TrimEditorState) {
        if (editUndoStack.size >= MAX_EDIT_UNDO) editUndoStack.removeFirst()
        editUndoStack.addLast(editor)
    }

    private fun resetEditHistory() {
        editUndoStack.clear()
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

    private companion object {
        const val MAX_EDIT_UNDO = 20
        const val SCENE_SEARCH_WINDOW_MS = 90_000L
    }
}
