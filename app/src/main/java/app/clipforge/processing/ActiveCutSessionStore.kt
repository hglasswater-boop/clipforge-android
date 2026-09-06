package app.clipforge.processing

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

enum class ActiveCutSessionPhase {
    EDITING,
    EXPORTING,
}

data class ActiveCutSessionSnapshot(
    val sourceUri: String,
    val sourceName: String,
    val sessionPath: String,
    val localPath: String?,
    val durationMs: Long,
    val thumbnailPaths: List<String>,
    val phase: ActiveCutSessionPhase,
    val updatedAtEpochMs: Long,
)

/**
 * Durable pointer to the currently active trim editor.
 *
 * The marker lives inside the app-private edit-session directory. This avoids a second global
 * database/pointer and means deleting the session naturally makes the record invalid. The
 * process-local StateFlow exists only to notify the UI when a background export returns the
 * session to EDITING after an Activity/process recreation.
 *
 * Marker I/O is intentionally best-effort: a recovery-metadata failure must never make the
 * actual media operation fail.
 */
object ActiveCutSessionStore {
    private val _state = MutableStateFlow<ActiveCutSessionSnapshot?>(null)
    val state = _state.asStateFlow()

    @Synchronized
    fun prepared(
        sourceUri: String,
        sourceName: String,
        sessionPath: String,
        localPath: String?,
        durationMs: Long,
        thumbnailPaths: List<String>,
    ) {
        if (durationMs <= 0L) return
        val snapshot = ActiveCutSessionSnapshot(
            sourceUri = sourceUri,
            sourceName = sourceName,
            sessionPath = sessionPath,
            localPath = localPath,
            durationMs = durationMs,
            thumbnailPaths = thumbnailPaths.take(MAX_THUMBNAILS),
            phase = ActiveCutSessionPhase.EDITING,
            updatedAtEpochMs = System.currentTimeMillis(),
        )
        runCatching { write(snapshot) }
        _state.value = snapshot
    }

    @Synchronized
    fun markExporting(sessionPath: String) {
        updatePhase(sessionPath, ActiveCutSessionPhase.EXPORTING)
    }

    @Synchronized
    fun markEditing(sessionPath: String) {
        updatePhase(sessionPath, ActiveCutSessionPhase.EDITING)
    }

    @Synchronized
    fun clear(sessionPath: String? = _state.value?.sessionPath) {
        val path = sessionPath ?: return
        runCatching { markerFile(File(path)).delete() }
        runCatching { temporaryMarkerFile(File(path)).delete() }
        if (_state.value?.sessionPath == path) _state.value = null
    }

    /**
     * Restores the newest valid marker. Call before ExternalEditPipeline is constructed so the
     * active directory can be touched and excluded from its 24-hour stale-session cleanup.
     * Only a bounded number of marker-bearing directories is parsed because this runs at launch.
     */
    fun restore(cacheRoot: File): ActiveCutSessionSnapshot? {
        val root = File(cacheRoot, EDIT_SESSION_RELATIVE_PATH)
        val restored = runCatching {
            if (!root.isDirectory) return@runCatching null
            root.listFiles().orEmpty()
                .asSequence()
                .filter(File::isDirectory)
                .filter { session -> markerFile(session).isFile }
                .sortedByDescending { session -> markerFile(session).lastModified() }
                .take(MAX_STARTUP_SESSION_SCAN)
                .mapNotNull(::read)
                .maxByOrNull(ActiveCutSessionSnapshot::updatedAtEpochMs)
        }.getOrNull()
        restored?.let { active ->
            runCatching { File(active.sessionPath).setLastModified(System.currentTimeMillis()) }
        }
        synchronized(this) {
            _state.value = restored
        }
        return restored
    }

    @Synchronized
    internal fun resetForTest() {
        _state.value = null
    }

    private fun updatePhase(sessionPath: String, phase: ActiveCutSessionPhase) {
        val sessionDir = File(sessionPath)
        val current = _state.value
            ?.takeIf { it.sessionPath == sessionPath }
            ?: read(sessionDir)
            ?: return
        val updated = current.copy(
            phase = phase,
            updatedAtEpochMs = System.currentTimeMillis(),
        )
        runCatching { write(updated) }
        runCatching { sessionDir.setLastModified(updated.updatedAtEpochMs) }
        _state.value = updated
    }

