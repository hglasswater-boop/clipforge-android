package app.clipforge

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.clipforge.media.FfmpegMediaEngine
import app.clipforge.media.TrimRangeSnapper
import app.clipforge.smb.SmbClient
import app.clipforge.smb.SmbConnection
import app.clipforge.smb.SmbEntry
import app.clipforge.workflow.RemoteEditPipeline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TrimEditorState(
    val remotePath: String,
    val localPath: String,
    val durationMs: Long,
    val startMs: Long,
    val endMs: Long,
    val keyframesMs: List<Long>,
    val thumbnailPaths: List<String>
)

data class MainUiState(
    val connected: Boolean = false,
    val busy: Boolean = false,
    val currentPath: String = "",
    val entries: List<SmbEntry> = emptyList(),
    val selectedPaths: List<String> = emptyList(),
    val trimEditor: TrimEditorState? = null,
    val status: String = "SMBへ接続してください",
    val error: String? = null
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val smb = SmbClient()
    private val pipeline = RemoteEditPipeline(application.cacheDir, smb, FfmpegMediaEngine())
    private val _uiState = MutableStateFlow(MainUiState())
    val uiState = _uiState.asStateFlow()

    fun connect(host: String, share: String, domain: String, username: String, password: String) {
        runTask("SMBへ接続中") {
            smb.connect(SmbConnection(host.trim(), share.trim('/'), domain.trim(), username, password))
            val entries = smb.list("")
            _uiState.update {
                it.copy(connected = true, currentPath = "", entries = entries, selectedPaths = emptyList(), status = "接続済み")
            }
        }
    }

    fun openDirectory(entry: SmbEntry) {
        if (!entry.directory) return
        loadDirectory(entry.path)
    }

    fun goUp() {
        val current = _uiState.value.currentPath.trimEnd('/')
        if (current.isBlank()) return
        val parent = current.substringBeforeLast('/', "").let { if (it.isBlank()) "" else "$it/" }
        loadDirectory(parent)
    }

    fun toggleSelection(entry: SmbEntry) {
        if (!entry.isVideo || _uiState.value.busy) return
        _uiState.update { state ->
            val next = state.selectedPaths.toMutableList()
            if (!next.remove(entry.path)) next += entry.path
            state.copy(selectedPaths = next, error = null)
        }
    }

    fun moveSelected(path: String, offset: Int) {
        if (_uiState.value.busy || offset == 0) return
        _uiState.update { state ->
            val next = state.selectedPaths.toMutableList()
            val from = next.indexOf(path)
            if (from < 0) return@update state
            val to = (from + offset).coerceIn(0, next.lastIndex)
            if (to == from) return@update state
            val item = next.removeAt(from)
            next.add(to, item)
            state.copy(selectedPaths = next, error = null)
        }
    }

    fun removeSelected(path: String) {
        if (_uiState.value.busy) return
        _uiState.update { state ->
            if (path !in state.selectedPaths) return@update state
            state.copy(selectedPaths = state.selectedPaths - path, error = null)
        }
    }

    fun concatSelected(outputName: String) {
        val selected = _uiState.value.selectedPaths
        if (selected.size < 2) {
            showError("結合するMP4/MKVを2本以上選択してください")
            return
        }
        val output = validatedOutputPath(outputName, "merged.mkv") ?: return
        runTask("結合を開始") {
            pipeline.concat(selected, output) { message -> _uiState.update { it.copy(status = message) } }
            refreshAfterOperation()
        }
    }

    fun openTrimEditor() {
        val selected = _uiState.value.selectedPaths
        if (selected.size != 1) {
            showError("カットする動画を1本だけ選択してください")
            return
        }
        runTask("編集画面を準備中") {
            val prepared = pipeline.prepareCut(selected.single()) { message ->
                _uiState.update { it.copy(status = message) }
            }
            _uiState.update {
                it.copy(
                    trimEditor = TrimEditorState(
                        remotePath = prepared.remoteInput,
                        localPath = prepared.localFile.absolutePath,
                        durationMs = prepared.durationMs,
                        startMs = 0L,
                        endMs = prepared.durationMs,
                        keyframesMs = prepared.keyframesMs,
                        thumbnailPaths = prepared.thumbnailPaths
                    ),
                    status = "範囲を選択してください"
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
            requestedEndMs = requestedEndMs
        )
        val updated = editor.copy(startMs = snapped.startMs, endMs = snapped.endMs)
        _uiState.update { it.copy(trimEditor = updated, error = null) }
        return updated
    }

    fun applyTrim(outputName: String) {
        val editor = _uiState.value.trimEditor ?: return
        val originalName = editor.remotePath.substringAfterLast('/')
        val extension = originalName.substringAfterLast('.', "mkv")
        val baseName = originalName.substringBeforeLast('.', originalName)
        val output = validatedOutputPath(outputName, "$baseName-cut.$extension") ?: return

        runTask("カットを開始") {
            pipeline.cutPrepared(
                localInputPath = editor.localPath,
                remoteInput = editor.remotePath,
                remoteOutput = output,
                startMs = editor.startMs,
                endMs = editor.endMs
            ) { message -> _uiState.update { it.copy(status = message) } }
            pipeline.discardPrepared(editor.localPath)
            _uiState.update { it.copy(trimEditor = null) }
            refreshAfterOperation()
        }
    }

    fun cancelTrimEditor() {
        if (_uiState.value.busy) return
        val editor = _uiState.value.trimEditor ?: return
        _uiState.update { it.copy(trimEditor = null, status = "編集をキャンセルしました", error = null) }
        viewModelScope.launch(Dispatchers.IO) { pipeline.discardPrepared(editor.localPath) }
    }

    private fun loadDirectory(path: String) {
        runTask("一覧を取得中") {
            val entries = smb.list(path)
            _uiState.update { it.copy(currentPath = path, entries = entries, selectedPaths = emptyList(), status = "接続済み") }
        }
    }

    private suspend fun refreshAfterOperation() {
        val path = _uiState.value.currentPath
        _uiState.update { it.copy(entries = smb.list(path), selectedPaths = emptyList()) }
    }

    private fun validatedOutputPath(requestedName: String, fallbackName: String): String? {
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
        return _uiState.value.currentPath + name
    }

    private fun showError(message: String) {
        _uiState.update { it.copy(error = message) }
    }

    private fun runTask(initialStatus: String, block: suspend () -> Unit) {
        if (_uiState.value.busy) return
        viewModelScope.launch {
            _uiState.update { it.copy(busy = true, status = initialStatus, error = null) }
            try {
                block()
            } catch (t: Throwable) {
                _uiState.update { it.copy(error = t.message ?: t.javaClass.simpleName, status = "失敗") }
            } finally {
                _uiState.update { it.copy(busy = false) }
            }
        }
    }

    override fun onCleared() {
        pipeline.cleanupPreparedSessions()
        smb.close()
        super.onCleared()
    }
}
