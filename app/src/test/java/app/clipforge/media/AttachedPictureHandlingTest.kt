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
    fun attachedPictureMappingKeepsOriginalStreamOrder() {
        val mainVideo = video(codec = "h264")
        val jacket = video(codec = "mjpeg", attachedPic = true)
        val audio = StreamSignature(
            type = "audio",
            codec = "aac",
            codecTag = null,
            width = null,
            height = null,
            sampleRate = 48_000,
            channels = 2,
            timeBase = "1/48000",
        )
        val signature = signature(mainVideo, jacket, audio)

        assertEquals(
            listOf(
                "-map", "0:0",
                "-map", "1:1",
                "-disposition:v:1", "attached_pic",
                "-map", "0:2",
            ),
            preservedStreamMapArguments(signature, primaryInputIndex = 0, jacketInputIndex = 1),
        )
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
