package info.skyblond.recorder.spotify.cli

import com.github.ajalt.clikt.core.CliktCommand
import info.skyblond.recorder.spotify.config.ConfigStore

/**
 * Command that clears the stored Spotify refresh token.
 * */
class LogoutCommand(
    private val configStore: ConfigStore
) : CliktCommand(name = "logout") {

    override fun run() {
        if (configStore.configFile?.exists() != true) {
            echo("No config file found, nothing to log out.")
            return
        }
        if (configStore.getRefreshToken() == null) {
            echo("Not logged in.")
            return
        }

        configStore.clearRefreshToken()
        configStore.writeConfig()
        echo("Refresh token cleared, please login again")
    }
}
