package info.skyblond.recorder.spotify.service

import jakarta.inject.Inject
import jakarta.inject.Named
import se.michaelthelin.spotify.SpotifyApi
import se.michaelthelin.spotify.model_objects.specification.Track

class MetadataFetcher @Inject constructor(
    private val spotifyApi: SpotifyApi,
    @param:Named("spotifyApiHeader")
    private val headers: Map<String, String>
) {

    fun fetchTracks(trackIds: List<String>): List<Track> {
        return trackIds.chunked(50).flatMap {
            val req = spotifyApi.getSeveralTracks(*it.toTypedArray())
                .apply { headers.forEach { (key, value) -> setHeader(key, value) } }
                .build()
            req.execute().toList()
        }
    }
}