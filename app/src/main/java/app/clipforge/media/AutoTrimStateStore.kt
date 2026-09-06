package app.clipforge.media

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executors

data class AutoTrimUiState(
    val sessionPath: String? = null,
    val visible: Boolean = false,
    val running: Boolean = false,
    val progress: AutoTrimProgress? = null,
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
    private var lastDurableProgressPercent = -PROGRESS_PERSIST_STEP
    private var lastDurableProgressPhase: AutoTrimPhase? = null

    @Synchronized
    fun begin(sessionPath: String) {
        val current = _state.value
        val next = AutoTrimUiState(
            sessionPath = sessionPath,
            visible = current.visible.takeIf {
                current.sessionPath == sessionPath && current.hasPayload()
            } ?: true,
            running = true,
            progress = AutoTrimProgress(
                phase = AutoTrimPhase.PREPARING,
                overallPercent = 0,
                phasePercent = 0,
                elapsedMs = 0L,
                remainingMs = null,
            ),
        )
        _state.value = next
        lastDurableProgressPercent = 0
        lastDurableProgressPhase = AutoTrimPhase.PREPARING
        persist(next, wait = false)
    }

    @Synchronized
    fun updateProgress(sessionPath: String, progress: AutoTrimProgress) {
        val current = _state.value
        if (current.sessionPath != sessionPath || !current.running) return
        val previous = current.progress
        if (previous != null && progress.overallPercent < previous.overallPercent) return

        val next = current.copy(progress = progress)
        _state.value = next

        val shouldPersist =
            progress.phase != lastDurableProgressPhase ||
                progress.overallPercent - lastDurableProgressPercent >= PROGRESS_PERSIST_STEP ||
                progress.overallPercent >= 99
        if (shouldPersist) {
            lastDurableProgressPercent = progress.overallPercent
            lastDurableProgressPhase = progress.phase
            persist(next, wait = false)
        }
    }

    @Synchronized
    fun ready(sessionPath: String, analysis: AutoTrimAnalysis) {
        val current = _state.value
        val next = AutoTrimUiState(
            sessionPath = sessionPath,
            visible = current.visible.takeIf { current.sessionPath == sessionPath } ?: true,
            running = false,
            progress = AutoTrimProgress(
                phase = AutoTrimPhase.COMPLETE,
                overallPercent = 100,
                phasePercent = 100,
                elapsedMs = current.progress?.elapsedMs ?: 0L,
                remainingMs = 0L,
            ),
            analysis = analysis,
        )
        _state.value = next
        lastDurableProgressPercent = 100
        lastDurableProgressPhase = AutoTrimPhase.COMPLETE
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
            progress = current.progress.takeIf { current.sessionPath == sessionPath },
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
            progress = current.progress.takeIf { current.sessionPath == sessionPath },
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
            lastDurableProgressPercent = restored.progress?.overallPercent ?: -PROGRESS_PERSIST_STEP
            lastDurableProgressPhase = restored.progress?.phase
        }
        return true
    }

    @Synchronized
    internal fun resetForTest() {
        // Flush pending snapshots before a test deletes its temporary session directory.
        persistenceExecutor.submit { }.get()
        _state.value = AutoTrimUiState()
        lastDurableProgressPercent = -PROGRESS_PERSIST_STEP
        lastDurableProgressPhase = null
    }

    private fun AutoTrimUiState.hasPayload(): Boolean =
        running || progress != null || analysis != null || error != null

    private fun persist(state: AutoTrimUiState, wait: Boolean) {
        val future = persistenceExecutor.submit { AutoTrimStatePersistence.save(state) }
        if (wait) future.get()
    }

    private const val PROGRESS_PERSIST_STEP = 5
}
