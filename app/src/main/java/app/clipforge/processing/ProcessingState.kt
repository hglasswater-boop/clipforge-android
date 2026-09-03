package app.clipforge.processing

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface ProcessingState {
    data object Idle : ProcessingState
    data class Running(val title: String, val message: String) : ProcessingState
    data class Success(val message: String) : ProcessingState
    data class Failure(val message: String) : ProcessingState
}

object ProcessingStateStore {
    private val _state = MutableStateFlow<ProcessingState>(ProcessingState.Idle)
    val state = _state.asStateFlow()

    fun running(title: String, message: String) {
        _state.value = ProcessingState.Running(title, message)
    }

    fun success(message: String) {
        _state.value = ProcessingState.Success(message)
    }

    fun failure(message: String) {
        _state.value = ProcessingState.Failure(message)
    }
}
