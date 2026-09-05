package app.clipforge.media

import org.junit.Assert.assertEquals
import org.junit.Test

class AttachedPictureHandlingTest {

    @Test
    fun attachedPictureIsExcludedFromPlaybackVideos() {
        val mainVideo = video(codec = "h264")
        val jacket = video(codec = "mjpeg", attachedPic = true)
        val signature = signature(mainVideo, jacket)

        assertEquals(listOf(mainVideo), signature.playbackVideoStreams())
    }

    @Test
    fun realSecondVideoTrackIsStillRejectedByCount() {
        val signature = signature(video(codec = "h264"), video(codec = "hevc"))

        assertEquals(2, signature.playbackVideoStreams().size)
    }

    @Test
    fun jacketBeforeMainVideoDoesNotBecomePlaybackVideo() {
        val jacket = video(codec = "png", attachedPic = true)
        val mainVideo = video(codec = "hevc")
        val signature = signature(jacket, mainVideo)

        assertEquals(listOf(mainVideo), signature.playbackVideoStreams())
    }

    private fun signature(vararg streams: StreamSignature): MediaSignature = MediaSignature(
        formatNames = setOf("mp4"),
        streams = streams.toList(),
        durationMs = 10_000,
    )

    private fun video(
        codec: String,
        attachedPic: Boolean = false,
    ): StreamSignature = StreamSignature(
        type = "video",
        codec = codec,
        codecTag = null,
        width = 1920,
        height = 1080,
        sampleRate = null,
        channels = null,
        timeBase = "1/90000",
        attachedPic = attachedPic,
    )
}
