package app.clipforge.media

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class AutoTrimPhase(val label: String) {
    PREPARING("解析を準備中"),
    START_SCENE("先頭の映像切替を解析中"),
    END_SCENE("末尾の映像切替を解析中"),
    START_AUDIO("先頭の音声変化を解析中"),
    END_AUDIO("末尾の音声変化を解析中"),
    VISUAL_FINGERPRINT("映像fingerprintを作成中"),
    KNOWN_CLIP_MATCH("既知クリップと照合中"),
    RANKING("候補を評価中"),
    COMPLETE("解析完了"),
}

data class AutoTrimProgress(
    val phase: AutoTrimPhase = AutoTrimPhase.PREPARING,
    val percent: Int = 0,
    val startedAtElapsedMs: Long = 0L,
    val updatedAtElapsedMs: Long = 0L,
) {
    fun elapsedMs(nowElapsedMs: Long = android.os.SystemClock.elapsedRealtime()): Long =
        if (startedAtElapsedMs <= 0L) 0L else (nowElapsedMs - startedAtElapsedMs).coerceAtLeast(0L)

    fun estimatedRemainingMs(nowElapsedMs: Long = android.os.SystemClock.elapsedRealtime()): Long? {
        val safePercent = percent.coerceIn(0, 100)
        if (safePercent <= 0 || safePercent >= 100) return null
        val elapsed = elapsedMs(nowElapsedMs)
        if (elapsed < MIN_ETA_SAMPLE_MS) return null
        val totalEstimate = elapsed.toDouble() * 100.0 / safePercent.toDouble()
        return (totalEstimate - elapsed).toLong().coerceAtLeast(0L)
    }

    private companion object {
        const val MIN_ETA_SAMPLE_MS = 3_000L
    }
}

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

    fun begin(sessionPath: String, nowElapsedMs: Long = android.os.SystemClock.elapsedRealtime()) {
        _state.value = AutoTrimUiState(
            sessionPath = sessionPath,
            visible = true,
            running = true,
            progress = AutoTrimProgress(
                phase = AutoTrimPhase.PREPARING,
                percent = 0,
                startedAtElapsedMs = nowElapsedMs,
                updatedAtElapsedMs = nowElapsedMs,
            ),
        )
    }

    fun progress(
        sessionPath: String,
        phase: AutoTrimPhase,
        percent: Int,
        nowElapsedMs: Long = android.os.SystemClock.elapsedRealtime(),
    ) {
        val current = _state.value
        if (current.sessionPath != sessionPath || !current.running) return
        val previous = current.progress
        val safePercent = percent.coerceIn(previous?.percent ?: 0, 99)
        _state.value = current.copy(
            progress = AutoTrimProgress(
                phase = phase,
                percent = safePercent,
                startedAtElapsedMs = previous?.startedAtElapsedMs?.takeIf { it > 0L } ?: nowElapsedMs,
                updatedAtElapsedMs = nowElapsedMs,
            ),
        )
    }

    fun ready(sessionPath: String, analysis: AutoTrimAnalysis) {
        val current = _state.value
        val now = android.os.SystemClock.elapsedRealtime()
        _state.value = AutoTrimUiState(
            sessionPath = sessionPath,
            visible = true,
            running = false,
            progress = AutoTrimProgress(
                phase = AutoTrimPhase.COMPLETE,
                percent = 100,
                startedAtElapsedMs = current.progress?.startedAtElapsedMs ?: now,
                updatedAtElapsedMs = now,
            ),
            analysis = analysis,
        )
    }

    fun failure(sessionPath: String, message: String) {
        val current = _state.value
        _state.value = AutoTrimUiState(
            sessionPath = sessionPath,
            visible = true,
            running = false,
            progress = current.progress.takeIf { current.sessionPath == sessionPath },
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
