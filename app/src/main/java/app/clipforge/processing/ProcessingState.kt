package app.clipforge.processing

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface ProcessingState {
    data object Idle : ProcessingState
    data class Running(
        val title: String,
        val message: String,
        val progressPercent: Int? = null,
    ) : ProcessingState
    data class CutPrepared(
        val sourceUri: String,
        val sourceName: String,
        val localPath: String?,
        val durationMs: Long,
        val thumbnailPaths: List<String>,
    ) : ProcessingState
    data class Success(val message: String) : ProcessingState
    data class Failure(val message: String) : ProcessingState
}

object ProcessingStateStore {
    private val _state = MutableStateFlow<ProcessingState>(ProcessingState.Idle)
    val state = _state.asStateFlow()

    fun idle() {
        _state.value = ProcessingState.Idle
    }

    fun running(title: String, message: String, progressPercent: Int? = null) {
        _state.value = ProcessingState.Running(
            title = title,
            message = message,
            progressPercent = progressPercent?.coerceIn(0, 100),
        )
    }

    fun cutPrepared(
        sourceUri: String,
        sourceName: String,
        localPath: String?,
        durationMs: Long,
        thumbnailPaths: List<String>,
    ) {
        _state.value = ProcessingState.CutPrepared(
            sourceUri = sourceUri,
            sourceName = sourceName,
            localPath = localPath,
            durationMs = durationMs,
            thumbnailPaths = thumbnailPaths,
        )
    }

    fun success(message: String) {
        _state.value = ProcessingState.Success(message)
    }

    fun failure(message: String) {
        _state.value = ProcessingState.Failure(message)
    }
}
