package info.skyblond.recorder.spotify.dagger

import dagger.Component
import info.skyblond.recorder.spotify.cli.RootCommand
import info.skyblond.recorder.spotify.service.ImageDownloadService
import info.skyblond.recorder.spotify.service.MetadataFetcher
import jakarta.inject.Singleton
import se.michaelthelin.spotify.SpotifyApi

@Singleton
@Component(modules = [SubCommandsModule::class, Providers::class])
interface App {
    fun rootCommand(): RootCommand
}