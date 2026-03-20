package info.skyblond.recorder.spotify.cli

import se.michaelthelin.spotify.model_objects.specification.Track
import java.io.File

fun String.sanitizeAsFilename() = this
    .replace("/", " ")
    .replace(":", " ")

fun getMusicFile(
    folder: File,
    albumName: String,
    track: Track,
    diskNumber: Int,
    trackNumber: Int,
    trackName: String
) = File(
    File(
        File(
            folder,
            "$albumName - ${track.album.artists.first().name}".sanitizeAsFilename()
        ),
        "Disk $diskNumber"
    ),
    "$trackNumber. ${trackName}.flac".sanitizeAsFilename()
)