package app.clipforge.ui

internal enum class OutputDestinationTarget {
    LOCAL,
    XFILES_SMB,
}

internal fun availableOutputDestinations(xFilesAvailable: Boolean): List<OutputDestinationTarget> =
    if (xFilesAvailable) {
        listOf(OutputDestinationTarget.LOCAL, OutputDestinationTarget.XFILES_SMB)
    } else {
        listOf(OutputDestinationTarget.LOCAL)
    }
