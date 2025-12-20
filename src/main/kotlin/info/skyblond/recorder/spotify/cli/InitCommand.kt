package info.skyblond.recorder.spotify.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.options.validate
import info.skyblond.recorder.spotify.config.ConfigStore
import jakarta.inject.Inject

/**
 * Init config file with client id and client secret.
 * */
class InitCommand @Inject constructor(
    private val configStore: ConfigStore
): CliktCommand(name = "init") {
    private val clientId by option("-i", "--client-id", help = "Spotify client ID")
        .required()
        .validate { require(it.isNotBlank()) { "Client ID cannot be blank" } }

    private val clientSecret by option("-s", "--client-secret", help = "Spotify client secret")
        .required()
        .validate { require(it.isNotBlank()) { "Client secret cannot be blank" } }

    private val overrideExisting by option("-f", "--force", help = "Override existing config file")
        .flag(default = false)

    override fun run() {
        configStore.configFile?.let {
            if (it.exists() && !overrideExisting) {
                throw CliktError("Config file already exists, use -f to override")
            }
        }
        configStore.setClientCredential(clientId, clientSecret)
        configStore.writeConfig()
    }
}