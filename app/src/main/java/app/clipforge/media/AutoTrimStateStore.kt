package app.clipforge.media

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executors

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

    // Serialize snapshots so an older UI visibility write can never overwrite a newer analysis.
    private val persistenceExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "clipforge-auto-trim-state").apply { isDaemon = true }
    }

    @Synchronized
    fun begin(sessionPath: String) {
        val next = AutoTrimUiState(
            sessionPath = sessionPath,
            visible = true,
            running = true,
        )
        _state.value = next
        persist(next, wait = false)
    }

    @Synchronized
    fun ready(sessionPath: String, analysis: AutoTrimAnalysis) {
        val current = _state.value
        val next = AutoTrimUiState(
            sessionPath = sessionPath,
            visible = current.visible.takeIf { current.sessionPath == sessionPath } ?: true,
            running = false,
            analysis = analysis,
        )
        _state.value = next
        // Completion is valuable state. Do not report success before this snapshot is durable.
        persist(next, wait = true)
    }

    @Synchronized
    fun failure(sessionPath: String, message: String) {
        val current = _state.value
        val next = AutoTrimUiState(
            sessionPath = sessionPath,
            visible = current.visible.takeIf { current.sessionPath == sessionPath } ?: true,
            running = false,
            analysis = current.analysis.takeIf { current.sessionPath == sessionPath },
            error = message,
        )
        _state.value = next
        persist(next, wait = true)
    }

    @Synchronized
    fun cancelled(sessionPath: String) {
        val current = _state.value
        val next = AutoTrimUiState(
            sessionPath = sessionPath,
            visible = current.visible.takeIf { current.sessionPath == sessionPath } ?: true,
            running = false,
            analysis = current.analysis.takeIf { current.sessionPath == sessionPath },
            error = "自動解析をキャンセルしました",
        )
        _state.value = next
        persist(next, wait = true)
    }

    @Synchronized
    fun show(sessionPath: String): Boolean {
        val current = _state.value
        if (current.sessionPath != sessionPath) return false
        val next = current.copy(visible = true)
        _state.value = next
        persist(next, wait = false)
        return true
    }

    @Synchronized
    fun dismiss(sessionPath: String) {
        val current = _state.value
        if (current.sessionPath != sessionPath) return
        val next = current.copy(visible = false)
        _state.value = next
        persist(next, wait = false)
    }

    /**
     * Restores only the requested edit session. A live in-process state for the same session wins,
     * while a stale state from another editor can be replaced by the requested session snapshot.
     * Call this from an IO dispatcher.
     */
    fun restore(sessionPath: String): Boolean {
        synchronized(this) {
            val current = _state.value
            if (current.sessionPath == sessionPath && current.hasPayload()) return true
        }
        val restored = persistenceExecutor.submit<AutoTrimUiState?> {
            AutoTrimStatePersistence.load(sessionPath)
        }.get() ?: return false
        synchronized(this) {
            val current = _state.value
            if (current.sessionPath == sessionPath && current.hasPayload()) return true
            _state.value = restored
        }
        return true
    }

    @Synchronized
    internal fun resetForTest() {
        _state.value = AutoTrimUiState()
    }

    private fun AutoTrimUiState.hasPayload(): Boolean =
        running || analysis != null || error != null

    private fun persist(state: AutoTrimUiState, wait: Boolean) {
        val future = persistenceExecutor.submit { AutoTrimStatePersistence.save(state) }
        if (wait) future.get()
    }
}
