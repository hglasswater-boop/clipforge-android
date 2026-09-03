package app.clipforge.smb

import jcifs.CIFSContext
import jcifs.context.BaseContext
import jcifs.config.PropertyConfiguration
import jcifs.smb.NtlmPasswordAuthenticator
import java.io.Closeable
import java.io.File
import java.util.Properties

data class SmbConnection(
    val host: String,
    val share: String,
    val domain: String = "",
    val username: String = "",
    val password: String = ""
)

data class SmbEntry(
    val name: String,
    val path: String,
    val directory: Boolean,
    val size: Long,
    val lastModified: Long
) {
    val isVideo: Boolean
        get() = !directory && (name.endsWith(".mp4", true) || name.endsWith(".mkv", true))
}

class SmbClient : Closeable {
    private var context: CIFSContext? = null
    private var connection: SmbConnection? = null

    fun connect(config: SmbConnection) {
        require(config.host.isNotBlank()) { "Host is required" }
        require(config.share.isNotBlank()) { "Share is required" }

        close()
        val properties = Properties().apply {
            setProperty("jcifs.smb.client.minVersion", "SMB202")
            setProperty("jcifs.smb.client.maxVersion", "SMB311")
            setProperty("jcifs.smb.client.enableSMB2", "true")
            setProperty("jcifs.smb.client.responseTimeout", "30000")
            setProperty("jcifs.smb.client.soTimeout", "35000")
        }
        val base = BaseContext(PropertyConfiguration(properties))
        val credentials = NtlmPasswordAuthenticator(config.domain, config.username, config.password)
        context = base.withCredentials(credentials)
        connection = config

        resource("").use { root ->
            check(root.exists() && root.isDirectory) { "SMB share is not reachable" }
        }
    }

    fun list(path: String): List<SmbEntry> {
        return resource(directoryPath(path)).use { directory ->
            directory.children().use { children ->
                buildList {
                    while (children.hasNext()) {
                        children.next().use { child ->
                            val isDirectory = child.isDirectory
                            val cleanName = child.name.trimEnd('/')
                            add(
                                SmbEntry(
                                    name = cleanName,
                                    path = join(path, cleanName) + if (isDirectory) "/" else "",
                                    directory = isDirectory,
                                    size = if (isDirectory) 0L else child.length(),
                                    lastModified = child.lastModified()
                                )
                            )
                        }
                    }
                }.sortedWith(compareByDescending<SmbEntry> { it.directory }.thenBy { it.name.lowercase() })
            }
        }
    }

    fun exists(remotePath: String): Boolean = resource(remotePath).use { it.exists() }

    fun size(remotePath: String): Long = resource(remotePath).use { remote ->
        require(remote.isFile) { "Not a file: $remotePath" }
        remote.length()
    }

    fun download(
        remotePath: String,
        localFile: File,
        onProgress: (transferredBytes: Long, totalBytes: Long) -> Unit = { _, _ -> }
    ) {
        localFile.parentFile?.mkdirs()
        resource(remotePath).use { remote ->
            require(remote.isFile) { "Not a file: $remotePath" }
            val totalBytes = remote.length().coerceAtLeast(0L)
            remote.openInputStream().buffered(1024 * 1024).use { input ->
                localFile.outputStream().buffered(1024 * 1024).use { output ->
                    copyWithProgress(input, output, totalBytes, onProgress)
                }
            }
        }
    }

    fun uploadAtomically(
        localFile: File,
        remotePath: String,
        onProgress: (transferredBytes: Long, totalBytes: Long) -> Unit = { _, _ -> }
    ) {
        val finalPath = remotePath.trimStart('/')
        val tempPath = "$finalPath.clipforge-partial-${System.nanoTime()}"
        resource(tempPath).use { temp ->
            try {
                temp.openOutputStream().buffered(1024 * 1024).use { output ->
                    localFile.inputStream().buffered(1024 * 1024).use { input ->
                        copyWithProgress(input, output, localFile.length().coerceAtLeast(0L), onProgress)
                    }
                }
                resource(finalPath).use { destination ->
                    // Never silently replace an existing remote file. This also closes the
                    // race between the preflight exists() check and the final SMB rename.
                    temp.renameTo(destination, false)
                }
            } catch (t: Throwable) {
                runCatching { if (temp.exists()) temp.delete() }
                throw t
            }
        }
    }

    fun delete(remotePath: String) {
        resource(remotePath).use { it.delete() }
    }

    fun mkdirs(remotePath: String) {
        resource(directoryPath(remotePath)).use { it.mkdirs() }
    }

    override fun close() {
        runCatching { context?.close() }
        context = null
        connection = null
    }

    private fun copyWithProgress(
        input: java.io.InputStream,
        output: java.io.OutputStream,
        totalBytes: Long,
        onProgress: (Long, Long) -> Unit
    ) {
        val buffer = ByteArray(1024 * 1024)
        val reportInterval = 4L * 1024L * 1024L
        var transferred = 0L
        var lastReported = -reportInterval
        onProgress(0L, totalBytes)

        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            output.write(buffer, 0, read)
            transferred += read
            if (transferred - lastReported >= reportInterval || (totalBytes > 0 && transferred >= totalBytes)) {
                onProgress(transferred, totalBytes)
                lastReported = transferred
            }
        }
        if (transferred != lastReported) onProgress(transferred, totalBytes)
    }

    private fun resource(path: String) = requireNotNull(context) { "SMB is not connected" }.get(url(path))

    private fun url(path: String): String {
        val config = requireNotNull(connection) { "SMB is not connected" }
        val normalized = path.trimStart('/')
        return "smb://${config.host}/${config.share}/$normalized"
    }

    private fun directoryPath(path: String): String = path.trim('/').let { if (it.isBlank()) "" else "$it/" }

    private fun join(parent: String, child: String): String {
        val p = parent.trim('/')
        return if (p.isBlank()) child else "$p/$child"
    }
}
