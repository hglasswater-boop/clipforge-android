package app.clipforge.media

import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.roundToLong

enum class AutoTrimPhase(
    val label: String,
    internal val startPercent: Double,
    internal val endPercent: Double,
) {
    PREPARING("解析を準備中", 0.0, 2.0),
    START_AUDIO("先頭の音声変化を解析中", 2.0, 17.0),
    END_AUDIO("末尾の音声変化を解析中", 17.0, 32.0),
    VISUAL_FINGERPRINT("映像を疎く探索中", 32.0, 62.0),
    KNOWN_CLIP_MATCH("既知クリップと照合中", 62.0, 68.0),
    START_SCENE("先頭候補を精密解析中", 68.0, 82.0),
    END_SCENE("末尾候補を精密解析中", 82.0, 96.0),
    RANKING("境界候補を評価中", 96.0, 100.0),
    COMPLETE("解析完了", 100.0, 100.0),
}

data class AutoTrimProgress(
    val phase: AutoTrimPhase,
    val overallPercent: Int,
    val phasePercent: Int,
    val elapsedMs: Long,
    val remainingMs: Long?,
)

/**
 * Converts actual per-phase work into monotonic overall progress and a smoothed ETA.
 * No timer-driven fake progress is used: phase fractions come from FFmpeg timestamps or
 * the number of sparse visual frames that have actually been sampled.
 */
internal class AutoTrimProgressTracker(
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    private val startedAtMs = nowMs()
    private var lastOverallPercent = 0
    private var smoothedRemainingMs: Double? = null

    @Synchronized
    fun update(phase: AutoTrimPhase, phaseFraction: Double): AutoTrimProgress {
        val fraction = phaseFraction.coerceIn(0.0, 1.0)
        val calculated = if (phase == AutoTrimPhase.COMPLETE) {
            100
        } else {
            (phase.startPercent + (phase.endPercent - phase.startPercent) * fraction)
                .roundToInt()
                .coerceIn(0, 100)
        }
        val overall = max(lastOverallPercent, calculated)
        lastOverallPercent = overall

        val elapsed = (nowMs() - startedAtMs).coerceAtLeast(0L)
        val remaining = estimateRemaining(elapsed, overall)
        return AutoTrimProgress(
            phase = phase,
            overallPercent = overall,
            phasePercent = (fraction * 100.0).roundToInt().coerceIn(0, 100),
            elapsedMs = elapsed,
            remainingMs = remaining,
        )
    }

    private fun estimateRemaining(elapsedMs: Long, overallPercent: Int): Long? {
        if (overallPercent >= 100) {
            smoothedRemainingMs = 0.0
            return 0L
        }
        if (overallPercent < MIN_PERCENT_FOR_ETA || elapsedMs < MIN_ELAPSED_FOR_ETA_MS) return null

        val raw = elapsedMs.toDouble() * (100.0 - overallPercent) / overallPercent.toDouble()
        if (!raw.isFinite() || raw < 0.0) return null
        val capped = raw.coerceAtMost(MAX_ETA_MS.toDouble())
        val previous = smoothedRemainingMs
        val smoothed = if (previous == null) {
            capped
        } else {
            previous * (1.0 - ETA_SMOOTHING) + capped * ETA_SMOOTHING
        }
        smoothedRemainingMs = smoothed
        return smoothed.roundToLong().coerceAtLeast(0L)
    }

    private companion object {
        const val MIN_PERCENT_FOR_ETA = 5
        const val MIN_ELAPSED_FOR_ETA_MS = 1_500L
        const val MAX_ETA_MS = 24L * 60L * 60L * 1_000L
        const val ETA_SMOOTHING = 0.25
    }
}
