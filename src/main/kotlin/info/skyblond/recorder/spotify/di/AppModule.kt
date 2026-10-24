package info.skyblond.recorder.spotify.di

import com.github.ajalt.clikt.core.CliktCommand
import info.skyblond.recorder.spotify.cli.CheckCommand
import info.skyblond.recorder.spotify.cli.InitCommand
import info.skyblond.recorder.spotify.cli.LoginCommand
import info.skyblond.recorder.spotify.cli.LogoutCommand
import info.skyblond.recorder.spotify.cli.RootCommand
import info.skyblond.recorder.spotify.cli.SliceCommand
import info.skyblond.recorder.spotify.config.ConfigStore
import info.skyblond.recorder.spotify.service.ImageDownloadService
import info.skyblond.recorder.spotify.service.MetadataFetcher
import net.bramp.ffmpeg.FFmpeg
import net.bramp.ffmpeg.FFmpegExecutor
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.bind
import org.koin.dsl.module
import se.michaelthelin.spotify.SpotifyApi
import java.net.URI
import java.net.http.HttpClient

/**
 * The Koin container's module: the whole application object graph.
 *
 * The module is parameterized by the [ConfigStore] created in `Main.kt`.
 * The store's config file path is a CLI option handled by [RootCommand],
 * so every definition that touches the config (the Spotify API client)
 * must stay lazy until a subcommand actually runs. Koin singletons are
 * lazy by default; the commands receive `inject()` (`Lazy`) for those
 * definitions, the Koin equivalent of Dagger's `Provider`.
 */
fun appModule(configStore: ConfigStore): Module = module {
    single<ConfigStore> { configStore }

    single<HttpClient> {
        HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()
    }

    // lazy: created on first resolution, after RootCommand has set the
    // config file path; refreshes the access token when one is stored
    single<SpotifyApi> { createSpotifyApi(get()) }

    single(named("spotifyApiHeader")) {
        mapOf(
            "accept-language" to "zh-CN,zh;q=0.9,zh-Hans;q=0.8,en;q=0.7"
        )
    }

    single<FFmpeg> { FFmpeg() }
    single<FFmpegExecutor> { FFmpegExecutor(get()) }

    single { ImageDownloadService(get()) }
    single { MetadataFetcher(get(), get(named("spotifyApiHeader"))) }

    // subcommands are bound as CliktCommand so RootCommand can collect
    // them with getAll(), the Koin equivalent of Dagger's @IntoSet
    single { InitCommand(get()) } bind CliktCommand::class
    single { LoginCommand(inject(), get()) } bind CliktCommand::class
    single { LogoutCommand(get()) } bind CliktCommand::class
    single { SliceCommand(inject(), inject(), get()) } bind CliktCommand::class
    single { CheckCommand(inject()) } bind CliktCommand::class

    single { RootCommand(getAll<CliktCommand>(), get()) }
}

private fun createSpotifyApi(configStore: ConfigStore): SpotifyApi {
    val (id, secret) = configStore.getClientCredential()
    val spotifyApi = SpotifyApi.builder()
        .setClientId(id)
        .setClientSecret(secret)
        .setRedirectUri(URI.create("http://127.0.0.1:${configStore.getLoginServerPort()}"))
        .build()

    // get access token
    val refreshToken = configStore.getRefreshToken() ?: return spotifyApi

    spotifyApi.refreshToken = refreshToken
    val refreshCredential = spotifyApi.authorizationCodeRefresh().build().execute()
    spotifyApi.accessToken = refreshCredential.accessToken
    if (refreshCredential.refreshToken != null) {
        configStore.setRefreshToken(refreshCredential.refreshToken)
        configStore.writeConfig()
    }

    return spotifyApi
}
