package info.skyblond.recorder.spotify.cli

import com.github.ajalt.clikt.core.CliktCommand
import info.skyblond.recorder.spotify.config.ConfigStore
import io.javalin.Javalin
import se.michaelthelin.spotify.SpotifyApi
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.absoluteValue
import kotlin.random.Random

/**
 * Command that login to the user's spotify account.
 * */
class LoginCommand(
    spotifyApi: Lazy<SpotifyApi>,
    private val configStore: ConfigStore
) : CliktCommand(name = "login") {

    private val spotifyApi by spotifyApi

    override fun run() {
        val requestState = Random.nextLong().absoluteValue.toString()

        val authzCodeUriRequest = spotifyApi.authorizationCodeUri()
            .state(requestState)
            .scope(
                listOf(
                    "playlist-read-private",
                    "user-read-playback-state",
                    "user-read-currently-playing",
                    "user-library-read",
                ).joinToString(separator = ",")
            )
            .build()

        val codeRef = AtomicReference("")
        val server = Javalin.create { config ->
            config.routes.get("/") { ctx ->
                val code = ctx.queryParam("code")
                val state = ctx.queryParam("state")

                if (requestState != state) {
                    ctx.status(400)
                    ctx.result("State mismatch")
                    return@get
                }

                ctx.result("Success! You may close this tab safely.")
                codeRef.set(code)
            }
        }.start("localhost", configStore.getLoginServerPort())

        echo("Please use this url to login: \n    " + authzCodeUriRequest.execute())

        while (codeRef.get().isBlank()) {
            Thread.sleep(1000)
        }
        server.stop()
        echo("Received auth code, exchange for access token...")
        val authzCodeRequest = spotifyApi.authorizationCode(codeRef.get()).build()
        val credentials = authzCodeRequest.execute()

        spotifyApi.accessToken = credentials.accessToken
        spotifyApi.refreshToken = credentials.refreshToken

        configStore.setRefreshToken(credentials.refreshToken)
        configStore.writeConfig()

        echo("Refresh token saved, login finished")
    }
}