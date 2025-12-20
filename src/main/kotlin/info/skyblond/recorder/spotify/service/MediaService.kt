package info.skyblond.recorder.spotify.service

import jakarta.inject.Inject
import net.bramp.ffmpeg.FFmpeg
import net.bramp.ffmpeg.FFprobe
import net.bramp.ffmpeg.probe.FFmpegStream
import java.io.File

class MediaService @Inject constructor(
    private val ffmpeg: FFmpeg,
    private val ffprobe: FFprobe
) {
    fun probeMusicDuration(file: File): Double {
        val probe = ffprobe.probe(file.absolutePath)
        return probe.streams.find { it.codec_type == FFmpegStream.CodecType.AUDIO }?.duration
            ?: error("No audio stream found")
    }
}