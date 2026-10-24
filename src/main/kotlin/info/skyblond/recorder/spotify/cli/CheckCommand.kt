package info.skyblond.recorder.spotify.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.file
import info.skyblond.recorder.spotify.service.MetadataFetcher

/**
 * Check the given spotify id and see if the content has already been ripped.
 * */
class CheckCommand(
    metadataFetcher: Lazy<MetadataFetcher>,
) : CliktCommand(name = "check") {
    private val metadataFetcher by metadataFetcher

    private val spotifyId by option(
        "--id", help = "Spotify track id"
    ).required()

    private val contentFolders by option(
        "-f", help = "Content folder for existing library"
    ).file(mustExist = false, canBeDir = true, canBeFile = false)
        .multiple()


    override fun run() {
        val track = metadataFetcher.fetchTracks(listOf(spotifyId)).first()
        val trackName = track.name
        val albumName = track.album.name
        val diskNumber = track.discNumber // start with 1
        val trackNumber = track.trackNumber // start with 1

        val found = contentFolders.map {
            getMusicFile(
                it, albumName, track, diskNumber, trackNumber, trackName
            )
        }.any { it.exists() }
        if (found) {
            echo("Already exists.")
        } else {
            echo("Not found.")
        }
    }
}
