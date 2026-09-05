package app.clipforge.media

import org.junit.Assert.assertEquals
import org.junit.Test

class FdSmartConcatScriptTest {
    @Test
    fun consumesIndependentFdForEverySourcePart() {
        val script = fdSmartConcatScript(
            fds = listOf(51, 52),
            parts = listOf(
                SmartConcatInput.SourceSegment(MediaSegment(0L, 1_000L)),
                SmartConcatInput.RenderedFile("/tmp/boundary.mkv"),
                SmartConcatInput.SourceSegment(MediaSegment(2_000L, 3_000L)),
            ),
        )

        assertEquals(
            """ffconcat version 1.0
file 'fd:'
option fd 51
inpoint 0.000
outpoint 1.000
file '/tmp/boundary.mkv'
file 'fd:'
option fd 52
inpoint 2.000
outpoint 3.000
""",
            script,
        )
    }

    @Test
    fun allowsNoDescriptorsWhenEveryPartIsRendered() {
        val script = fdSmartConcatScript(
            fds = emptyList(),
            parts = listOf(
                SmartConcatInput.RenderedFile("/tmp/start.mkv"),
                SmartConcatInput.RenderedFile("/tmp/end.mkv"),
            ),
        )

        assertEquals(
            """ffconcat version 1.0
file '/tmp/start.mkv'
file '/tmp/end.mkv'
""",
            script,
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsDescriptorCountThatDoesNotMatchSourceParts() {
        fdSmartConcatScript(
            fds = listOf(51),
            parts = listOf(
                SmartConcatInput.SourceSegment(MediaSegment(0L, 1_000L)),
                SmartConcatInput.SourceSegment(MediaSegment(2_000L, 3_000L)),
            ),
        )
    }
}
