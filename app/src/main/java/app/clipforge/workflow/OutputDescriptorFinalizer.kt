package app.clipforge.workflow

import java.io.IOException

/**
 * Closes the destination descriptor as part of the successful export path.
 *
 * Some ContentProviders only surface the final flush/upload failure from close(). Treat that as a
 * real export failure instead of allowing the caller to publish a false-success state.
 */
internal fun closeOutputOrThrow(close: () -> Unit) {
    try {
        close()
    } catch (error: Throwable) {
        val detail = error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName
        throw IOException("保存先への書き込み確定に失敗しました: $detail", error)
    }
}
