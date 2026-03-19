package info.skyblond.recorder.spotify.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.terminal
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.double
import com.github.ajalt.clikt.parameters.types.file
import info.skyblond.recorder.spotify.detector.valley.RmsValleyDetector
import info.skyblond.recorder.spotify.service.ImageDownloadService
import info.skyblond.recorder.spotify.service.MetadataFetcher
import info.skyblond.recorder.spotify.service.TrackInfo
import info.skyblond.recorder.spotify.service.TransitionDetector
import info.skyblond.recorder.spotify.wav.WavSampleReader
import jakarta.inject.Inject
import jakarta.inject.Provider
import net.bramp.ffmpeg.FFmpegExecutor
import net.bramp.ffmpeg.builder.FFmpegBuilder
import se.michaelthelin.spotify.model_objects.specification.Track
import java.io.File
import kotlin.math.abs

/**
 * Slice the recording based on the provided input.
 * */
class SliceCommand @Inject constructor(
    metadataFetcher: Provider<MetadataFetcher>,
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

    private val timestampFile by option("-t", "--timestamps", help = "Timestamp file")
        .file(mustExist = true, canBeDir = false, canBeFile = true)
        .default(File("session_timestamps.txt"))

    private val recordingFile by option("-r", "--recording", help = "Recording file")
        .file(mustExist = true, canBeDir = false, canBeFile = true)
        .default(File("session_record.wav"))

    private val ffmpegLogFile by option("-l", "--ffmpeg-log", help = "FFMpeg log file")
        .file(mustExist = false, canBeDir = false, canBeFile = true)
        .default(File("session_ffmpeg.log"))

    private val searchWindow by option("--search-window", help = "Search window radius around expected positions (seconds)")
        .double()
        .default(10.0)

    override fun run() {
        // ── Parse ffmpeg log for recording start time ──
        echo("Load ffmpeg logs...")
        val startRecordingTime = ffmpegLogFile.readLines()
            .filter { it.contains("Duration: N/A, start: ") }
            .apply { require(size == 1) { "Cannot find start time in ffmpeg log" } }
            .first().split("start: ")[1].split(",")[0].trim().toDouble()

        // ── Read recording file ──
        echo("Reading recording file...")
        val reader = WavSampleReader(recordingFile)
        val recordingDuration = reader.duration

        // ── Parse timestamps ──
        echo("Processing timestamps...")
        val startAndTrackIds = timestampFile.readLines().map {
            val arr = it.split(",")
            val timestamp = arr[0].toDouble()
            val trackId = arr[1].trim()
            (timestamp - startRecordingTime) to trackId
        }.filter { it.first > 0.0 }.sortedBy { it.first }

        // ── Fetch Spotify metadata ──
        val tracksMetadata = metadataFetcher.fetchTracks(startAndTrackIds.map { it.second })
        val startAndTrack = startAndTrackIds.map { (start, trackId) ->
            start to tracksMetadata.find { it.id == trackId }!!
        }

        // ── Pre-filter: identify viable (complete) tracks and build TrackInfo ──
        echo("Detecting track boundaries...")
        val viableIndices = mutableListOf<Int>()
        val trackInfos = mutableListOf<TrackInfo>()

        startAndTrack.forEachIndexed { index, (start, track) ->
            val nextStart = startAndTrack.getOrNull(index + 1)?.first ?: recordingDuration
            val duration = track.durationMs / 1000.0
            val actualDuration = nextStart - start
            if (abs(actualDuration - duration) <= 2.5) {
                viableIndices.add(index)
                trackInfos.add(TrackInfo(dbusOffset = start, spotifyDurationSec = duration))
            } else {
                echo(
                    terminal.theme.warning(
                        "Pre-filter: skipping incomplete track '${track.name}' " +
                                "(recording time $actualDuration < track duration $duration)"
                    )
                )
            }
        }

        // ── Run adaptive boundary detection ──
        val transitionDetector = TransitionDetector(
            valleyDetector = RmsValleyDetector(),
            searchWindow = searchWindow,
        )
        val refinedResults = transitionDetector.refineStartTimes(reader, trackInfos)
        val resultMap = viableIndices.zip(refinedResults).toMap()

        // ── Process each track ──
        echo("Processing recording file...")
        startAndTrack.forEachIndexed { index, (start, track) ->
            if (index !in resultMap) return@forEachIndexed // Skipped during pre-filter

            val result = resultMap[index]
            if (result == null) {
                // Valley pairing failed — skip this track
                echo(
                    terminal.theme.warning(
                        "Skipping '${track.name}': could not find valid valley pair. " +
                                "Try placing non-gapless tracks around this song and re-record."
                    )
                )
                return@forEachIndexed
            }

            val duration = track.durationMs / 1000.0
            val trackName = track.name
            val artistsStr = track.artists.joinToString(", ") { it.name }
            val albumName = track.album.name
            val albumImageUrl = track.album.images.maxByOrNull { it.height }?.url!!
            val diskNumber = track.discNumber // start with 1
            val trackNumber = track.trackNumber // start with 1

            echo(
                terminal.theme.info(
                    "Processing track '$trackName' from album '$albumName' by '$artistsStr' (Disk $diskNumber, Track $trackNumber)"
                )
            )
            echo(terminal.theme.info("Spotify track id: ${track.id}"))

            // Safety check: enough recording remaining for this track
            val ss = result.cutPoint
            if (ss + duration > recordingDuration) {
                echo(
                    terminal.theme.warning("Not enough recording remaining for track: $trackName")
                )
                return@forEachIndexed
            }

            echo(terminal.theme.info("DBus recorded start: $start"))
            echo(terminal.theme.info("Refined start: $ss (${result.confidence})"))
            echo(terminal.theme.info("Detection: ${result.message}"))

            val contentFile = getMusicFile(contentFolder, albumName, track, diskNumber, trackNumber, trackName)
            if (contentFile.exists()) {
                echo(
                    terminal.theme.warning(
                        "File already exists in content folder: ${contentFile.absolutePath}"
                    )
                )
                return@forEachIndexed
            }

            val outputFile = getMusicFile(outputFolder, albumName, track, diskNumber, trackNumber, trackName)
            outputFile.parentFile.mkdirs()

            if (outputFile.exists()) {
                echo(
                    terminal.theme.warning(
                        "File already exists in output folder: ${outputFile.absolutePath}"
                    )
                )
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
                // do not add dither for lossless
                .addExtraArgs("-dither_method", "none")
                .addExtraArgs("-af", "volume=-0.5dB")
                // metadata
                .addExtraArgs("-metadata", "title=$trackName")
                .addExtraArgs("-metadata", "artist=$artistsStr")
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
            echo(
                terminal.theme.success(
                    "Finished processing. File saved to: ${outputFile.absolutePath}"
                )
            )
        }

        // ── Summary of skipped tracks ──
        val skippedTracks = viableIndices.zip(refinedResults)
            .filter { (_, result) -> result == null }
        if (skippedTracks.isNotEmpty()) {
            echo(
                terminal.theme.warning(
                    "\n${skippedTracks.size} track(s) skipped (no valid valley pair found):"
                )
            )
            skippedTracks.forEach { (originalIndex, _) ->
                val track = startAndTrack[originalIndex].second
                echo(
                    terminal.theme.warning(
                        "  - ${track.name} (${track.artists.first().name})"
                    )
                )
            }
            echo(
                terminal.theme.warning(
                    "Tip: place non-gapless tracks around these songs and re-record."
                )
            )
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
