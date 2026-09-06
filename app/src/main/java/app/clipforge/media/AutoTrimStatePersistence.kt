package app.clipforge.media

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Small, versioned on-disk snapshot for auto-trim UI/results.
 *
 * The edit session directory is already lifecycle-independent, so keeping this state beside the
 * session avoids tying analysis results to an Activity, Compose composition, or process lifetime.
 */
internal object AutoTrimStatePersistence {
    private const val MAGIC = 0x43464154 // CFAT
    private const val VERSION = 1
    private const val FILE_NAME = ".auto-trim-state-v1.bin"
    private const val TEMP_SUFFIX = ".tmp"

    private const val MAX_CANDIDATES = 32
    private const val MAX_EVIDENCE = 16
    private const val MAX_VISUAL_POINTS = 4_096
    private const val MAX_AUDIO_POINTS = 65_536
    private const val MAX_ERROR_LENGTH = 16_384

    fun save(state: AutoTrimUiState) {
        val sessionPath = state.sessionPath ?: return
        val sessionDir = File(sessionPath)
        if (!sessionDir.isDirectory && !sessionDir.mkdirs()) return

        val target = File(sessionDir, FILE_NAME)
        val temporary = File(sessionDir, FILE_NAME + TEMP_SUFFIX)
        runCatching {
            FileOutputStream(temporary).use { fileOutput ->
                DataOutputStream(BufferedOutputStream(fileOutput)).use { output ->
                    output.writeInt(MAGIC)
                    output.writeInt(VERSION)
                    output.writeBoolean(state.visible)
                    output.writeBoolean(state.running)
                    writeNullableString(output, state.error)
                    output.writeBoolean(state.analysis != null)
                    state.analysis?.let { writeAnalysis(output, it) }
                    output.flush()
                    fileOutput.fd.sync()
                }
            }
            Files.move(
                temporary.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        }.recoverCatching {
            // Some filesystems do not expose ATOMIC_MOVE even inside app-private storage.
            Files.move(
                temporary.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }.onFailure {
            temporary.delete()
        }
    }

    fun load(sessionPath: String): AutoTrimUiState? {
        val target = File(sessionPath, FILE_NAME)
        if (!target.isFile) return null
        return runCatching {
            DataInputStream(BufferedInputStream(FileInputStream(target))).use { input ->
                require(input.readInt() == MAGIC) { "Invalid auto-trim state" }
                require(input.readInt() == VERSION) { "Unsupported auto-trim state version" }
                val visible = input.readBoolean()
                val running = input.readBoolean()
                val error = readNullableString(input)
                val analysis = if (input.readBoolean()) readAnalysis(input) else null
                AutoTrimUiState(
                    sessionPath = sessionPath,
                    visible = visible,
                    running = running,
                    analysis = analysis,
                    error = error,
                )
            }
        }.getOrNull()
    }

    fun delete(sessionPath: String) {
        File(sessionPath, FILE_NAME).delete()
        File(sessionPath, FILE_NAME + TEMP_SUFFIX).delete()
    }

    private fun writeAnalysis(output: DataOutputStream, analysis: AutoTrimAnalysis) {
        writeCandidates(output, analysis.startCandidates)
        writeCandidates(output, analysis.endCandidates)
        writeFingerprint(output, analysis.startFingerprint)
        writeFingerprint(output, analysis.endFingerprint)
        writeSegment(output, analysis.scannedStart)
        writeSegment(output, analysis.scannedEnd)
    }

    private fun readAnalysis(input: DataInputStream): AutoTrimAnalysis = AutoTrimAnalysis(
        startCandidates = readCandidates(input),
        endCandidates = readCandidates(input),
        startFingerprint = readFingerprint(input),
        endFingerprint = readFingerprint(input),
        scannedStart = readSegment(input),
        scannedEnd = readSegment(input),
    )

    private fun writeCandidates(output: DataOutputStream, candidates: List<AutoTrimCandidate>) {
        output.writeInt(candidates.size)
        candidates.forEach { candidate ->
            output.writeUTF(candidate.side.name)
            output.writeLong(candidate.boundaryMs)
            output.writeDouble(candidate.confidence)
            output.writeInt(candidate.evidence.size)
            candidate.evidence.forEach { output.writeUTF(it.name) }
            output.writeBoolean(candidate.knownClipSimilarity != null)
            candidate.knownClipSimilarity?.let(output::writeDouble)
        }
    }

    private fun readCandidates(input: DataInputStream): List<AutoTrimCandidate> {
        val count = checkedCount(input.readInt(), MAX_CANDIDATES)
        return List(count) {
            val side = AutoTrimSide.valueOf(input.readUTF())
            val boundaryMs = input.readLong()
            val confidence = input.readDouble()
            val evidenceCount = checkedCount(input.readInt(), MAX_EVIDENCE)
            val evidence = buildSet {
                repeat(evidenceCount) { add(AutoTrimEvidence.valueOf(input.readUTF())) }
            }
            val similarity = if (input.readBoolean()) input.readDouble() else null
            AutoTrimCandidate(
                side = side,
                boundaryMs = boundaryMs,
                confidence = confidence,
                evidence = evidence,
                knownClipSimilarity = similarity,
            )
        }
    }

    private fun writeFingerprint(output: DataOutputStream, snapshot: EdgeFingerprintSnapshot) {
        output.writeUTF(snapshot.side.name)
        output.writeLong(snapshot.edgeDurationMs)
        output.writeInt(snapshot.visual.size)
        snapshot.visual.forEach { point ->
            output.writeLong(point.offsetFromEdgeMs)
            output.writeLong(point.hash)
        }
        output.writeInt(snapshot.audio.size)
        snapshot.audio.forEach { point ->
            output.writeLong(point.offsetFromEdgeMs)
            output.writeDouble(point.rmsDb)
        }
    }

    private fun readFingerprint(input: DataInputStream): EdgeFingerprintSnapshot {
        val side = AutoTrimSide.valueOf(input.readUTF())
        val edgeDurationMs = input.readLong()
        val visualCount = checkedCount(input.readInt(), MAX_VISUAL_POINTS)
        val visual = List(visualCount) {
            VisualFingerprintPoint(
                offsetFromEdgeMs = input.readLong(),
                hash = input.readLong(),
            )
        }
        val audioCount = checkedCount(input.readInt(), MAX_AUDIO_POINTS)
        val audio = List(audioCount) {
            AudioFingerprintPoint(
                offsetFromEdgeMs = input.readLong(),
                rmsDb = input.readDouble(),
            )
        }
        return EdgeFingerprintSnapshot(
            side = side,
            edgeDurationMs = edgeDurationMs,
            visual = visual,
            audio = audio,
        )
    }

    private fun writeSegment(output: DataOutputStream, segment: MediaSegment) {
        output.writeLong(segment.startMs)
        output.writeLong(segment.endMs)
    }

    private fun readSegment(input: DataInputStream): MediaSegment = MediaSegment(
        startMs = input.readLong(),
        endMs = input.readLong(),
    )

    private fun writeNullableString(output: DataOutputStream, value: String?) {
        output.writeBoolean(value != null)
        if (value != null) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            output.writeInt(bytes.size)
            output.write(bytes)
        }
    }

    private fun readNullableString(input: DataInputStream): String? {
        if (!input.readBoolean()) return null
        val size = checkedCount(input.readInt(), MAX_ERROR_LENGTH * 4)
        val bytes = ByteArray(size)
        input.readFully(bytes)
        return bytes.toString(Charsets.UTF_8).take(MAX_ERROR_LENGTH)
    }

    private fun checkedCount(value: Int, maximum: Int): Int {
        require(value in 0..maximum) { "Corrupt auto-trim state count: $value" }
        return value
    }
}
