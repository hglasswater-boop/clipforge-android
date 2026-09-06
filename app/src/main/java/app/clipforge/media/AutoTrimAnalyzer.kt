package app.clipforge.media

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import com.arthenica.ffmpegkit.ReturnCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.Locale
import kotlin.coroutines.coroutineContext
import kotlin.math.abs
import kotlin.math.max
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

private data class FrameSignature(
    val hash: Long,
    val averageLuma: Int,
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
        edgeWindowMs: Long = AutoTrimRangeSettings.DEFAULT_EDGE_WINDOW_MS,
        onProgress: (AutoTrimProgress) -> Unit = {},
    ): AutoTrimAnalysis = withContext(Dispatchers.IO) {
        require(durationMs > 1L) { "動画の長さを取得できません" }
        val tracker = AutoTrimProgressTracker()
        fun emit(phase: AutoTrimPhase, fraction: Double) {
            onProgress(tracker.update(phase, fraction))
        }

        emit(AutoTrimPhase.PREPARING, 0.0)
        val resolvedEdgeWindowMs = edgeWindowFor(durationMs, edgeWindowMs)
        val startRange = MediaSegment(0L, resolvedEdgeWindowMs)
        val endRange = MediaSegment((durationMs - resolvedEdgeWindowMs).coerceAtLeast(0L), durationMs)
        emit(AutoTrimPhase.PREPARING, 1.0)

        // Audio is cheap at 8 kHz, so keep the complete edge-window scan for reliable silence and
        // level-boundary evidence. Expensive video decoding is handled sparsely below.
        val startAudio = detectAudio(
            sourceUri = sourceUri,
            localInputPath = localInputPath,
            durationMs = durationMs,
            range = startRange,
            onProgress = { emit(AutoTrimPhase.START_AUDIO, it) },
        )
        val endAudio = detectAudio(
            sourceUri = sourceUri,
            localInputPath = localInputPath,
            durationMs = durationMs,
            range = endRange,
            onProgress = { emit(AutoTrimPhase.END_AUDIO, it) },
        )

        // Reuse these sparse sync frames both for learned fingerprints and for coarse scene hints.
        // Do not alternate start/end seeks: sequential edge reads are substantially friendlier to
        // SMB and Content URI providers.
        val visual = sampleSparseVisualEdges(
            sourceUri = sourceUri,
            localInputPath = localInputPath,
            durationMs = durationMs,
            edgeWindowMs = resolvedEdgeWindowMs,
            onProgress = { emit(AutoTrimPhase.VISUAL_FINGERPRINT, it) },
        )
        val startFingerprint = EdgeFingerprintSnapshot(
            side = AutoTrimSide.START,
            edgeDurationMs = resolvedEdgeWindowMs,
            visual = visual.first.map { sample ->
                VisualFingerprintPoint(
                    offsetFromEdgeMs = sample.offsetFromEdgeMs,
                    hash = sample.hash,
                )
            },
            audio = startAudio.samples.map {
                AudioFingerprintPoint(
                    offsetFromEdgeMs = it.timeMs,
                    rmsDb = it.rmsDb,
                )
            },
        )
        val endFingerprint = EdgeFingerprintSnapshot(
            side = AutoTrimSide.END,
            edgeDurationMs = resolvedEdgeWindowMs,
            visual = visual.second.map { sample ->
                VisualFingerprintPoint(
                    offsetFromEdgeMs = sample.offsetFromEdgeMs,
                    hash = sample.hash,
                )
            },
            audio = endAudio.samples.map {
                AudioFingerprintPoint(
                    offsetFromEdgeMs = (durationMs - it.timeMs).coerceAtLeast(0L),
                    rmsDb = it.rmsDb,
                )
            }.sortedBy(AudioFingerprintPoint::offsetFromEdgeMs),
        )

        emit(AutoTrimPhase.KNOWN_CLIP_MATCH, 0.0)
        val known = knownStore.load()
        emit(AutoTrimPhase.KNOWN_CLIP_MATCH, 0.25)
        val startKnown = bestKnownMatch(startFingerprint, known, durationMs)
        emit(AutoTrimPhase.KNOWN_CLIP_MATCH, 0.65)
        val endKnown = bestKnownMatch(endFingerprint, known, durationMs)
        emit(AutoTrimPhase.KNOWN_CLIP_MATCH, 1.0)

        // The old path decoded the complete start and end windows. Instead, sparse dHash/luma
        // changes plus audio boundaries select at most a few short windows for precise decoding.
        // A very strong learned-clip match can skip this stage entirely for that edge.
        val startWindows = buildSceneRefinementWindows(
            range = startRange,
            visualHints = visualRefinementHints(AutoTrimSide.START, durationMs, visual.first),
            audioSignals = startAudio.signals,
            knownMatch = startKnown,
        )
        val startScenes = detectSceneWindows(
            sourceUri = sourceUri,
            localInputPath = localInputPath,
            durationMs = durationMs,
            edgeRange = startRange,
            windows = startWindows,
            onProgress = { emit(AutoTrimPhase.START_SCENE, it) },
        )

        val endWindows = buildSceneRefinementWindows(
            range = endRange,
            visualHints = visualRefinementHints(AutoTrimSide.END, durationMs, visual.second),
            audioSignals = endAudio.signals,
            knownMatch = endKnown,
        )
        val endScenes = detectSceneWindows(
            sourceUri = sourceUri,
            localInputPath = localInputPath,
            durationMs = durationMs,
            edgeRange = endRange,
            windows = endWindows,
            onProgress = { emit(AutoTrimPhase.END_SCENE, it) },
        )

        emit(AutoTrimPhase.RANKING, 0.0)
        val startCandidates = rankAutoTrimCandidates(
            side = AutoTrimSide.START,
            durationMs = durationMs,
            windowStartMs = startRange.startMs,
            windowEndMs = startRange.endMs,
            sceneMarkers = startScenes.markers,
            audioSignals = startAudio.signals,
            knownClipMatch = startKnown,
        )
        emit(AutoTrimPhase.RANKING, 0.5)
        val endCandidates = rankAutoTrimCandidates(
            side = AutoTrimSide.END,
            durationMs = durationMs,
            windowStartMs = endRange.startMs,
            windowEndMs = endRange.endMs,
            sceneMarkers = endScenes.markers,
            audioSignals = endAudio.signals,
            knownClipMatch = endKnown,
        )
        emit(AutoTrimPhase.RANKING, 1.0)

        val result = AutoTrimAnalysis(
            startCandidates = startCandidates,
            endCandidates = endCandidates,
            startFingerprint = startFingerprint,
            endFingerprint = endFingerprint,
            scannedStart = startRange,
            scannedEnd = endRange,
        )
        emit(AutoTrimPhase.COMPLETE, 1.0)
        result
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

    private suspend fun detectSceneWindows(
        sourceUri: String,
        localInputPath: String?,
        durationMs: Long,
        edgeRange: MediaSegment,
        windows: List<MediaSegment>,
        onProgress: (Double) -> Unit,
    ): SceneDetectionResult {
        if (windows.isEmpty()) {
            onProgress(1.0)
            return SceneDetectionResult(emptyList(), edgeRange.startMs, edgeRange.endMs)
        }

        val totalWorkMs = windows.sumOf { (it.endMs - it.startMs).coerceAtLeast(0L) }.coerceAtLeast(1L)
        var completedWorkMs = 0L
        val markers = mutableListOf<SceneMarker>()
        onProgress(0.0)

        windows.forEach { window ->
            coroutineContext.ensureActive()
            val windowWorkMs = (window.endMs - window.startMs).coerceAtLeast(1L)
            val result = detectScenes(
                sourceUri = sourceUri,
                localInputPath = localInputPath,
                durationMs = durationMs,
                range = window,
                onProgress = { fraction ->
                    val done = completedWorkMs + (windowWorkMs * fraction.coerceIn(0.0, 1.0)).roundToLong()
                    onProgress(done.toDouble() / totalWorkMs.toDouble())
                },
            )
            markers += result.markers
            completedWorkMs += windowWorkMs
            onProgress(completedWorkMs.toDouble() / totalWorkMs.toDouble())
        }

        return SceneDetectionResult(
            markers = markers.sortedWith(compareBy(SceneMarker::timeMs, SceneMarker::kind)),
            scannedStartMs = edgeRange.startMs,
            scannedEndMs = edgeRange.endMs,
        )
    }

    private suspend fun detectScenes(
        sourceUri: String,
        localInputPath: String?,
        durationMs: Long,
        range: MediaSegment,
        onProgress: (Double) -> Unit,
    ): SceneDetectionResult = runCatching {
        if (localInputPath != null) {
            sceneDetector.detectPath(
                path = localInputPath,
                durationMs = durationMs,
                startMs = range.startMs,
                endMs = range.endMs,
                mode = SceneScanMode.PRECISE,
                onProgress = onProgress,
            )
        } else {
            // Open a new descriptor for every precise window. Never share a file position across
            // FFmpeg sessions, especially for SMB-backed Content URIs.
            openSeekable(sourceUri).use { descriptor ->
                sceneDetector.detectDescriptor(
                    fd = descriptor.fd,
                    durationMs = durationMs,
                    startMs = range.startMs,
                    endMs = range.endMs,
                    mode = SceneScanMode.PRECISE,
                    onProgress = onProgress,
                )
            }
        }
    }.getOrElse { error ->
        if (error is CancellationException) throw error
        onProgress(1.0)
        SceneDetectionResult(emptyList(), range.startMs, range.endMs)
    }

    private suspend fun detectAudio(
        sourceUri: String,
        localInputPath: String?,
        durationMs: Long,
        range: MediaSegment,
        onProgress: (Double) -> Unit,
    ): AudioFeatureResult = runCatching {
        if (localInputPath != null) {
            audioDetector.detectPath(
                path = localInputPath,
                durationMs = durationMs,
                startMs = range.startMs,
                endMs = range.endMs,
                onProgress = onProgress,
            )
        } else {
            openSeekable(sourceUri).use { descriptor ->
                audioDetector.detectDescriptor(
                    fd = descriptor.fd,
                    durationMs = durationMs,
                    startMs = range.startMs,
                    endMs = range.endMs,
                    onProgress = onProgress,
                )
            }
        }
    }.getOrElse { error ->
        if (error is CancellationException) throw error
        onProgress(1.0)
        AudioFeatureResult(emptyList(), emptyList())
    }

    private suspend fun sampleSparseVisualEdges(
        sourceUri: String,
        localInputPath: String?,
        durationMs: Long,
        edgeWindowMs: Long,
        onProgress: (Double) -> Unit,
    ): Pair<List<SparseVisualSample>, List<SparseVisualSample>> {
        val retriever = MediaMetadataRetriever()
        return try {
            if (localInputPath != null) {
                retriever.setDataSource(localInputPath)
            } else {
                retriever.setDataSource(appContext, Uri.parse(sourceUri))
            }
            val intervalMs = max(MIN_VISUAL_SAMPLE_INTERVAL_MS, edgeWindowMs / MAX_VISUAL_SAMPLES_PER_EDGE)
            val offsets = buildList {
                var offset = 0L
                while (offset <= edgeWindowMs) {
                    add(offset)
                    if (edgeWindowMs - offset < intervalMs) break
                    offset += intervalMs
                }
            }
            val totalFrames = (offsets.size * 2).coerceAtLeast(1)
            var completedFrames = 0
            val start = mutableListOf<SparseVisualSample>()
            val end = mutableListOf<SparseVisualSample>()
            onProgress(0.0)

            // Keep physical access moving forward through the start of the file.
            offsets.forEach { offset ->
                coroutineContext.ensureActive()
                frameSignature(retriever, offset)?.let { signature ->
                    start += SparseVisualSample(
                        offsetFromEdgeMs = offset,
                        hash = signature.hash,
                        averageLuma = signature.averageLuma,
                    )
                }
                completedFrames += 1
                onProgress(completedFrames.toDouble() / totalFrames.toDouble())
            }

            // Walk the end window in chronological file order. The stored offset remains measured
            // backwards from the end so learned fingerprints keep the existing representation.
            offsets.asReversed().forEach { offset ->
                coroutineContext.ensureActive()
                val endTime = (durationMs - offset).coerceAtLeast(0L)
                frameSignature(retriever, endTime)?.let { signature ->
                    end += SparseVisualSample(
                        offsetFromEdgeMs = offset,
                        hash = signature.hash,
                        averageLuma = signature.averageLuma,
                    )
                }
                completedFrames += 1
                onProgress(completedFrames.toDouble() / totalFrames.toDouble())
            }
            start.sortedBy(SparseVisualSample::offsetFromEdgeMs) to
                end.sortedBy(SparseVisualSample::offsetFromEdgeMs)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            onProgress(1.0)
            emptyList<SparseVisualSample>() to emptyList()
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun frameSignature(retriever: MediaMetadataRetriever, timeMs: Long): FrameSignature? {
        val bitmap = retriever.getFrameAtTime(
            timeMs.coerceAtLeast(0L) * 1_000L,
            MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
        ) ?: return null
        return try {
            val scaled = android.graphics.Bitmap.createScaledBitmap(bitmap, HASH_WIDTH, HASH_HEIGHT, true)
            try {
                val luma = IntArray(HASH_WIDTH * HASH_HEIGHT)
                var sum = 0L
                var index = 0
                for (y in 0 until HASH_HEIGHT) {
                    for (x in 0 until HASH_WIDTH) {
                        val value = luminance(scaled.getPixel(x, y))
                        luma[index++] = value
                        sum += value
                    }
                }

                var hash = 0L
                var bit = 0
                for (y in 0 until HASH_HEIGHT) {
                    val row = y * HASH_WIDTH
                    for (x in 0 until HASH_WIDTH - 1) {
                        if (luma[row + x] > luma[row + x + 1]) hash = hash or (1L shl bit)
                        bit += 1
                    }
                }
                FrameSignature(
                    hash = hash,
                    averageLuma = (sum / luma.size.coerceAtLeast(1)).toInt(),
                )
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

    private fun edgeWindowFor(durationMs: Long, requestedEdgeWindowMs: Long): Long {
        val requested = normalizeAutoTrimEdgeWindowMs(requestedEdgeWindowMs)
            .coerceAtLeast(MIN_EDGE_WINDOW_MS)
        return requested.coerceAtMost((durationMs / 2L).coerceAtLeast(1L))
    }

    private companion object {
        const val MIN_EDGE_WINDOW_MS = 30_000L
        const val MIN_VISUAL_SAMPLE_INTERVAL_MS = 7_500L
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
        onProgress: (Double) -> Unit = {},
    ): AudioFeatureResult = withContext(Dispatchers.IO) {
        detect(
            inputArguments = listOf("-ss", seconds(startMs), "-i", path),
            durationMs = durationMs,
            startMs = startMs,
            endMs = endMs,
            onProgress = onProgress,
        )
    }

    suspend fun detectDescriptor(
        fd: Int,
        durationMs: Long,
        startMs: Long,
        endMs: Long,
        onProgress: (Double) -> Unit = {},
    ): AudioFeatureResult = withContext(Dispatchers.IO) {
        detect(
            inputArguments = listOf("-ss", seconds(startMs), "-fd", fd.toString(), "-i", "fd:"),
            durationMs = durationMs,
            startMs = startMs,
            endMs = endMs,
            onProgress = onProgress,
        )
    }

    private suspend fun detect(
        inputArguments: List<String>,
        durationMs: Long,
        startMs: Long,
        endMs: Long,
        onProgress: (Double) -> Unit,
    ): AudioFeatureResult {
        val safeDuration = durationMs.coerceAtLeast(1L)
        val safeStart = startMs.coerceIn(0L, safeDuration)
        val safeEnd = endMs.coerceIn(safeStart, safeDuration)
        if (safeEnd <= safeStart) {
            onProgress(1.0)
            return AudioFeatureResult(emptyList(), emptyList())
        }

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
        val session = executeCancellableFfmpeg(
            arguments = arguments,
            expectedDurationMs = safeEnd - safeStart,
            onProgress = onProgress,
        )
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
