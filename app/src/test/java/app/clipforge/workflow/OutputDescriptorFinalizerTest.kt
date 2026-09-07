package app.clipforge.workflow

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test

class OutputDescriptorFinalizerTest {
    @Test
    fun closeSuccessReturnsNormally() {
        var closed = false

        closeOutputOrThrow { closed = true }

        assertEquals(true, closed)
    }

    @Test
    fun closeFailureIsPropagatedAsExportFailure() {
        val cause = IOException("remote flush failed")

        try {
            closeOutputOrThrow { throw cause }
            fail("Expected IOException")
        } catch (error: IOException) {
            assertSame(cause, error.cause)
            assertEquals(
                "保存先への書き込み確定に失敗しました: remote flush failed",
                error.message,
            )
        }
    }
}
