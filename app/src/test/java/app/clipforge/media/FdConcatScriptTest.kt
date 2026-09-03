package app.clipforge.media

import org.junit.Assert.assertEquals
import org.junit.Test

class FdConcatScriptTest {
    @Test
    fun `creates one fd option per concat input`() {
        val script = fdConcatScript(
            listOf(
                NamedMediaDescriptor(fd = 140, displayName = "one.mp4"),
                NamedMediaDescriptor(fd = 141, displayName = "two.mp4"),
            ),
        )

        assertEquals(
            """ffconcat version 1.0
file 'fd:'
option fd 140
file 'fd:'
option fd 141
""",
            script,
        )
    }
}
