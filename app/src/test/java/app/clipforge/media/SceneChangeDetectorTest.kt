package app.clipforge.media

import org.junit.Assert.assertEquals
import org.junit.Test

class SceneChangeDetectorTest {
    @Test
    fun parsesSceneAndBlackMarkersWithWindowOffset() {
        val log = """
            [Parsed_showinfo_4 @ 0x1] n:1 pts:112 pts_time:1.250 pos:1234
            [blackdetect @ 0x2] black_start:2.500 black_end:2.800 black_duration:0.300
        """.trimIndent()

        assertEquals(
            listOf(
                SceneMarker(11_250L, SceneMarkerKind.SCENE_CHANGE),
                SceneMarker(12_500L, SceneMarkerKind.BLACK),
            ),
            parseSceneDetectionLog(log, 10_000L),
        )
    }

    @Test
    fun nearlyIdenticalMarkersAreDeduplicatedPerKind() {
        val log = """
            [Parsed_showinfo_4 @ 0x1] pts_time:1.000
            [Parsed_showinfo_4 @ 0x1] pts_time:1.050
            [blackdetect @ 0x2] black_start:1.040 black_end:1.200 black_duration:0.160
        """.trimIndent()

        assertEquals(
            listOf(
                SceneMarker(1_000L, SceneMarkerKind.SCENE_CHANGE),
                SceneMarker(1_040L, SceneMarkerKind.BLACK),
            ),
            parseSceneDetectionLog(log, 0L),
        )
    }
}
