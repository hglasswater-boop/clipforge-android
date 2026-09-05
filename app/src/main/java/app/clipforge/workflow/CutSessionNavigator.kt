package app.clipforge.workflow

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import app.clipforge.media.SyncFrameResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

class CutSessionNavigator(
    context: Context,
    private val syncFrameResolver: SyncFrameResolver = SyncFrameResolver(),
) {
    private val contentResolver = context.applicationContext.contentResolver

    suspend fun adjacentKeyframe(
        source: PickedVideo,
        localInputPath: String?,
        durationMs: Long,
        positionMs: Long,
        forward: Boolean,
    ): Long? = withContext(Dispatchers.IO) {
        if (localInputPath != null) {
            val input = File(localInputPath)
            if (!input.isFile) return@withContext null
            return@withContext syncFrameResolver.adjacentSyncFrame(
                source = input,
                durationMs = durationMs,
                positionMs = positionMs,
                forward = forward,
            )
        }

        openSeekable(source).use { descriptor ->
            syncFrameResolver.adjacentSyncFrame(
                sourceFd = descriptor.fileDescriptor,
                durationMs = durationMs,
                positionMs = positionMs,
                forward = forward,
            )
        }
    }

    private fun openSeekable(source: PickedVideo): ParcelFileDescriptor {
        val descriptor = contentResolver.openFileDescriptor(Uri.parse(source.uri), "r")
            ?: throw IOException("ファイルを開けません: ${source.displayName}")
        try {
            Os.lseek(descriptor.fileDescriptor, 0L, OsConstants.SEEK_SET)
            return descriptor
        } catch (error: Throwable) {
            runCatching { descriptor.close() }
            throw IOException("シークできない動画です: ${source.displayName}", error)
        }
    }
}
