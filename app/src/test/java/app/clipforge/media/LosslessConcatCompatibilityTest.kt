package app.clipforge.media

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class LosslessConcatCompatibilityTest {

    @Test
    fun codecTagDifferenceDoesNotBlockLosslessConcat() {
        val expected = signature(video(codecTag = "avc1"))
        val actual = signature(video(codecTag = "avc3"))

        assertNull(losslessConcatMismatch(expected, actual))
    }

    @Test
    fun h264Mp4ResolutionChangeCanUseConcatAutoConvert() {
        val expected = signature(video(width = 1920, height = 1080))
        val actual = signature(video(width = 1280, height = 720))

        assertNull(losslessConcatMismatch(expected, actual))
    }

    @Test
    fun nonH264ResolutionChangeIsRejected() {
        val expected = signature(video(codec = "hevc", codecTag = "hvc1", width = 1920, height = 1080))
        val actual = signature(video(codec = "hevc", codecTag = "hev1", width = 1280, height = 720))

        assertNotNull(losslessConcatMismatch(expected, actual))
    }

    @Test
    fun timeBaseDifferenceIsRejected() {
        val expected = signature(video(timeBase = "1/90000"))
        val actual = signature(video(timeBase = "1/15360"))

        assertNotNull(losslessConcatMismatch(expected, actual))
    }

    @Test
    fun audioSampleRateDifferenceIsRejected() {
        val expected = signature(video(), audio(sampleRate = 48000))
        val actual = signature(video(), audio(sampleRate = 44100))

        assertNotNull(losslessConcatMismatch(expected, actual))
    }

    private fun signature(vararg streams: StreamSignature): MediaSignature = MediaSignature(
        formatNames = setOf("mov", "mp4", "m4a", "3gp", "3g2", "mj2"),
        streams = streams.toList(),
        durationMs = 10_000,
    )

    private fun video(
        codec: String = "h264",
        codecTag: String = "avc1",
        width: Int = 1920,
        height: Int = 1080,
        timeBase: String = "1/90000",
    ): StreamSignature = StreamSignature(
        type = "video",
        codec = codec,
        codecTag = codecTag,
        width = width,
        height = height,
        sampleRate = null,
        channels = null,
        timeBase = timeBase,
    )

    private fun audio(
        sampleRate: Int = 48000,
        channels: Int = 2,
        timeBase: String = "1/48000",
    ): StreamSignature = StreamSignature(
        type = "audio",
        codec = "aac",
        codecTag = "mp4a",
        width = null,
        height = null,
        sampleRate = sampleRate,
        channels = channels,
        timeBase = timeBase,
    )
}
