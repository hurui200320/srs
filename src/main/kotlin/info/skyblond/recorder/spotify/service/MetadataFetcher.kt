package info.skyblond.recorder.spotify.service

import se.michaelthelin.spotify.SpotifyApi
import se.michaelthelin.spotify.model_objects.specification.Track

class MetadataFetcher(
    private val spotifyApi: SpotifyApi,
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