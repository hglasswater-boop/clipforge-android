package app.clipforge.media

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AutoTrimUiState(
    val sessionPath: String? = null,
    val visible: Boolean = false,
    val running: Boolean = false,
    val analysis: AutoTrimAnalysis? = null,
    val error: String? = null,
)

object AutoTrimStateStore {
    private val _state = MutableStateFlow(AutoTrimUiState())
    val state = _state.asStateFlow()

    fun begin(sessionPath: String) {
        _state.value = AutoTrimUiState(
            sessionPath = sessionPath,
            visible = true,
            running = true,
        )
    }

    fun ready(sessionPath: String, analysis: AutoTrimAnalysis) {
        _state.value = AutoTrimUiState(
            sessionPath = sessionPath,
            visible = true,
            running = false,
            analysis = analysis,
        )
    }

    fun failure(sessionPath: String, message: String) {
        val current = _state.value
        _state.value = AutoTrimUiState(
            sessionPath = sessionPath,
            visible = true,
            running = false,
            analysis = current.analysis.takeIf { current.sessionPath == sessionPath },
            error = message,
        )
    }

    fun cancelled(sessionPath: String) {
        val current = _state.value
        _state.value = current.copy(
            sessionPath = sessionPath,
            visible = true,
            running = false,
            error = "自動解析をキャンセルしました",
        )
    }

    fun show(sessionPath: String): Boolean {
        val current = _state.value
        if (current.sessionPath != sessionPath) return false
        _state.value = current.copy(visible = true)
        return true
    }

    fun dismiss(sessionPath: String) {
        val current = _state.value
        if (current.sessionPath != sessionPath) return
        _state.value = current.copy(visible = false)
    }
}
