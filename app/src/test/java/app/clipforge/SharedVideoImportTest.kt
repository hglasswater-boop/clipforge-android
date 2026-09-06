package app.clipforge

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedVideoImportTest {
    @Test
    fun `single share prefers stream and de-duplicates clip data`() {
        val result = normalizeSharedVideoUris(
            action = Intent.ACTION_SEND,
            directUri = null,
            streamUris = listOf("content://xfiles/video1"),
            clipDataUris = listOf("content://xfiles/video1"),
        )

        assertEquals(listOf("content://xfiles/video1"), result)
    }

    @Test
    fun `multiple share keeps all unique shared videos`() {
        val result = normalizeSharedVideoUris(
            action = Intent.ACTION_SEND_MULTIPLE,
            directUri = null,
            streamUris = listOf(
                "content://xfiles/video1",
                "content://xfiles/video2",
            ),
            clipDataUris = listOf(
                "content://xfiles/video2",
                "content://xfiles/video3",
            ),
        )

        assertEquals(
            listOf(
                "content://xfiles/video1",
                "content://xfiles/video2",
                "content://xfiles/video3",
            ),
            result,
        )
    }

    @Test
    fun `view intent accepts direct uri`() {
        val result = normalizeSharedVideoUris(
            action = Intent.ACTION_VIEW,
            directUri = "content://xfiles/movie",
            streamUris = emptyList(),
            clipDataUris = emptyList(),
        )

        assertEquals(listOf("content://xfiles/movie"), result)
    }

    @Test
    fun `launcher intent is ignored`() {
        val result = normalizeSharedVideoUris(
            action = Intent.ACTION_MAIN,
            directUri = "content://xfiles/movie",
            streamUris = listOf("content://xfiles/movie"),
            clipDataUris = emptyList(),
        )

        assertTrue(result.isEmpty())
    }
}
