package app.clipforge.ui.theme

import android.app.Activity
import android.content.ContextWrapper
import android.graphics.Color
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.expressiveLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

internal enum class ThemeSchemeSource {
    DynamicLight,
    DynamicDark,
    ExpressiveLight,
    Dark,
}

internal fun selectThemeSchemeSource(
    sdkInt: Int,
    darkTheme: Boolean,
    dynamicColor: Boolean,
): ThemeSchemeSource = when {
    dynamicColor && sdkInt >= Build.VERSION_CODES.S && darkTheme -> ThemeSchemeSource.DynamicDark
    dynamicColor && sdkInt >= Build.VERSION_CODES.S -> ThemeSchemeSource.DynamicLight
    darkTheme -> ThemeSchemeSource.Dark
    else -> ThemeSchemeSource.ExpressiveLight
}

// Keep edge-to-edge system bars visually aligned with XFiles on Android 8-9.
private val LegacyLightNavigationBarScrim = Color.argb(0xE6, 0xFF, 0xFF, 0xFF)
private val LegacyDarkNavigationBarScrim = Color.argb(0x80, 0x1B, 0x1B, 0x1B)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ClipForgeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = when (
        selectThemeSchemeSource(
            sdkInt = Build.VERSION.SDK_INT,
            darkTheme = darkTheme,
            dynamicColor = dynamicColor,
        )
    ) {
        ThemeSchemeSource.DynamicLight -> dynamicLightColorScheme(context)
        ThemeSchemeSource.DynamicDark -> dynamicDarkColorScheme(context)
        ThemeSchemeSource.ExpressiveLight -> expressiveLightColorScheme()
        ThemeSchemeSource.Dark -> darkColorScheme()
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val activity = generateSequence(view.context) { (it as? ContextWrapper)?.baseContext }
                .filterIsInstance<Activity>()
                .firstOrNull()
                ?: return@SideEffect

            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                @Suppress("DEPRECATION")
                activity.window.navigationBarColor = if (darkTheme) {
                    LegacyDarkNavigationBarScrim
                } else {
                    LegacyLightNavigationBarScrim
                }
            }

            val controller = WindowCompat.getInsetsController(activity.window, view)
            controller.isAppearanceLightStatusBars = !darkTheme
            controller.isAppearanceLightNavigationBars = !darkTheme
        }
    }

    MaterialExpressiveTheme(
        colorScheme = colorScheme,
        motionScheme = MotionScheme.expressive(),
        content = content,
    )
}
