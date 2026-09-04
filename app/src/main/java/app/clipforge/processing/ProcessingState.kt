package app.clipforge.processing

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface ProcessingState {
    data object Idle : ProcessingState
    data class Running(val title: String, val message: String) : ProcessingState
    data class CutPrepared(
        val sourceUri: String,
        val sourceName: String,
        val localPath: String,
        val durationMs: Long,
        val keyframesMs: List<Long>,
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

    fun running(title: String, message: String) {
        _state.value = ProcessingState.Running(title, message)
    }

    fun cutPrepared(
        sourceUri: String,
        sourceName: String,
        localPath: String,
        durationMs: Long,
        keyframesMs: List<Long>,
        thumbnailPaths: List<String>,
    ) {
        _state.value = ProcessingState.CutPrepared(
            sourceUri = sourceUri,
            sourceName = sourceName,
            localPath = localPath,
            durationMs = durationMs,
            keyframesMs = keyframesMs,
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
