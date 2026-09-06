package app.clipforge.processing

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ActiveCutSessionStoreTest {
    private val roots = mutableListOf<File>()

    @After
    fun tearDown() {
        ProcessingStateStore.idle()
        ActiveCutSessionStore.resetForTest()
        roots.forEach { root -> root.deleteRecursively() }
    }

    @Test
    fun preparedSessionSurvivesProcessLocalStateReset() {
        val cacheRoot = cacheRoot()
        val session = session(cacheRoot, "one")
        val local = File(session, "video.mkv").apply { writeText("fixture") }
        val thumbnail = File(session, "timeline/000.jpg").apply {
            parentFile?.mkdirs()
            writeText("thumb")
        }

        ActiveCutSessionStore.prepared(
            sourceUri = "content://example/video/1",
            sourceName = "video.mkv",
            sessionPath = session.absolutePath,
            localPath = local.absolutePath,
            durationMs = 9_000_000L,
            thumbnailPaths = listOf(thumbnail.absolutePath),
        )
        ActiveCutSessionStore.resetForTest()

        val restored = ActiveCutSessionStore.restore(cacheRoot)
        requireNotNull(restored)
        assertEquals("content://example/video/1", restored.sourceUri)
        assertEquals("video.mkv", restored.sourceName)
        assertEquals(session.absolutePath, restored.sessionPath)
        assertEquals(local.absolutePath, restored.localPath)
        assertEquals(9_000_000L, restored.durationMs)
        assertEquals(listOf(thumbnail.absolutePath), restored.thumbnailPaths)
        assertEquals(ActiveCutSessionPhase.EDITING, restored.phase)
    }

    @Test
    fun exportingSessionIsDurablyMarkedAndCanReturnToEditing() {
        val cacheRoot = cacheRoot()
        val session = session(cacheRoot, "phase")
        ActiveCutSessionStore.prepared(
            sourceUri = "content://example/video/2",
            sourceName = "remote.mp4",
            sessionPath = session.absolutePath,
            localPath = null,
            durationMs = 600_000L,
            thumbnailPaths = emptyList(),
        )

        ActiveCutSessionStore.markExporting(session.absolutePath)
        ActiveCutSessionStore.resetForTest()
        assertEquals(
            ActiveCutSessionPhase.EXPORTING,
            ActiveCutSessionStore.restore(cacheRoot)?.phase,
        )

        ActiveCutSessionStore.markEditing(session.absolutePath)
        ActiveCutSessionStore.resetForTest()
        assertEquals(
            ActiveCutSessionPhase.EDITING,
            ActiveCutSessionStore.restore(cacheRoot)?.phase,
        )
    }

    @Test
    fun explicitIdleClearsThePreparedEditorMarker() {
        val cacheRoot = cacheRoot()
        val session = session(cacheRoot, "idle")
        ProcessingStateStore.cutPrepared(
            sourceUri = "content://example/video/3",
            sourceName = "video.mp4",
            sessionPath = session.absolutePath,
            localPath = null,
            durationMs = 120_000L,
            thumbnailPaths = emptyList(),
        )
        assertTrue(marker(session).isFile)

        ProcessingStateStore.idle()
        ActiveCutSessionStore.resetForTest()

        assertNull(ActiveCutSessionStore.restore(cacheRoot))
        assertFalse(marker(session).exists())
    }

    @Test
    fun restoreRefreshesActiveDirectoryBeforeStaleCleanup() {
        val cacheRoot = cacheRoot()
        val session = session(cacheRoot, "retained")
        ActiveCutSessionStore.prepared(
            sourceUri = "content://example/video/4",
            sourceName = "video.mp4",
            sessionPath = session.absolutePath,
            localPath = null,
            durationMs = 120_000L,
            thumbnailPaths = emptyList(),
        )
        assertTrue(session.setLastModified(1L))
        ActiveCutSessionStore.resetForTest()

        assertEquals(ActiveCutSessionPhase.EDITING, ActiveCutSessionStore.restore(cacheRoot)?.phase)
        assertTrue(session.lastModified() > 1L)
    }

    @Test
    fun corruptedSessionMetadataIsIgnoredAndRemoved() {
        val cacheRoot = cacheRoot()
        val session = session(cacheRoot, "corrupt")
        marker(session).writeBytes(byteArrayOf(1, 2, 3, 4, 5, 6))

        assertNull(ActiveCutSessionStore.restore(cacheRoot))
        assertFalse(marker(session).exists())
    }

    private fun cacheRoot(): File =
        Files.createTempDirectory("clipforge-cache-").toFile().also(roots::add)

    private fun session(cacheRoot: File, name: String): File =
        File(cacheRoot, "clipforge/external-edit/$name").apply {
            assertTrue(mkdirs())
        }

    private fun marker(session: File): File = File(session, ".active-cut-session-v1.bin")
}
