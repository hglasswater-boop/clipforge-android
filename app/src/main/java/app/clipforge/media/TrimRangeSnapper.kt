package app.clipforge.media

import kotlin.math.abs

data class SnappedTrimRange(
    val startMs: Long,
    val endMs: Long
)

object TrimRangeSnapper {
    fun snap(
        durationMs: Long,
        keyframesMs: List<Long>,
        requestedStartMs: Long,
        requestedEndMs: Long
    ): SnappedTrimRange {
        require(durationMs > 0L) { "durationMs must be positive" }

        val requestedStart = requestedStartMs.coerceIn(0L, durationMs - 1L)
        val requestedEnd = requestedEndMs.coerceIn(1L, durationMs)
        val validKeyframes = keyframesMs.filter { it in 0 until durationMs }.distinct().sorted()

        if (validKeyframes.isEmpty()) {
            val start = requestedStart
            val end = requestedEnd.coerceAtLeast(start + 1L).coerceAtMost(durationMs)
            return SnappedTrimRange(start, end)
        }

        val start = nearest(validKeyframes, requestedStart)
        val endCandidates = (validKeyframes.filter { it > 0L } + durationMs).distinct().sorted()
        var end = nearest(endCandidates, requestedEnd)
        if (end <= start) {
            end = endCandidates.firstOrNull { it > start } ?: durationMs
        }
        if (end <= start) {
            val fallbackStart = validKeyframes.lastOrNull { it < durationMs } ?: 0L
            return SnappedTrimRange(fallbackStart, durationMs)
        }
        return SnappedTrimRange(start, end)
    }

    private fun nearest(candidates: List<Long>, target: Long): Long =
        candidates.minByOrNull { abs(it - target) } ?: target
}
