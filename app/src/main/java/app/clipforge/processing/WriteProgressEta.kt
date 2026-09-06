package app.clipforge.processing

import kotlin.math.roundToLong

/**
 * Estimates remaining time only for write phases whose message contains their own 0..100 progress.
 * Stage-level percentages are intentionally ignored because smart-cut phases have different costs.
 */
internal class WriteProgressEta(
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    private data class Sample(val timeMs: Long, val percent: Int)

    private val samples = ArrayDeque<Sample>()
    private var activePhase: String? = null
    private var smoothedEtaMs: Double? = null

    @Synchronized
    fun decorate(message: String): String {
        val phase = phaseOf(message)
        val percent = WRITE_PERCENT.find(message)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?.coerceIn(0, 100)

        if (phase == null || percent == null) {
            reset()
            return message
        }
        if (phase != activePhase) {
            resetSamples()
            activePhase = phase
        }

        val etaMs = update(percent, nowMs()) ?: return message
        return "$message ・ 残り ${formatRemainingTime(etaMs)}"
    }

    @Synchronized
    fun reset() {
        activePhase = null
        resetSamples()
    }

    private fun update(percent: Int, now: Long): Long? {
        if (percent <= 0 || percent >= 100) return null

        val last = samples.lastOrNull()
        if (last != null && percent < last.percent) {
            resetSamples()
        }

        val currentLast = samples.lastOrNull()
        if (currentLast == null || percent > currentLast.percent) {
            samples.addLast(Sample(now, percent))
        }

        while (samples.size > 2 && now - samples.first().timeMs > WINDOW_MS) {
            samples.removeFirst()
        }
        if (samples.size < 2) return smoothedEtaMs?.roundToLong()

        val first = samples.first()
        val newest = samples.last()
        val elapsedMs = newest.timeMs - first.timeMs
        val progressed = newest.percent - first.percent
        if (elapsedMs < MIN_SAMPLE_MS || progressed < MIN_PROGRESS_POINTS) {
            return smoothedEtaMs?.roundToLong()
        }

        val pointsPerMs = progressed.toDouble() / elapsedMs.toDouble()
        if (!pointsPerMs.isFinite() || pointsPerMs <= 0.0) return smoothedEtaMs?.roundToLong()
        val rawEtaMs = (100 - newest.percent).toDouble() / pointsPerMs
        if (!rawEtaMs.isFinite() || rawEtaMs <= 0.0 || rawEtaMs > MAX_ETA_MS) return null

        smoothedEtaMs = smoothedEtaMs
            ?.let { previous -> previous * 0.65 + rawEtaMs * 0.35 }
            ?: rawEtaMs
        return smoothedEtaMs?.roundToLong()
    }

    private fun resetSamples() {
        samples.clear()
        smoothedEtaMs = null
    }

    private fun phaseOf(message: String): String? = when {
        message.startsWith("スマートカットを書き出し中 ") -> "smart-write"
        message.startsWith("無劣化で書き出し中 ") -> "lossless-write"
        message.startsWith("キーフレーム一致のため無劣化で書き出し中 ") -> "keyframe-lossless-write"
        else -> null
    }

    companion object {
        private val WRITE_PERCENT = Regex("(\\d{1,3})%")
        private const val MIN_SAMPLE_MS = 3_000L
        private const val MIN_PROGRESS_POINTS = 2
        private const val WINDOW_MS = 20_000L
        private const val MAX_ETA_MS = 24L * 60L * 60L * 1000L
    }
}

internal fun formatRemainingTime(milliseconds: Long): String {
    val seconds = ((milliseconds.coerceAtLeast(0L) + 500L) / 1_000L).coerceAtLeast(1L)
    if (seconds < 60L) return "約${seconds}秒"

    val minutes = seconds / 60L
    val remainingSeconds = seconds % 60L
    if (minutes < 60L) {
        return if (remainingSeconds >= 10L) {
            "約${minutes}分${remainingSeconds}秒"
        } else {
            "約${minutes}分"
        }
    }

    val hours = minutes / 60L
    val remainingMinutes = minutes % 60L
    return if (remainingMinutes > 0L) "約${hours}時間${remainingMinutes}分" else "約${hours}時間"
}
