package app.clipforge.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class OutputDestinationPolicyTest {
    @Test
    fun localDestinationIsAlwaysAvailable() {
        assertEquals(
            listOf(OutputDestinationTarget.LOCAL),
            availableOutputDestinations(xFilesAvailable = false),
        )
    }

    @Test
    fun xFilesAddsSmbWithoutReplacingLocalDestination() {
        assertEquals(
            listOf(OutputDestinationTarget.LOCAL, OutputDestinationTarget.XFILES_SMB),
            availableOutputDestinations(xFilesAvailable = true),
        )
    }
}
