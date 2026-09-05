package app.clipforge.media

import org.junit.Assert.assertEquals
import org.junit.Test

class FdSegmentConcatScriptTest {
    @Test
    fun writesOneSeekableFdEntryPerKeptSegment() {
        val script = fdSegmentConcatScript(
            fd = 42,
            segments = listOf(
                MediaSegment(0L, 1_000L),
                MediaSegment(2_500L, 4_000L),
            ),
        )

        assertEquals(
            """ffconcat version 1.0
file 'fd:'
option fd 42
inpoint 0.000
outpoint 1.000
file 'fd:'
option fd 42
inpoint 2.500
outpoint 4.000
""",
            script,
        )
    }
}
