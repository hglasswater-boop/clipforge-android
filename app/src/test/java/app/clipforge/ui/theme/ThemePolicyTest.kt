package app.clipforge.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Test

class ThemePolicyTest {
    @Test
    fun `android 12 dynamic light uses dynamic light`() {
        assertEquals(
            ThemeSchemeSource.DynamicLight,
            selectThemeSchemeSource(sdkInt = 31, darkTheme = false, dynamicColor = true),
        )
    }

    @Test
    fun `android 12 dynamic dark uses dynamic dark`() {
        assertEquals(
            ThemeSchemeSource.DynamicDark,
            selectThemeSchemeSource(sdkInt = 31, darkTheme = true, dynamicColor = true),
        )
    }

    @Test
    fun `pre android 12 light falls back to expressive light`() {
        assertEquals(
            ThemeSchemeSource.ExpressiveLight,
            selectThemeSchemeSource(sdkInt = 30, darkTheme = false, dynamicColor = true),
        )
    }

    @Test
    fun `dynamic disabled light falls back to expressive light`() {
        assertEquals(
            ThemeSchemeSource.ExpressiveLight,
            selectThemeSchemeSource(sdkInt = 36, darkTheme = false, dynamicColor = false),
        )
    }

    @Test
    fun `dynamic disabled dark falls back to dark`() {
        assertEquals(
            ThemeSchemeSource.Dark,
            selectThemeSchemeSource(sdkInt = 36, darkTheme = true, dynamicColor = false),
        )
    }
}
