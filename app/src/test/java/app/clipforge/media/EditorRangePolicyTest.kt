package app.clipforge.media

import org.junit.Assert.assertEquals
import org.junit.Test

class EditorRangePolicyTest {
    @Test
    fun `moving start inside range keeps end`() {
        assertEquals(
            MediaSegment(2_000, 5_000),
            rangeAfterSettingStart(10_000, 1_000, 5_000, 2_000),
        )
    }

    @Test
    fun `moving start past end swaps local interval`() {
        assertEquals(
            MediaSegment(5_000, 7_000),
            rangeAfterSettingStart(10_000, 1_000, 5_000, 7_000),
        )
    }

    @Test
    fun `moving end before start swaps local interval`() {
        assertEquals(
            MediaSegment(2_000, 4_000),
            rangeAfterSettingEnd(10_000, 4_000, 8_000, 2_000),
        )
    }

    @Test
    fun `equal markers become minimal valid interval`() {
        assertEquals(
            MediaSegment(5_000, 5_001),
            rangeAfterSettingStart(10_000, 1_000, 5_000, 5_000),
        )
        assertEquals(
            MediaSegment(4_000, 4_001),
            rangeAfterSettingEnd(10_000, 4_000, 8_000, 4_000),
        )
    }
}
