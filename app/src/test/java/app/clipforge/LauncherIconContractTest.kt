package app.clipforge

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LauncherIconContractTest {
    private val main = File("src/main")

    @Test
    fun launcherIconUsesCanonicalSimpleResources() {
        val manifest = main.resolve("AndroidManifest.xml").readText()
        assertTrue(manifest.contains("android:icon=\"@mipmap/ic_launcher\""))
        assertTrue(manifest.contains("android:roundIcon=\"@mipmap/ic_launcher_round\""))
        assertFalse(manifest.contains("_v2"))

        val foreground = main.resolve("res/drawable/ic_launcher_foreground.xml")
        assertTrue(foreground.isFile)
        val foregroundXml = foreground.readText()
        assertTrue(foregroundXml.contains("android:fillType=\"evenOdd\""))
        assertTrue(foregroundXml.contains("M68,45 L68,63 L81,54 Z"))
        assertFalse(main.resolve("res/drawable/ic_launcher_monochrome.xml").exists())
        assertFalse(main.resolve("res/drawable/ic_launcher_monochrome_v2.xml").exists())

        val adaptive26 = main.resolve("res/mipmap-anydpi-v26/ic_launcher.xml").readText()
        assertTrue(adaptive26.contains("@drawable/ic_launcher_foreground"))
        assertFalse(adaptive26.contains("_v2"))

        val adaptive33 = main.resolve("res/mipmap-anydpi-v33/ic_launcher.xml").readText()
        assertTrue(adaptive33.contains("<foreground android:drawable=\"@drawable/ic_launcher_foreground\""))
        assertTrue(adaptive33.contains("<monochrome android:drawable=\"@drawable/ic_launcher_foreground\""))
        assertFalse(adaptive33.contains("_v2"))
    }

    @Test
    fun obsoleteLauncherArtworkIsRemoved() {
        val obsolete = listOf(
            "res/drawable-nodpi/clipforge_launcher.webp",
            "res/drawable-nodpi/clipforge_launcher_v2.webp",
            "res/mipmap-anydpi/ic_launcher.xml",
            "res/mipmap-anydpi/ic_launcher_round.xml",
            "res/mipmap-anydpi/ic_launcher_v2.xml",
            "res/mipmap-anydpi/ic_launcher_round_v2.xml",
            "res/mipmap-anydpi-v26/ic_launcher_v2.xml",
            "res/mipmap-anydpi-v26/ic_launcher_round_v2.xml",
            "res/mipmap-anydpi-v33/ic_launcher_v2.xml",
            "res/mipmap-anydpi-v33/ic_launcher_round_v2.xml"
        )
        obsolete.forEach { path ->
            assertFalse("obsolete launcher resource remains: $path", main.resolve(path).exists())
        }
    }
}
