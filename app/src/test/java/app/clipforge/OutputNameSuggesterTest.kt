package app.clipforge

import org.junit.Assert.assertEquals
import org.junit.Test

class OutputNameSuggesterTest {
    @Test
    fun `concat output uses first mp4 filename`() {
        assertEquals("vacation_01_concat.mp4", suggestedConcatName("vacation_01.mp4"))
    }

    @Test
    fun `concat output preserves mkv extension`() {
        assertEquals("episode_concat.mkv", suggestedConcatName("episode.mkv"))
    }

    @Test
    fun `concat output falls back to mkv when extension is unknown`() {
        assertEquals("video_concat.mkv", suggestedConcatName("video"))
    }
}
