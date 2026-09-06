package app.clipforge

import app.clipforge.media.CutMode
import app.clipforge.media.MediaSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CutDraftStoreTest {
    @Test
    fun codecRoundTripsDraft() {
        val draft = CutDraft(
            sourceUri = "content://example/video?id=smb://nas/movies/test.mp4",
            sourceName = "テスト動画.mp4",
            sourceSizeBytes = 4_294_967_296L,
            durationMs = 180_000L,
            cutRanges = listOf(
                MediaSegment(1_250L, 9_000L),
                MediaSegment(40_000L, 55_500L),
            ),
            cutMode = CutMode.LOSSLESS,
        )

        assertEquals(draft, decodeCutDraft(encodeCutDraft(draft)))
    }

    @Test
    fun codecRejectsOutOfBoundsRange() {
        val raw = encodeCutDraft(
            CutDraft(
                sourceUri = "content://example/video",
                sourceName = "video.mp4",
                sourceSizeBytes = null,
                durationMs = 10_000L,
                cutRanges = emptyList(),
                cutMode = CutMode.SMART,
            ),
        )
        val broken = raw.substringBeforeLast('|') + "|9000:11000"

        assertNull(decodeCutDraft(broken))
    }

    @Test
    fun keySeparatesDifferentSources() {
        val first = cutDraftKey("content://example/a", "video.mp4")
        val second = cutDraftKey("content://example/b", "video.mp4")

        assertNotEquals(first, second)
        assertEquals(first, cutDraftKey("content://example/a", "video.mp4"))
    }
}
