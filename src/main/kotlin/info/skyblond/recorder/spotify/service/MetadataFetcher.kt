package info.skyblond.recorder.spotify.service

import se.michaelthelin.spotify.SpotifyApi
import se.michaelthelin.spotify.model_objects.specification.Track

class MetadataFetcher(
    private val spotifyApi: SpotifyApi,
    private val headers: Map<String, String>
) {

    fun fetchTracks(trackIds: List<String>): List<Track> {
        // The batch GET /tracks endpoint is no longer available to development-mode
        // apps, and spotify-web-api-java 10 deprecates getSeveralTracks. Fetch each
        // track individually instead, as recommended by Spotify's migration guide.
        // Requests are deduplicated so a track played twice in one session is only
        // fetched once; the result still has one entry per input id, in input order.
        val tracksById = trackIds.distinct().associateWith { trackId ->
            spotifyApi.getTrack(trackId)
                .apply { headers.forEach { (key, value) -> setHeader(key, value) } }
                .build()
                .execute()
        }
        return trackIds.map { tracksById.getValue(it) }
    }
}
