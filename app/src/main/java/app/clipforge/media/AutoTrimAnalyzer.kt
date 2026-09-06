package app.clipforge.media

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

data class AutoTrimAnalysis(
    val startCandidates: List<AutoTrimCandidate>,
    val endCandidates: List<AutoTrimCandidate>,
    val startFingerprint: EdgeFingerprintSnapshot,
    val endFingerprint: EdgeFingerprintSnapshot,
    val scannedStart: MediaSegment,
    val scannedEnd: MediaSegment,
)

private data class AudioFeatureSample(
    val timeMs: Long,
    val rmsDb: Double,
)

private data class AudioFeatureResult(
    val samples: List<AudioFeatureSample>,
    val signals: List<AutoTrimAudioSignal>,
)

class AutoTrimAnalyzer(context: Context) {
    private val appContext = context.applicationContext
    private val sceneDetector = SceneChangeDetector()
    private val audioDetector = AudioFeatureDetector()
    private val knownStore = KnownClipFingerprintStore(appContext)

    suspend fun analyze(
        sourceUri: String,
        localInputPath: String?,
        durationMs: Long,
    ): AutoTrimAnalysis = withContext(Dispatchers.IO) {
        require(durationMs > 1L) { "動画の長さを取得できません" }
        val edgeWindowMs = edgeWindowFor(durationMs)
        val startRange = MediaSegment(0L, edgeWindowMs)
        val endRange = MediaSegment((durationMs - edgeWindowMs).coerceAtLeast(0L), durationMs)

        val startScenes = detectScenes(sourceUri, localInputPath, durationMs, startRange)
        val endScenes = detectScenes(sourceUri, localInputPath, durationMs, endRange)
        val startAudio = detectAudio(sourceUri, localInputPath, durationMs, startRange)
        val endAudio = detectAudio(sourceUri, localInputPath, durationMs, endRange)

        val visual = sampleVisualFingerprints(
            sourceUri = sourceUri,
            localInputPath = localInputPath,
            durationMs = durationMs,
            edgeWindowMs = edgeWindowMs,
        )
        val startFingerprint = EdgeFingerprintSnapshot(
            side = AutoTrimSide.START,
            edgeDurationMs = edgeWindowMs,
            visual = visual.first,
            audio = startAudio.samples.map {
                AudioFingerprintPoint(
                    offsetFromEdgeMs = it.timeMs,
                    rmsDb = it.rmsDb,
                )
            },
        )
        val endFingerprint = EdgeFingerprintSnapshot(
            side = AutoTrimSide.END,
            edgeDurationMs = edgeWindowMs,
            visual = visual.second,
            audio = endAudio.samples.map {
                AudioFingerprintPoint(
                    offsetFromEdgeMs = (durationMs - it.timeMs).coerceAtLeast(0L),
                    rmsDb = it.rmsDb,
                )
            }.sortedBy(AudioFingerprintPoint::offsetFromEdgeMs),
        )

        val known = knownStore.load()
        val startKnown = bestKnownMatch(startFingerprint, known, durationMs)
        val endKnown = bestKnownMatch(endFingerprint, known, durationMs)

        AutoTrimAnalysis(
            startCandidates = rankAutoTrimCandidates(
                side = AutoTrimSide.START,
                durationMs = durationMs,
                windowStartMs = startRange.startMs,
                windowEndMs = startRange.endMs,
                sceneMarkers = startScenes.markers,
                audioSignals = startAudio.signals,
                knownClipMatch = startKnown,
            ),
            endCandidates = rankAutoTrimCandidates(
                side = AutoTrimSide.END,
                durationMs = durationMs,
                windowStartMs = endRange.startMs,
                windowEndMs = endRange.endMs,
                sceneMarkers = endScenes.markers,
                audioSignals = endAudio.signals,
                knownClipMatch = endKnown,
            ),
            startFingerprint = startFingerprint,
            endFingerprint = endFingerprint,
            scannedStart = startRange,
            scannedEnd = endRange,
        )
    }

