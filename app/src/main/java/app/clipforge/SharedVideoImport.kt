package app.clipforge

import android.content.ClipData
import android.content.Intent
import android.net.Uri

/**
 * Extracts video content URIs from Android share/view intents without copying the source media.
 * ClipForge keeps only URI strings here and opens independent descriptors later for each operation.
 */
internal fun sharedVideoUris(intent: Intent): List<String> {
    val streamUris = when (intent.action) {
        Intent.ACTION_SEND -> listOfNotNull(intent.parcelableUriExtra(Intent.EXTRA_STREAM))
        Intent.ACTION_SEND_MULTIPLE -> intent.parcelableUriArrayListExtra(Intent.EXTRA_STREAM)
        Intent.ACTION_VIEW -> emptyList()
        else -> emptyList()
    }
    return normalizeSharedVideoUris(
        action = intent.action,
        directUri = intent.data?.toString(),
        streamUris = streamUris.map(Uri::toString),
        clipDataUris = intent.clipData.toUriList().map(Uri::toString),
    )
}

internal fun normalizeSharedVideoUris(
    action: String?,
    directUri: String?,
    streamUris: List<String>,
    clipDataUris: List<String>,
): List<String> {
    val supported = action == Intent.ACTION_SEND ||
        action == Intent.ACTION_SEND_MULTIPLE ||
        action == Intent.ACTION_VIEW
    if (!supported) return emptyList()

    return buildList {
        if (action == Intent.ACTION_VIEW) directUri?.let(::add)
        addAll(streamUris)
        addAll(clipDataUris)
    }
        .filter(String::isNotBlank)
        .distinct()
}

private fun ClipData?.toUriList(): List<Uri> = buildList {
    val clips = this@toUriList ?: return@buildList
    for (index in 0 until clips.itemCount) {
        clips.getItemAt(index).uri?.let(::add)
    }
}

@Suppress("DEPRECATION")
private fun Intent.parcelableUriExtra(name: String): Uri? =
    if (android.os.Build.VERSION.SDK_INT >= 33) {
        getParcelableExtra(name, Uri::class.java)
    } else {
        getParcelableExtra(name)
    }

@Suppress("DEPRECATION")
private fun Intent.parcelableUriArrayListExtra(name: String): List<Uri> =
    if (android.os.Build.VERSION.SDK_INT >= 33) {
        getParcelableArrayListExtra(name, Uri::class.java).orEmpty()
    } else {
        getParcelableArrayListExtra<Uri>(name).orEmpty()
    }
