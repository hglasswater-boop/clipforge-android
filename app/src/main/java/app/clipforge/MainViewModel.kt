package app.clipforge

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.clipforge.media.FfmpegMediaEngine
import app.clipforge.smb.SmbClient
import app.clipforge.smb.SmbConnection
import app.clipforge.smb.SmbEntry
import app.clipforge.workflow.RemoteEditPipeline
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class MainUiState(
    val connected: Boolean = false,
    val busy: Boolean = false,
    val currentPath: String = "",
    val entries: List<SmbEntry> = emptyList(),
    val selectedPaths: List<String> = emptyList(),
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

    fun cutSelected(outputName: String, startSeconds: String, endSeconds: String) {
        val selected = _uiState.value.selectedPaths
        if (selected.size != 1) {
            showError("カットする動画を1本だけ選択してください")
            return
        }
        val startMs = startSeconds.toDoubleOrNull()?.times(1000)?.toLong()
        val endMs = endSeconds.takeIf { it.isNotBlank() }?.toDoubleOrNull()?.times(1000)?.toLong()
        if (startMs == null || startMs < 0 || (endMs != null && endMs <= startMs)) {
            showError("開始/終了秒を確認してください")
            return
        }
        val inputExtension = selected.single().substringAfterLast('.', "mkv")
        val output = validatedOutputPath(outputName, "cut.$inputExtension") ?: return
        runTask("カットを開始") {
            pipeline.cut(selected.single(), output, startMs, endMs) { message -> _uiState.update { it.copy(status = message) } }
            refreshAfterOperation()
        }
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
        smb.close()
        super.onCleared()
    }
}
