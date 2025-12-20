package info.skyblond.recorder.spotify

import com.github.ajalt.clikt.core.main
import info.skyblond.recorder.spotify.dagger.DaggerApp

object Main {
    @JvmStatic
    fun main(args: Array<String>) = DaggerApp.create()
        .rootCommand().main(args)
}
