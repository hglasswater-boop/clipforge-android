package app.clipforge.processing

import android.content.ContentProviderClient
import android.content.ContentResolver
import android.net.Uri
import java.io.Closeable
import java.io.IOException

/**
 * Holds stable ContentProvider references for the lifetime of a background media operation.
 *
 * A ParcelFileDescriptor returned by a proxy ContentProvider can keep working only while the
 * provider process is runnable. ContentResolver.openFileDescriptor() releases its provider
 * reference as soon as the descriptor has been returned, so a remote provider such as XFiles can
 * become cached/frozen after ClipForge goes to the background even though FFmpeg still owns the
 * descriptor. A stable ContentProviderClient prevents that lifecycle gap without staging or
 * copying the media file.
 *
 * This class deliberately owns provider references only. Actual file descriptors are still opened
 * independently by each analyzer/FFmpeg operation, so descriptor file positions are never shared.
 */
internal class ContentProviderLeaseSet private constructor(
    private val clients: List<ContentProviderClient>,
) : Closeable {
    override fun close() {
        clients.asReversed().forEach { client ->
            runCatching { client.close() }
        }
    }

    companion object {
        fun acquire(
            contentResolver: ContentResolver,
            uriStrings: Iterable<String>,
        ): ContentProviderLeaseSet {
            val contentUris = uriStrings
                .map { value -> Uri.parse(value) }
                .filter { uri -> uri.scheme == ContentResolver.SCHEME_CONTENT }
                .distinctBy { uri -> uri.authority }

            val clients = mutableListOf<ContentProviderClient>()
            try {
                contentUris.forEach { uri ->
                    val client = contentResolver.acquireContentProviderClient(uri)
                        ?: throw IOException(
                            "ContentProviderへの接続を保持できません: ${uri.authority ?: "unknown"}",
                        )
                    clients += client
                }
                return ContentProviderLeaseSet(clients)
            } catch (error: Throwable) {
                clients.asReversed().forEach { client -> runCatching { client.close() } }
                throw error
            }
        }
    }
}
