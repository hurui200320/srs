package info.skyblond.recorder.spotify.config

import jakarta.inject.Inject
import jakarta.inject.Singleton
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

@Singleton
class ConfigStore @Inject constructor() {
    private val readWriteLock = ReentrantReadWriteLock(true)

    /**
     * The config file var is not thread safe.
     * It should be set on the startup and never changed again.
     * */
    @Volatile
    var configFile: File? = null
        private set

    fun setConfigFile(file: File) {
        configFile = file
        if (file.exists()) {
            loadConfig()
        }
    }

    private var configModel: ConfigModel? = null

    fun loadConfig() {
        readWriteLock.write {
            require(configFile != null) { "config file not set" }
            configModel = Json.decodeFromString<ConfigModel>(configFile!!.readText())
        }
    }

    fun writeConfig() {
        readWriteLock.read {
            require(configFile != null) { "config file not set" }
            require(configModel != null) { "config is not initialized yet" }
            configFile!!.parentFile.mkdirs()
            configFile!!.writeText(Json.encodeToString(configModel!!))
        }
    }

    /**
     * Get client id and client secret.
     *
     * @return Pair, first component is client id, second component is client secret.
     * */
    fun getClientCredential(): Pair<String, String> = readWriteLock.read {
        require(configModel != null) { "config is not initialized yet" }
        configModel!!.spotifyClientId to configModel!!.spotifyClientSecret
    }

    fun setClientCredential(clientId: String, clientSecret: String) {
        readWriteLock.write {
            val model = if (configModel == null) {
                ConfigModel(
                    spotifyClientId = clientId,
                    spotifyClientSecret = clientSecret
                )
            } else {
                configModel!!.copy(
                    spotifyClientId = clientId,
                    spotifyClientSecret = clientSecret
                )
            }
            configModel = model
        }
    }

    /**
     * Get refresh token.
     * */
    fun getRefreshToken(): String? = readWriteLock.read {
        require(configModel != null) { "config is not initialized yet" }
        configModel!!.refreshToken
    }

    fun setRefreshToken(refreshToken: String) {
        readWriteLock.write {
            require(configModel != null) { "config is not initialized yet" }
            configModel = configModel!!.copy(refreshToken = refreshToken)
        }
    }

    fun getLoginServerPort(): Int = readWriteLock.read {
        require(configModel != null) { "config is not initialized yet" }
        configModel!!.loginServerPort
    }
}