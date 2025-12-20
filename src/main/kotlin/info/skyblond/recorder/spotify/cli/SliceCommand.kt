package info.skyblond.recorder.spotify.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.double
import com.github.ajalt.clikt.parameters.types.file
import info.skyblond.recorder.spotify.dagger.DaggerApp
import info.skyblond.recorder.spotify.service.ImageDownloadService
import info.skyblond.recorder.spotify.service.MediaService
import info.skyblond.recorder.spotify.service.MetadataFetcher
import jakarta.inject.Inject
import jakarta.inject.Provider
import net.bramp.ffmpeg.FFmpegExecutor
import net.bramp.ffmpeg.builder.FFmpegBuilder
import se.michaelthelin.spotify.model_objects.specification.Track
import java.io.File

/**
 * Slice the recording based on the provided input.
 * */
class SliceCommand @Inject constructor(
    metadataFetcher: Provider<MetadataFetcher>,
    private val mediaService: MediaService,
    private val ffmpegExecutor: FFmpegExecutor,
    private val imageDownloadService: ImageDownloadService,
) : CliktCommand(name = "slice") {
    private val metadataFetcher by lazy { metadataFetcher.get() }

    private val outputFolder by option("-o", "--output", help = "Output folder for processed files")
        .file(mustExist = false, canBeDir = true, canBeFile = false)
        .required()

    private val contentFolder by option("-c", "--content", help = "Content folder for existing library")
        .file(mustExist = false, canBeDir = true, canBeFile = false)
        .required()

    private val timingCorrection by option("-a", "--adjustment", help = "Timing adjustment in seconds")
        .double()
        .default(0.0)

    private val timestampFile by option("-t", "--timestamps", help = "Timestamp file")
        .file(mustExist = true, canBeDir = false, canBeFile = true)
        .default(File("session_timestamps.txt"))

    private val recordingFile by option("-r", "--recording", help = "Recording file")
        .file(mustExist = true, canBeDir = false, canBeFile = true)
        .default(File("session_record.wav"))

    private val ffmpegLogFile by option("-l", "--ffmpeg-log", help = "FFMpeg log file")
        .file(mustExist = false, canBeDir = false, canBeFile = true)
        .default(File("session_ffmpeg.log"))


    override fun run() {
        echo("Load files...")
        val startRecordingTime = ffmpegLogFile.readLines()
            .filter { it.contains("Duration: N/A, start: ") }
            .apply { require(size == 1) { "Cannot find start time in ffmpeg log" } }
            .first().split("start: ")[1].split(",")[0].trim().toDouble()

        // start, end
        echo("Reading recording file...")
        val recordingDuration = mediaService.probeMusicDuration(recordingFile)

        echo("Processing tracks metadata...")
        val startAndTrackIds = timestampFile.readLines().map {
            val arr = it.split(",")
            val timestamp = arr[0].toDouble()
            val trackId = arr[1].trim()
            (timestamp - startRecordingTime) to trackId
        }.sortedBy { it.first }

        val tracksMetadata = metadataFetcher.fetchTracks(startAndTrackIds.map { it.second })

        val startAndTrack = startAndTrackIds.map { (start, trackId) ->
            start to tracksMetadata.find { it.id == trackId }!!
        }

        echo("Processing recording file...")
        startAndTrack.forEachIndexed { index, (start, track) ->
            val nextItemStart = startAndTrack.getOrNull(index + 1)?.first ?: recordingDuration
            val recordingTime = nextItemStart - start
            val duration = track.durationMs / 1000.0
            val trackName = track.name
            val artistsStr = track.artists.joinToString(", ") { it.name }
            val albumName = track.album.name
            val albumImageUrl = track.album.images.maxByOrNull { it.height }?.url!!
            val diskNumber = track.discNumber // start with 1
            val trackNumber = track.trackNumber // start with 1

            echo("Processing track '$trackName' from album '$albumName' by '$artistsStr' (Disk ${diskNumber}, Track $trackNumber)")

            if (recordingTime < duration) {
                // TODO warning
                echo("Recording time is less than track duration, skip this track: $trackName")
                return@forEachIndexed
            }

            val recordDurationDiff = recordingTime - duration
            echo("Recording time diff: $recordDurationDiff")
            if (recordDurationDiff >= 1.0) {
                // TODO warning
                echo("Recording time diff bigger than 1s, skip this track: $trackName")
                return@forEachIndexed
            }

            // decide the start
            val ss = start + timingCorrection
            echo("DBus recorded start: $start")
            echo("Chosen start: $ss")

            val contentFile = getMusicFile(contentFolder, albumName, track, diskNumber, trackNumber, trackName)
            if (contentFile.exists()) {
                // TODO warning
                echo("File already exists in content folder: ${contentFile.absolutePath}")
                return@forEachIndexed
            }

            val outputFile = getMusicFile(outputFolder, albumName, track, diskNumber, trackNumber, trackName)
            outputFile.parentFile.mkdirs()

            if (outputFile.exists()) {
                // TODO warning
                echo("File already exists in output folder: ${outputFile.absolutePath}")
                return@forEachIndexed
            }

            val albumImage = imageDownloadService.downloadAlbumImage(albumImageUrl)
            val ffmpegCmd = FFmpegBuilder()
                .overrideOutputFiles(true)
                .setVerbosity(FFmpegBuilder.Verbosity.ERROR)
                .addExtraArgs("-ss", "%.3f".format(ss))
                .addExtraArgs("-t", "%.3f".format(duration))
                .addInput(recordingFile.absolutePath) // input 0
                .addInput(albumImage.absolutePath) // input 1

                .addOutput(outputFile.absolutePath)
                .addExtraArgs("-c:a", "flac")
                .addExtraArgs("-sample_fmt", "s32")
                .addExtraArgs("-compression_level", "8")
                .addExtraArgs("-metadata", "title=$trackName")
                .addExtraArgs("-metadata", "artist=${artistsStr}")
                .addExtraArgs("-metadata", "album=$albumName")
                .addExtraArgs("-metadata", "track=$trackNumber")
                .addExtraArgs("-metadata", "disc=$diskNumber")
                .addExtraArgs("-metadata", "spotifyTrackId=${track.id}")
                // album image
                .addExtraArgs("-map", "0:a") // read audio from input 0
                .addExtraArgs("-map", "1:0") // read pic from input 1
                .addExtraArgs("-c:v", "copy") // copy the video stream (album pic)
                .addExtraArgs("-disposition:v", "attached_pic") // treat video stream as album pic
                .done()
            ffmpegExecutor.createJob(ffmpegCmd).run()
            albumImage.delete()
        }
    }

    private fun getMusicFile(
        folder: File, albumName: String, track: Track, diskNumber: Int, trackNumber: Int, trackName: String
    ) = File(
        File(
            File(folder, "$albumName (${track.album.artists.first().name})".replace("/", "-")),
            "Disk $diskNumber"
        ),
        "$trackNumber. ${trackName.replace("/", "-")}.flac"
    )
}