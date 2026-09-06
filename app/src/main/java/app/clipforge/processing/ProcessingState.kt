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
        val sessionPath: String,
        val localPath: String?,
        val durationMs: Long,
        val thumbnailPaths: List<String>,
    ) : ProcessingState
    data class Success(val message: String) : ProcessingState
    data class Failure(val message: String) : ProcessingState
    data class Cancelled(val message: String) : ProcessingState
}

object ProcessingStateStore {
    private val _state = MutableStateFlow<ProcessingState>(ProcessingState.Idle)
    private val writeProgressEta = WriteProgressEta()
    val state = _state.asStateFlow()

    fun idle() {
        writeProgressEta.reset()
        _state.value = ProcessingState.Idle
    }

    fun running(title: String, message: String, progressPercent: Int? = null) {
        _state.value = ProcessingState.Running(
            title = title,
            message = writeProgressEta.decorate(message),
            progressPercent = progressPercent?.coerceIn(0, 100),
        )
    }

    fun cutPrepared(
        sourceUri: String,
        sourceName: String,
        sessionPath: String,
        localPath: String?,
        durationMs: Long,
        thumbnailPaths: List<String>,
    ) {
        writeProgressEta.reset()
        _state.value = ProcessingState.CutPrepared(
            sourceUri = sourceUri,
            sourceName = sourceName,
            sessionPath = sessionPath,
            localPath = localPath,
            durationMs = durationMs,
            thumbnailPaths = thumbnailPaths,
        )
    }

    fun success(message: String) {
        writeProgressEta.reset()
        _state.value = ProcessingState.Success(message)
    }

    fun failure(message: String) {
        writeProgressEta.reset()
        _state.value = ProcessingState.Failure(message)
    }

    fun cancelled(message: String) {
        writeProgressEta.reset()
        _state.value = ProcessingState.Cancelled(message)
    }
}