    suspend fun rememberConfirmed(
        analysis: AutoTrimAnalysis,
        candidate: AutoTrimCandidate,
        durationMs: Long,
    ) = withContext(Dispatchers.IO) {
        val clipDuration = when (candidate.side) {
            AutoTrimSide.START -> candidate.boundaryMs
            AutoTrimSide.END -> durationMs - candidate.boundaryMs
        }.coerceAtLeast(0L)
        if (clipDuration < MIN_LEARNED_CLIP_MS) return@withContext

        val snapshot = when (candidate.side) {
            AutoTrimSide.START -> analysis.startFingerprint
            AutoTrimSide.END -> analysis.endFingerprint
        }
        val visual = snapshot.visual.filter { it.offsetFromEdgeMs <= clipDuration }
        if (visual.size < MIN_LEARNED_VISUAL_POINTS) return@withContext
        val audio = snapshot.audio.filter { it.offsetFromEdgeMs <= clipDuration }

        knownStore.add(
            KnownClipFingerprint(
                side = candidate.side,
                clipDurationMs = clipDuration,
                visual = visual,
                audio = audio,
                createdAtEpochMs = System.currentTimeMillis(),
            ),
        )
    }

    private fun bestKnownMatch(
        current: EdgeFingerprintSnapshot,
        known: List<KnownClipFingerprint>,
        durationMs: Long,
    ): KnownClipMatch? = known
        .asSequence()
        .filter { it.side == current.side }
        .filter { it.clipDurationMs < durationMs - 3_000L }
        .filter { it.clipDurationMs <= current.edgeDurationMs + 3_000L }
        .mapNotNull { fingerprint ->
            val similarity = matchKnownFingerprint(current, fingerprint) ?: return@mapNotNull null
            val boundary = when (current.side) {
                AutoTrimSide.START -> fingerprint.clipDurationMs
                AutoTrimSide.END -> durationMs - fingerprint.clipDurationMs
            }
            KnownClipMatch(boundaryMs = boundary, similarity = similarity)
        }
        .maxByOrNull(KnownClipMatch::similarity)

    private suspend fun detectScenes(
        sourceUri: String,
        localInputPath: String?,
        durationMs: Long,
        range: MediaSegment,
    ): SceneDetectionResult = runCatching {
        if (localInputPath != null) {
            sceneDetector.detectPath(
                path = localInputPath,
                durationMs = durationMs,
                startMs = range.startMs,
                endMs = range.endMs,
            )
        } else {
            openSeekable(sourceUri).use { descriptor ->
                sceneDetector.detectDescriptor(
                    fd = descriptor.fd,
                    durationMs = durationMs,
                    startMs = range.startMs,
                    endMs = range.endMs,
                )
            }
        }
    }.getOrElse {
        SceneDetectionResult(emptyList(), range.startMs, range.endMs)
    }

    private suspend fun detectAudio(
        sourceUri: String,
        localInputPath: String?,
        durationMs: Long,
        range: MediaSegment,
    ): AudioFeatureResult = runCatching {
        if (localInputPath != null) {
            audioDetector.detectPath(
                path = localInputPath,
                durationMs = durationMs,
                startMs = range.startMs,
                endMs = range.endMs,
            )
        } else {
            openSeekable(sourceUri).use { descriptor ->
                audioDetector.detectDescriptor(
                    fd = descriptor.fd,
                    durationMs = durationMs,
                    startMs = range.startMs,
                    endMs = range.endMs,
                )
            }
        }
    }.getOrElse { AudioFeatureResult(emptyList(), emptyList()) }