    private fun write(snapshot: ActiveCutSessionSnapshot) {
        val sessionDir = File(snapshot.sessionPath)
        require(sessionDir.isDirectory) { "Edit session directory does not exist" }
        val target = markerFile(sessionDir)
        val temporary = temporaryMarkerFile(sessionDir)

        FileOutputStream(temporary).use { fileOutput ->
            DataOutputStream(BufferedOutputStream(fileOutput)).use { output ->
                output.writeInt(MAGIC)
                output.writeInt(VERSION)
                writeString(output, snapshot.sourceUri)
                writeString(output, snapshot.sourceName)
                writeString(output, snapshot.sessionPath)
                writeNullableString(output, snapshot.localPath)
                output.writeLong(snapshot.durationMs)
                output.writeInt(snapshot.phase.ordinal)
                output.writeLong(snapshot.updatedAtEpochMs)
                output.writeInt(snapshot.thumbnailPaths.size)
                snapshot.thumbnailPaths.forEach { path -> writeString(output, path) }
                output.flush()
                fileOutput.fd.sync()
            }
        }
        runCatching {
            Files.move(
                temporary.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        }.recoverCatching {
            Files.move(
                temporary.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }.getOrThrow()
        runCatching { sessionDir.setLastModified(snapshot.updatedAtEpochMs) }
    }

    private fun read(sessionDir: File): ActiveCutSessionSnapshot? = runCatching {
        val marker = markerFile(sessionDir)
        if (!marker.isFile) return@runCatching null
        DataInputStream(BufferedInputStream(FileInputStream(marker))).use { input ->
            require(input.readInt() == MAGIC)
            require(input.readInt() == VERSION)
            val sourceUri = readString(input)
            val sourceName = readString(input)
            val recordedSession = File(readString(input))
            require(recordedSession.canonicalFile == sessionDir.canonicalFile)
            val localPath = readNullableString(input)?.let(::File)
            val durationMs = input.readLong()
            require(durationMs > 0L)
            val phaseOrdinal = input.readInt()
            val phase = ActiveCutSessionPhase.entries.getOrNull(phaseOrdinal)
                ?: error("Invalid active-session phase")
            val updatedAt = input.readLong()
            val thumbnailCount = input.readInt()
            require(thumbnailCount in 0..MAX_THUMBNAILS)

            if (localPath != null) {
                require(localPath.isFile)
                require(isInside(localPath, sessionDir))
            }
            val thumbnails = buildList {
                repeat(thumbnailCount) {
                    val file = File(readString(input))
                    if (file.isFile && isInside(file, sessionDir)) add(file.absolutePath)
                }
            }
            ActiveCutSessionSnapshot(
                sourceUri = sourceUri,
                sourceName = sourceName,
                sessionPath = sessionDir.absolutePath,
                localPath = localPath?.absolutePath,
                durationMs = durationMs,
                thumbnailPaths = thumbnails,
                phase = phase,
                updatedAtEpochMs = updatedAt.takeIf { it > 0L } ?: marker.lastModified(),
            )
        }
    }.getOrElse {
        runCatching { markerFile(sessionDir).delete() }
        runCatching { temporaryMarkerFile(sessionDir).delete() }
        null
    }

    private fun writeString(output: DataOutputStream, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_STRING_BYTES)
        output.writeInt(bytes.size)
        output.write(bytes)
    }

    private fun readString(input: DataInputStream): String {
        val size = input.readInt()
        require(size in 0..MAX_STRING_BYTES)
        val bytes = ByteArray(size)
        input.readFully(bytes)
        return bytes.toString(Charsets.UTF_8)
    }

    private fun writeNullableString(output: DataOutputStream, value: String?) {
        output.writeBoolean(value != null)
        if (value != null) writeString(output, value)
    }

    private fun readNullableString(input: DataInputStream): String? =
        if (input.readBoolean()) readString(input) else null

    private fun isInside(file: File, directory: File): Boolean {
        val directoryPath = directory.canonicalFile.toPath()
        return file.canonicalFile.toPath().startsWith(directoryPath)
    }

    private fun markerFile(sessionDir: File): File = File(sessionDir, MARKER_FILE_NAME)
    private fun temporaryMarkerFile(sessionDir: File): File = File(sessionDir, "$MARKER_FILE_NAME.tmp")

    private const val MAGIC = 0x43464553 // CFES
    private const val VERSION = 1
    private const val MARKER_FILE_NAME = ".active-cut-session-v1.bin"
    private const val EDIT_SESSION_RELATIVE_PATH = "clipforge/external-edit"
    private const val MAX_THUMBNAILS = 64
    private const val MAX_STRING_BYTES = 1024 * 1024
    private const val MAX_STARTUP_SESSION_SCAN = 64
}
