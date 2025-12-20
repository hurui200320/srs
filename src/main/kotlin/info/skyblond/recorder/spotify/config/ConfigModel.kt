package info.skyblond.recorder.spotify.config

import kotlinx.serialization.Serializable

@Serializable
data class ConfigModel(
    val spotifyClientId: String,
    val spotifyClientSecret: String,
    val refreshToken: String? = null,
    val loginServerPort: Int = 3000
)

// TODO:
//    this will create the config
//    srs --config ~/.config/srs/config.yaml \
//        init --clientId xxx --clientSecret xxx
//    then login (get auth code and bring up javalin, resolve refresh token and access token)
//    srs --config xxx \
//        login
//    provide the recordings, slice it
//    srs --config xxx \
//        slice --recording xxx.wav --timestamps xxx.txt --info.skyblond.recorder.spotify.ffmpeg-log xxx.log \
//              --outputDir /path/to/output/folder --contentDir /path/to/content
//    the files will be write to output dir, but will skip if the same name has occurred in content folder

// TODO: config store, hold config model and provide check and access to the value
//       also provide read and write config file?