package app.clipforge.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpdateReleaseParserTest {
    @Test
    fun parsesClipForgeDebugAsset() {
        val release = UpdateReleaseParser.parseAsset(
            "ClipForge-0.2.0-b123456-debug.apk",
            "https://example.invalid/app.apk"
        )

        requireNotNull(release)
        assertEquals("0.2.0", release.versionName)
        assertEquals(123456, release.buildNumber)
    }

    @Test
    fun ignoresUnrelatedAssets() {
        assertNull(
            UpdateReleaseParser.parseAsset(
                "not-clipforge.apk",
                "https://example.invalid/app.apk"
            )
        )
    }

    @Test
    fun selectsNewestBuildAboveInstalled() {
        val newest = UpdateReleaseParser.newest(
            listOf(
                "ClipForge-0.1.0-b100-debug.apk" to "https://example.invalid/100.apk",
                "ClipForge-0.1.0-b120-debug.apk" to "https://example.invalid/120.apk",
                "ClipForge-0.1.0-b110-debug.apk" to "https://example.invalid/110.apk"
            ),
            installedBuild = 105
        )

        requireNotNull(newest)
        assertEquals(120, newest.buildNumber)
    }

    @Test
    fun returnsNullWhenInstalledBuildIsNewest() {
        assertNull(
            UpdateReleaseParser.newest(
                listOf(
                    "ClipForge-0.1.0-b100-debug.apk" to "https://example.invalid/100.apk"
                ),
                installedBuild = 100
            )
        )
    }
}
