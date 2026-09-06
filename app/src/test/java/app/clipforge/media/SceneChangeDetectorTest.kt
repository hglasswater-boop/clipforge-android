package app.clipforge.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    @Test
    fun longAutoScansUseCoarseKeyframeMode() {
        assertEquals(
            SceneScanMode.COARSE,
            resolvedSceneScanMode(SceneScanMode.AUTO, 10 * 60_000L),
        )
    }

    @Test
    fun shortAutoScansKeepPreciseMode() {
        assertEquals(
            SceneScanMode.PRECISE,
            resolvedSceneScanMode(SceneScanMode.AUTO, 30_000L),
        )
    }

    @Test
    fun coarseFilterUsesLowerResolutionAndStricterSceneThreshold() {
        val filter = sceneVideoFilter(SceneScanMode.COARSE)
        assertTrue(filter.contains("scale=240"))
        assertTrue(filter.contains("gt(scene,0.40)"))
    }

    @Test
    fun preciseFilterGraphKeepsAContinuousProgressBranch() {
        val graph = preciseSceneFilterGraph()

        assertTrue(graph.startsWith("[0:V:0]"))
        assertTrue(graph.contains("split=2[progress_src][scene_src]"))
        assertTrue(graph.contains("[progress_src]fps=1[progress]"))
        assertTrue(graph.contains("[scene_src]select='gt(scene,0.35)',showinfo[changes]"))
    }
}
