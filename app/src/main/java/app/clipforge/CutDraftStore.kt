package app.clipforge

import android.content.Context
import app.clipforge.media.CutMode
import app.clipforge.media.MediaSegment
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64

internal data class CutDraft(
    val sourceUri: String,
    val sourceName: String,
    val sourceSizeBytes: Long?,
    val durationMs: Long,
    val cutRanges: List<MediaSegment>,
    val cutMode: CutMode,
)

internal class CutDraftStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun load(
        sourceUri: String,
        sourceName: String,
        sourceSizeBytes: Long?,
        durationMs: Long,
    ): CutDraft? {
        val raw = preferences.getString(cutDraftKey(sourceUri, sourceName), null) ?: return null
        val draft = decodeCutDraft(raw) ?: return null
        if (draft.sourceUri != sourceUri || draft.sourceName != sourceName) return null
        if (draft.durationMs != durationMs) return null
        if (
            sourceSizeBytes != null &&
            draft.sourceSizeBytes != null &&
            sourceSizeBytes != draft.sourceSizeBytes
        ) {
            return null
        }
        return draft
    }

    fun save(draft: CutDraft) {
        preferences.edit()
            .putString(cutDraftKey(draft.sourceUri, draft.sourceName), encodeCutDraft(draft))
            .apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "clipforge_cut_drafts"
    }
}

internal fun cutDraftFrom(editor: TrimEditorState, sourceSizeBytes: Long?): CutDraft = CutDraft(
    sourceUri = editor.sourceUri,
    sourceName = editor.sourceName,
    sourceSizeBytes = sourceSizeBytes,
    durationMs = editor.durationMs,
    cutRanges = editor.cutRanges,
    cutMode = editor.cutMode,
)

internal fun CutDraft.restoreInto(editor: TrimEditorState): TrimEditorState {
    val validRanges = cutRanges
        .filter { range ->
            range.startMs >= 0L &&
                range.endMs > range.startMs &&
                range.endMs <= editor.durationMs
        }
        .sortedBy(MediaSegment::startMs)
    return editor.copy(
        startMs = 0L,
        endMs = editor.durationMs,
        cutRanges = validRanges,
        cutMode = cutMode,
        editingCutIndex = null,
    )
}

internal fun cutDraftKey(sourceUri: String, sourceName: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
        .digest("$sourceUri\n$sourceName".toByteArray(StandardCharsets.UTF_8))
    return buildString(prefix = "draft_") {
        digest.forEach { byte -> append("%02x".format(byte.toInt() and 0xff)) }
    }
}

internal fun encodeCutDraft(draft: CutDraft): String {
    val encoder = Base64.getUrlEncoder().withoutPadding()
    fun encodeText(value: String): String = encoder.encodeToString(value.toByteArray(StandardCharsets.UTF_8))
    val ranges = draft.cutRanges.joinToString(",") { range -> "${range.startMs}:${range.endMs}" }
    return listOf(
        CUT_DRAFT_VERSION.toString(),
        encodeText(draft.sourceUri),
        encodeText(draft.sourceName),
        (draft.sourceSizeBytes ?: -1L).toString(),
        draft.durationMs.toString(),
        draft.cutMode.name,
        ranges,
    ).joinToString("|")
}

internal fun decodeCutDraft(raw: String): CutDraft? = runCatching {
    val parts = raw.split('|', limit = 7)
    require(parts.size == 7)
    require(parts[0].toInt() == CUT_DRAFT_VERSION)
    val decoder = Base64.getUrlDecoder()
    fun decodeText(value: String): String = String(decoder.decode(value), StandardCharsets.UTF_8)

    val sourceSizeValue = parts[3].toLong()
    val durationMs = parts[4].toLong()
    require(durationMs > 0L)
    val ranges = if (parts[6].isBlank()) {
        emptyList()
    } else {
        parts[6].split(',').map { encodedRange ->
            val bounds = encodedRange.split(':', limit = 2)
            require(bounds.size == 2)
            val startMs = bounds[0].toLong()
            val endMs = bounds[1].toLong()
            require(startMs >= 0L && endMs > startMs && endMs <= durationMs)
            MediaSegment(startMs, endMs)
        }
    }

    CutDraft(
        sourceUri = decodeText(parts[1]),
        sourceName = decodeText(parts[2]),
        sourceSizeBytes = sourceSizeValue.takeIf { it >= 0L },
        durationMs = durationMs,
        cutRanges = ranges,
        cutMode = CutMode.valueOf(parts[5]),
    )
}.getOrNull()

private const val CUT_DRAFT_VERSION = 1
