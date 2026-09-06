package app.clipforge.media

import android.content.Context

class AutoTrimRangeSettings(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun loadEdgeWindowMs(): Long = normalizeAutoTrimEdgeWindowMs(
        preferences.getLong(KEY_EDGE_WINDOW_MS, DEFAULT_EDGE_WINDOW_MS),
    )

    fun saveEdgeWindowMs(value: Long) {
        preferences.edit()
            .putLong(KEY_EDGE_WINDOW_MS, normalizeAutoTrimEdgeWindowMs(value))
            .apply()
    }

    companion object {
        const val ONE_MINUTE_MS = 60_000L
        const val DEFAULT_EDGE_WINDOW_MS = 5 * ONE_MINUTE_MS
        val OPTIONS_MS = listOf(
            1 * ONE_MINUTE_MS,
            3 * ONE_MINUTE_MS,
            5 * ONE_MINUTE_MS,
            10 * ONE_MINUTE_MS,
        )

        private const val PREFERENCES_NAME = "clipforge_auto_trim_settings"
        private const val KEY_EDGE_WINDOW_MS = "edgeWindowMs"
    }
}

internal fun normalizeAutoTrimEdgeWindowMs(value: Long): Long =
    AutoTrimRangeSettings.OPTIONS_MS.minByOrNull { option -> kotlin.math.abs(option - value) }
        ?: AutoTrimRangeSettings.DEFAULT_EDGE_WINDOW_MS
