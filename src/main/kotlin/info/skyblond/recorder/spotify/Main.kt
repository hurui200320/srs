package info.skyblond.recorder.spotify

import com.github.ajalt.clikt.core.main
import info.skyblond.recorder.spotify.cli.RootCommand
import info.skyblond.recorder.spotify.config.ConfigStore
import info.skyblond.recorder.spotify.di.appModule
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin

object Main {
    @JvmStatic
    fun main(args: Array<String>) {
        // the config file path is a CLI option handled by RootCommand, so
        // the store is created before the container but loaded during run()
        val configStore = ConfigStore()
        val koin = startKoin { modules(appModule(configStore)) }.koin
        try {
            koin.get<RootCommand>().main(args)
        } finally {
            stopKoin()
        }
    }
}
