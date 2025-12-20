package info.skyblond.recorder.spotify.dagger

import com.github.ajalt.clikt.core.CliktCommand
import dagger.Binds
import dagger.Module
import dagger.multibindings.IntoSet
import info.skyblond.recorder.spotify.cli.InitCommand
import info.skyblond.recorder.spotify.cli.LoginCommand
import info.skyblond.recorder.spotify.cli.SliceCommand
import jakarta.inject.Named

@Module
interface SubCommandsModule {
    @Binds
    @IntoSet
    fun initCommand(command: InitCommand): CliktCommand

    @Binds
    @IntoSet
    fun loginCommand(command: LoginCommand): CliktCommand

    @Binds
    @IntoSet
    fun sliceCommand(command: SliceCommand): CliktCommand
}