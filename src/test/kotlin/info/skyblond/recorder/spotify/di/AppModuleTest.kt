package info.skyblond.recorder.spotify.di

import com.github.ajalt.clikt.core.CliktCommand
import info.skyblond.recorder.spotify.cli.RootCommand
import info.skyblond.recorder.spotify.config.ConfigStore
import info.skyblond.recorder.spotify.service.MetadataFetcher
import org.koin.core.error.InstanceCreationException
import org.koin.dsl.koinApplication
import se.michaelthelin.spotify.SpotifyApi
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppModuleTest {

    /**
     * Resolving the graph root must not touch the config or the network:
     * the Spotify client and the metadata fetcher are still lazy until a
     * subcommand actually runs (the config file path is a CLI option).
     */
    @Test
    fun `root command resolves without config or network`() {
        val app = koinApplication { modules(appModule(ConfigStore())) }
        try {
            val root = app.koin.get<RootCommand>()
            val commands = app.koin.getAll<CliktCommand>()

            assertEquals(5, commands.size)
            assertEquals(5, root.registeredSubcommands().size)
        } finally {
            app.close()
        }
    }

    /**
     * Once the config is loaded, the Spotify client builds without network
     * IO as long as no refresh token is stored (the credential refresh only
     * runs when a token exists).
     */
    @Test
    fun `spotify api resolves offline once config is set`() {
        val configFile = Files.createTempFile("srs-koin-test", ".json").toFile()
        try {
            configFile.writeText(
                """{"spotifyClientId":"id","spotifyClientSecret":"secret"}"""
            )
            val configStore = ConfigStore().apply { setConfigFile(configFile) }
            val app = koinApplication { modules(appModule(configStore)) }
            try {
                val spotifyApi = app.koin.get<SpotifyApi>()

                assertEquals("id", spotifyApi.clientId)
                assertEquals("secret", spotifyApi.clientSecret)
                assertNull(spotifyApi.accessToken)

                // also exercises the named("spotifyApiHeader") wiring end to end:
                // the compiler plugin validates types but not qualifier values,
                // so a qualifier typo would otherwise only surface at runtime
                assertNotNull(app.koin.get<MetadataFetcher>())
            } finally {
                app.close()
            }
        } finally {
            configFile.delete()
        }
    }

    /**
     * The Spotify client must not be creatable until [RootCommand.run] has set
     * the config file; resolving it earlier is caught by ConfigStore's guard,
     * not by accidentally hitting the network.
     */
    @Test
    fun `spotify api resolution before config is set fails fast`() {
        val app = koinApplication { modules(appModule(ConfigStore())) }
        try {
            val failure = assertFailsWith<InstanceCreationException> {
                app.koin.get<SpotifyApi>()
            }
            val guard = generateSequence(failure as Throwable) { it.cause }
                .filterIsInstance<IllegalArgumentException>()
                .firstOrNull()

            assertNotNull(guard)
            assertTrue(guard.message.orEmpty().contains("config is not initialized yet"))
        } finally {
            app.close()
        }
    }

}
