package app.clipforge.media

import org.junit.Assert.assertEquals
import org.junit.Test

class SmartCutPlanTest {
    @Test
    fun `sync aligned range stays fully lossless`() {
        val parts = planSmartCutSegment(
            segment = MediaSegment(1_000, 5_000),
            durationMs = 10_000,
            startPreviousSyncMs = 1_000,
            startNextSyncMs = 1_000,
            endPreviousSyncMs = 5_000,
            endNextSyncMs = 5_000,
        )

        assertEquals(listOf(SmartCutPart.Copy(MediaSegment(1_000, 5_000))), parts)
    }

    @Test
    fun `arbitrary boundaries reencode only edge GOP fragments`() {
        val parts = planSmartCutSegment(
            segment = MediaSegment(1_200, 5_700),
            durationMs = 10_000,
            startPreviousSyncMs = 1_000,
            startNextSyncMs = 2_000,
            endPreviousSyncMs = 5_000,
            endNextSyncMs = 6_000,
        )

        assertEquals(
            listOf(
                SmartCutPart.Reencode(MediaSegment(1_200, 2_000)),
                SmartCutPart.Copy(MediaSegment(2_000, 5_000)),
                SmartCutPart.Reencode(MediaSegment(5_000, 5_700)),
            ),
            parts,
        )
    }

    @Test
    fun `only arbitrary start reencodes head then copies`() {
        val parts = planSmartCutSegment(
            segment = MediaSegment(1_250, 6_000),
            durationMs = 10_000,
            startPreviousSyncMs = 1_000,
            startNextSyncMs = 2_000,
            endPreviousSyncMs = 6_000,
            endNextSyncMs = 6_000,
        )

        assertEquals(
            listOf(
                SmartCutPart.Reencode(MediaSegment(1_250, 2_000)),
                SmartCutPart.Copy(MediaSegment(2_000, 6_000)),
            ),
            parts,
        )
    }

    @Test
    fun `only arbitrary end copies then reencodes tail`() {
        val parts = planSmartCutSegment(
            segment = MediaSegment(2_000, 6_750),
            durationMs = 10_000,
            startPreviousSyncMs = 2_000,
            startNextSyncMs = 2_000,
            endPreviousSyncMs = 6_000,
            endNextSyncMs = 7_000,
        )

        assertEquals(
            listOf(
                SmartCutPart.Copy(MediaSegment(2_000, 6_000)),
                SmartCutPart.Reencode(MediaSegment(6_000, 6_750)),
            ),
            parts,
        )
    }

    @Test
    fun `range inside one GOP is reencoded as one small piece`() {
        val parts = planSmartCutSegment(
            segment = MediaSegment(1_200, 1_800),
            durationMs = 10_000,
            startPreviousSyncMs = 1_000,
            startNextSyncMs = 2_000,
            endPreviousSyncMs = 1_000,
            endNextSyncMs = 2_000,
        )

        assertEquals(listOf(SmartCutPart.Reencode(MediaSegment(1_200, 1_800))), parts)
    }

    @Test
    fun `millisecond rounding near sync frame remains lossless`() {
        val parts = planSmartCutSegment(
            segment = MediaSegment(1_001, 4_999),
            durationMs = 10_000,
            startPreviousSyncMs = 1_000,
            startNextSyncMs = 1_000,
            endPreviousSyncMs = 5_000,
            endNextSyncMs = 5_000,
        )

        assertEquals(listOf(SmartCutPart.Copy(MediaSegment(1_001, 4_999))), parts)
    }

    @Test
    fun `source start and end are safe without forced boundary encode`() {
        val parts = planSmartCutSegment(
            segment = MediaSegment(0, 10_000),
            durationMs = 10_000,
            startPreviousSyncMs = null,
            startNextSyncMs = 1_000,
            endPreviousSyncMs = 9_000,
            endNextSyncMs = null,
        )

        assertEquals(listOf(SmartCutPart.Copy(MediaSegment(0, 10_000))), parts)
    }
}
