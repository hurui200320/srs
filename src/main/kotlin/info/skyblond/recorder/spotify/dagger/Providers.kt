package info.skyblond.recorder.spotify.dagger

import dagger.Module
import dagger.Provides
import info.skyblond.recorder.spotify.config.ConfigStore
import jakarta.inject.Inject
import jakarta.inject.Named
import net.bramp.ffmpeg.FFmpeg
import net.bramp.ffmpeg.FFmpegExecutor
import net.bramp.ffmpeg.FFprobe
import se.michaelthelin.spotify.SpotifyApi
import java.net.URI
import java.net.http.HttpClient

@Module
class Providers {
    @Provides
    @Inject
    fun provideSpotifyApi(
        configStore: ConfigStore
    ): SpotifyApi {
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

    @Provides
    fun provideHttpClient(): HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    @Provides
    @Named("spotifyApiHeader")
    fun provideSpotifyApiHeader(): Map<String, String> = mapOf(
        "accept-language" to "zh-CN,zh;q=0.9,zh-Hans;q=0.8,en;q=0.7"
    )

    @Provides
    fun provideFfmpeg(): FFmpeg = FFmpeg()

    @Provides
    fun provideFfprobe(): FFprobe = FFprobe()

    @Provides
    @Inject
    fun provideFFmpegExecutor(
        ffmpeg: FFmpeg
    ): FFmpegExecutor = FFmpegExecutor(ffmpeg)
}