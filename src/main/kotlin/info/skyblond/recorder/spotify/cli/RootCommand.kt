package info.skyblond.recorder.spotify.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.file
import info.skyblond.recorder.spotify.config.ConfigStore
import jakarta.inject.Inject
import java.io.File

class RootCommand @Inject constructor(
    subcommands: Set<@JvmSuppressWildcards CliktCommand>,
    private val configStore: ConfigStore
) : CliktCommand(
    name = "srs"
) {
    private val configFile by option("-c", "--config", help = "Path to config file")
        .file(mustExist = false, canBeDir = false)
        .default(File(System.getProperty("user.home") + File.separator + ".srs/config.json"))

    init {
        subcommands(subcommands)
    }

    override fun run() {
        configStore.setConfigFile(configFile)
    }
}