    private fun sampleVisualFingerprints(
        sourceUri: String,
        localInputPath: String?,
        durationMs: Long,
        edgeWindowMs: Long,
    ): Pair<List<VisualFingerprintPoint>, List<VisualFingerprintPoint>> {
        val retriever = MediaMetadataRetriever()
        return try {
            if (localInputPath != null) {
                retriever.setDataSource(localInputPath)
            } else {
                retriever.setDataSource(appContext, Uri.parse(sourceUri))
            }
            val intervalMs = max(MIN_VISUAL_SAMPLE_INTERVAL_MS, edgeWindowMs / MAX_VISUAL_SAMPLES_PER_EDGE)
            val start = mutableListOf<VisualFingerprintPoint>()
            val end = mutableListOf<VisualFingerprintPoint>()
            var offset = 0L
            while (offset <= edgeWindowMs) {
                frameHash(retriever, offset)?.let { hash ->
                    start += VisualFingerprintPoint(offsetFromEdgeMs = offset, hash = hash)
                }
                val endTime = (durationMs - offset).coerceAtLeast(0L)
                frameHash(retriever, endTime)?.let { hash ->
                    end += VisualFingerprintPoint(offsetFromEdgeMs = offset, hash = hash)
                }
                if (edgeWindowMs - offset < intervalMs) break
                offset += intervalMs
            }
            start to end
        } catch (_: Throwable) {
            emptyList<VisualFingerprintPoint>() to emptyList()
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun frameHash(retriever: MediaMetadataRetriever, timeMs: Long): Long? {
        val bitmap = retriever.getFrameAtTime(
            timeMs.coerceAtLeast(0L) * 1_000L,
            MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
        ) ?: return null
        return try {
            val scaled = android.graphics.Bitmap.createScaledBitmap(bitmap, HASH_WIDTH, HASH_HEIGHT, true)
            try {
                var hash = 0L
                var bit = 0
                for (y in 0 until HASH_HEIGHT) {
                    for (x in 0 until HASH_WIDTH - 1) {
                        val left = luminance(scaled.getPixel(x, y))
                        val right = luminance(scaled.getPixel(x + 1, y))
                        if (left > right) hash = hash or (1L shl bit)
                        bit += 1
                    }
                }
                hash
            } finally {
                if (scaled !== bitmap) scaled.recycle()
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun luminance(color: Int): Int {
        val red = (color shr 16) and 0xff
        val green = (color shr 8) and 0xff
        val blue = color and 0xff
        return (red * 299 + green * 587 + blue * 114) / 1_000
    }

    private fun openSeekable(sourceUri: String): ParcelFileDescriptor {
        val descriptor = appContext.contentResolver.openFileDescriptor(Uri.parse(sourceUri), "r")
            ?: throw IOException("動画を開けません")
        try {
            Os.lseek(descriptor.fileDescriptor, 0L, OsConstants.SEEK_SET)
            return descriptor
        } catch (error: Throwable) {
            runCatching { descriptor.close() }
            throw IOException("シークできない動画です", error)
        }
    }

    private fun edgeWindowFor(durationMs: Long): Long {
        val preferred = max(MIN_EDGE_WINDOW_MS, durationMs / 3L)
        return min(MAX_EDGE_WINDOW_MS, preferred)
            .coerceAtMost((durationMs / 2L).coerceAtLeast(1L))
    }

    private companion object {
        const val MIN_EDGE_WINDOW_MS = 30_000L
        const val MAX_EDGE_WINDOW_MS = 10 * 60_000L
        const val MIN_VISUAL_SAMPLE_INTERVAL_MS = 5_000L
        const val MAX_VISUAL_SAMPLES_PER_EDGE = 80L
        const val MIN_LEARNED_CLIP_MS = 15_000L
        const val MIN_LEARNED_VISUAL_POINTS = 3
        const val HASH_WIDTH = 9
        const val HASH_HEIGHT = 8
    }
}

private class AudioFeatureDetector {
    suspend fun detectPath(
        path: String,
        durationMs: Long,
        startMs: Long,
        endMs: Long,
    ): AudioFeatureResult = withContext(Dispatchers.IO) {
        detect(
            inputArguments = listOf("-ss", seconds(startMs), "-i", path),
            durationMs = durationMs,
            startMs = startMs,
            endMs = endMs,
        )
    }

    suspend fun detectDescriptor(
        fd: Int,
        durationMs: Long,
        startMs: Long,
        endMs: Long,
    ): AudioFeatureResult = withContext(Dispatchers.IO) {
        detect(
            inputArguments = listOf("-ss", seconds(startMs), "-fd", fd.toString(), "-i", "fd:"),
            durationMs = durationMs,
            startMs = startMs,
            endMs = endMs,
        )
    }

    private fun detect(
        inputArguments: List<String>,
        durationMs: Long,
        startMs: Long,
        endMs: Long,
    ): AudioFeatureResult {
        val safeDuration = durationMs.coerceAtLeast(1L)
        val safeStart = startMs.coerceIn(0L, safeDuration)
        val safeEnd = endMs.coerceIn(safeStart, safeDuration)
        if (safeEnd <= safeStart) return AudioFeatureResult(emptyList(), emptyList())

        val arguments = mutableListOf("-hide_banner", "-nostats")
        arguments += inputArguments
        arguments += listOf(
            "-t", seconds(safeEnd - safeStart),
            "-map", "0:a:0?",
            "-vn", "-sn", "-dn",
            "-af",
            "aresample=8000,asetnsamples=n=8000:p=0," +
                "astats=metadata=1:reset=1," +
                "ametadata=print:key=lavfi.astats.Overall.RMS_level," +
                "silencedetect=n=-45dB:d=0.35",
            "-f", "null", "-",
        )
        val session = FFmpegKit.executeWithArguments(arguments.toTypedArray())
        if (!ReturnCode.isSuccess(session.returnCode)) {
            return AudioFeatureResult(emptyList(), emptyList())
        }
        return parseAudioLog(session.allLogsAsString, safeStart, safeEnd)
    }

    private fun seconds(ms: Long): String =
        "%.3f".format(Locale.US, ms.coerceAtLeast(0L) / 1000.0)
}

private fun parseAudioLog(log: String, offsetMs: Long, endMs: Long): AudioFeatureResult {
    val samples = mutableListOf<AudioFeatureSample>()
    val signals = mutableListOf<AutoTrimAudioSignal>()
    var currentPtsSeconds: Double? = null

    log.lineSequence().forEach { line ->
        metadataPtsRegex.find(line)?.groupValues?.getOrNull(1)?.toDoubleOrNull()?.let {
            currentPtsSeconds = it
        }
        rmsRegex.find(line)?.groupValues?.getOrNull(1)?.let { raw ->
            val rms = raw.toDoubleOrNull()
            val pts = currentPtsSeconds
            if (rms != null && rms.isFinite() && pts != null && pts.isFinite()) {
                val absoluteMs = offsetMs + (pts * 1_000.0).roundToLong()
                if (absoluteMs <= endMs) samples += AudioFeatureSample(absoluteMs, rms)
            }
        }
        silenceStartRegex.find(line)?.groupValues?.getOrNull(1)?.toDoubleOrNull()?.let { seconds ->
            signals += AutoTrimAudioSignal(
                timeMs = offsetMs + (seconds * 1_000.0).roundToLong(),
                kind = AutoTrimAudioSignalKind.SILENCE_START,
            )
        }
        silenceEndRegex.find(line)?.groupValues?.getOrNull(1)?.toDoubleOrNull()?.let { seconds ->
            signals += AutoTrimAudioSignal(
                timeMs = offsetMs + (seconds * 1_000.0).roundToLong(),
                kind = AutoTrimAudioSignalKind.SILENCE_END,
            )
        }
    }

    samples.zipWithNext().forEach { (previous, current) ->
        if (
            current.timeMs - previous.timeMs in 1L..3_500L &&
            abs(current.rmsDb - previous.rmsDb) >= AUDIO_LEVEL_JUMP_DB
        ) {
            signals += AutoTrimAudioSignal(
                timeMs = current.timeMs,
                kind = AutoTrimAudioSignalKind.LEVEL_JUMP,
            )
        }
    }

    val dedupedSignals = signals
        .filter { it.timeMs in offsetMs..endMs }
        .sortedBy(AutoTrimAudioSignal::timeMs)
        .fold(mutableListOf<AutoTrimAudioSignal>()) { result, signal ->
            val duplicate = result.lastOrNull()?.let { previous ->
                previous.kind == signal.kind && abs(previous.timeMs - signal.timeMs) <= 400L
            } ?: false
            if (!duplicate) result += signal
            result
        }
    return AudioFeatureResult(samples = samples, signals = dedupedSignals)
}

private class KnownClipFingerprintStore(context: Context) {
    private val file = File(context.filesDir, FILE_NAME)

    fun load(): List<KnownClipFingerprint> {
        if (!file.isFile) return emptyList()
        return runCatching {
            val root = JSONObject(file.readText())
            val items = root.optJSONArray("items") ?: JSONArray()
            buildList {
                for (index in 0 until items.length()) {
                    parseKnown(items.optJSONObject(index))?.let(::add)
                }
            }
        }.getOrDefault(emptyList())
    }

    fun add(fingerprint: KnownClipFingerprint) {
        val current = load().toMutableList()
        current += fingerprint
        val retained = current.sortedByDescending(KnownClipFingerprint::createdAtEpochMs).take(MAX_ITEMS)
        file.parentFile?.mkdirs()
        val root = JSONObject().put(
            "items",
            JSONArray().apply { retained.forEach { put(toJson(it)) } },
        )
        val temp = File(file.parentFile, "${file.name}.tmp")
        temp.writeText(root.toString())
        if (!temp.renameTo(file)) {
            file.writeText(root.toString())
            temp.delete()
        }
    }

    private fun toJson(value: KnownClipFingerprint): JSONObject = JSONObject()
        .put("side", value.side.name)
        .put("durationMs", value.clipDurationMs)
        .put("createdAt", value.createdAtEpochMs)
        .put(
            "visual",
            JSONArray().apply {
                value.visual.forEach { point ->
                    put(JSONObject().put("offset", point.offsetFromEdgeMs).put("hash", point.hash))
                }
            },
        )
        .put(
            "audio",
            JSONArray().apply {
                value.audio.forEach { point ->
                    put(JSONObject().put("offset", point.offsetFromEdgeMs).put("rms", point.rmsDb))
                }
            },
        )

    private fun parseKnown(json: JSONObject?): KnownClipFingerprint? {
        json ?: return null
        val side = runCatching { AutoTrimSide.valueOf(json.getString("side")) }.getOrNull() ?: return null
        val durationMs = json.optLong("durationMs", -1L).takeIf { it > 0L } ?: return null
        val visual = json.optJSONArray("visual").toVisualPoints()
        if (visual.size < 3) return null
        return KnownClipFingerprint(
            side = side,
            clipDurationMs = durationMs,
            visual = visual,
            audio = json.optJSONArray("audio").toAudioPoints(),
            createdAtEpochMs = json.optLong("createdAt", 0L),
        )
    }

    private fun JSONArray?.toVisualPoints(): List<VisualFingerprintPoint> = buildList {
        val array = this@toVisualPoints ?: return@buildList
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val offset = item.optLong("offset", -1L)
            if (offset < 0L || !item.has("hash")) continue
            add(VisualFingerprintPoint(offset, item.optLong("hash")))
        }
    }

    private fun JSONArray?.toAudioPoints(): List<AudioFingerprintPoint> = buildList {
        val array = this@toAudioPoints ?: return@buildList
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val offset = item.optLong("offset", -1L)
            val rms = item.optDouble("rms", Double.NaN)
            if (offset >= 0L && rms.isFinite()) add(AudioFingerprintPoint(offset, rms))
        }
    }

    private companion object {
        const val FILE_NAME = "auto_trim_known_clips.json"
        const val MAX_ITEMS = 40
    }
}

private val metadataPtsRegex = Regex("""pts_time:([0-9]+(?:\.[0-9]+)?)""")
private val rmsRegex = Regex("""lavfi\.astats\.Overall\.RMS_level=([-+]?[0-9]+(?:\.[0-9]+)?)""")
private val silenceStartRegex = Regex("""silence_start:\s*([0-9]+(?:\.[0-9]+)?)""")
private val silenceEndRegex = Regex("""silence_end:\s*([0-9]+(?:\.[0-9]+)?)""")
private const val AUDIO_LEVEL_JUMP_DB = 8.0
