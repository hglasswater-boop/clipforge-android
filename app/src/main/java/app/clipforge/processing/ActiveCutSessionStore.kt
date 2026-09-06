package app.clipforge.processing

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
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
        require(durationMs > 0L) { "durationMs must be > 0" }
        val snapshot = ActiveCutSessionSnapshot(
            sourceUri = sourceUri,
            sourceName = sourceName,
            sessionPath = sessionPath,
            localPath = localPath,
            durationMs = durationMs,
            thumbnailPaths = thumbnailPaths,
            phase = ActiveCutSessionPhase.EDITING,
            updatedAtEpochMs = System.currentTimeMillis(),
        )
        write(snapshot)
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
        markerFile(File(path)).delete()
        temporaryMarkerFile(File(path)).delete()
        if (_state.value?.sessionPath == path) _state.value = null
    }

    /** Call on an IO dispatcher. Returns the newest valid session marker, if one exists. */
    fun restore(cacheRoot: File): ActiveCutSessionSnapshot? {
        val root = File(cacheRoot, EDIT_SESSION_RELATIVE_PATH)
        val restored = runCatching {
            if (!root.isDirectory) return@runCatching null
            root.listFiles().orEmpty()
                .asSequence()
                .filter(File::isDirectory)
                .mapNotNull(::read)
                .maxByOrNull(ActiveCutSessionSnapshot::updatedAtEpochMs)
        }.getOrNull()
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
        write(updated)
        _state.value = updated
    }

    private fun write(snapshot: ActiveCutSessionSnapshot) {
        val sessionDir = File(snapshot.sessionPath)
        require(sessionDir.isDirectory) { "Edit session directory does not exist" }
        val target = markerFile(sessionDir)
        val temporary = temporaryMarkerFile(sessionDir)
        val json = JSONObject()
            .put("version", VERSION)
            .put("sourceUri", snapshot.sourceUri)
            .put("sourceName", snapshot.sourceName)
            .put("sessionPath", snapshot.sessionPath)
            .put("localPath", snapshot.localPath ?: JSONObject.NULL)
            .put("durationMs", snapshot.durationMs)
            .put("phase", snapshot.phase.name)
            .put("updatedAt", snapshot.updatedAtEpochMs)
            .put(
                "thumbnailPaths",
                JSONArray().apply { snapshot.thumbnailPaths.forEach(::put) },
            )
            .toString()

        FileOutputStream(temporary).use { output ->
            output.write(json.toByteArray(Charsets.UTF_8))
            output.flush()
            output.fd.sync()
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
    }

    private fun read(sessionDir: File): ActiveCutSessionSnapshot? = runCatching {
        val marker = markerFile(sessionDir)
        if (!marker.isFile) return@runCatching null
        val root = JSONObject(marker.readText())
        require(root.optInt("version", -1) == VERSION)
        val recordedSession = File(root.getString("sessionPath"))
        require(recordedSession.canonicalFile == sessionDir.canonicalFile)
        val durationMs = root.getLong("durationMs")
        require(durationMs > 0L)
        val localPath = root.optString("localPath")
            .takeIf { it.isNotBlank() && it != "null" }
            ?.let(::File)
        if (localPath != null) {
            require(localPath.isFile)
            require(isInside(localPath, sessionDir))
        }
        val thumbnails = buildList {
            val array = root.optJSONArray("thumbnailPaths") ?: JSONArray()
            for (index in 0 until array.length()) {
                val path = array.optString(index).takeIf(String::isNotBlank) ?: continue
                val file = File(path)
                if (file.isFile && isInside(file, sessionDir)) add(file.absolutePath)
            }
        }
        ActiveCutSessionSnapshot(
            sourceUri = root.getString("sourceUri"),
            sourceName = root.getString("sourceName"),
            sessionPath = sessionDir.absolutePath,
            localPath = localPath?.absolutePath,
            durationMs = durationMs,
            thumbnailPaths = thumbnails,
            phase = ActiveCutSessionPhase.valueOf(root.getString("phase")),
            updatedAtEpochMs = root.optLong("updatedAt", marker.lastModified()),
        )
    }.getOrElse {
        markerFile(sessionDir).delete()
        temporaryMarkerFile(sessionDir).delete()
        null
    }

    private fun isInside(file: File, directory: File): Boolean {
        val directoryPath = directory.canonicalFile.toPath()
        return file.canonicalFile.toPath().startsWith(directoryPath)
    }

    private fun markerFile(sessionDir: File): File = File(sessionDir, MARKER_FILE_NAME)
    private fun temporaryMarkerFile(sessionDir: File): File = File(sessionDir, "$MARKER_FILE_NAME.tmp")

    private const val VERSION = 1
    private const val MARKER_FILE_NAME = ".active-cut-session-v1.json"
    private const val EDIT_SESSION_RELATIVE_PATH = "clipforge/external-edit"
}
