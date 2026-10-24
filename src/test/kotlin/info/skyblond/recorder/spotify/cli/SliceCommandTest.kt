package info.skyblond.recorder.spotify.cli

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class SliceCommandTest {

    /**
     * Pins the exact ffmpeg argument list produced for a track cut.
     *
     * The ffmpeg wrapper library builds the argv itself, so a library upgrade can
     * silently reorder or drop arguments (0.9.x, for instance, reworked input
     * handling around `addInput`/`done`). This test makes such a change visible.
     */
    @Test
    fun `slice command keeps argument order`() {
        val args = buildSliceCommand(
            recordingFile = File("/tmp/session_record.wav"),
            albumImage = File("/tmp/cover.jpg"),
            outputFile = File("/tmp/Album/01. Song.flac"),
            startSeconds = 12.3456,
            durationSeconds = 200.0,
            trackName = "Song",
            artistsStr = "Artist A; Artist B",
            albumName = "Album",
            trackNumber = 3,
            discNumber = 1,
            spotifyTrackId = "track123",
        ).build()

        assertEquals(
            listOf(
                // global flags
                "-y", "-v", "error",
                // cut window, must precede the inputs so it applies to input 0
                "-ss", "%.3f".format(12.3456),
                "-t", "%.3f".format(200.0),
                // input 0: recording, input 1: album image
                "-i", "/tmp/session_record.wav",
                "-i", "/tmp/cover.jpg",
                // audio encoding
                "-c:a", "flac",
                "-sample_fmt", "s32",
                "-compression_level", "8",
                "-dither_method", "none",
                "-af", "volume=-0.5dB",
                // metadata
                "-metadata", "title=Song",
                "-metadata", "artist=Artist A; Artist B",
                "-metadata", "album=Album",
                "-metadata", "track=3",
                "-metadata", "disc=1",
                "-metadata", "spotifyTrackId=track123",
                // album image mapping
                "-map", "0:a",
                "-map", "1:0",
                "-c:v", "copy",
                "-disposition:v", "attached_pic",
                // output
                "/tmp/Album/01. Song.flac",
            ),
            args,
        )
    }
}
