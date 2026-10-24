package info.skyblond.recorder.spotify.service

import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import se.michaelthelin.spotify.SpotifyApi
import se.michaelthelin.spotify.model_objects.specification.Track
import se.michaelthelin.spotify.requests.data.tracks.GetTrackRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class MetadataFetcherTest {

    /**
     * Pins the post-9.x fetch strategy.
     *
     * spotify-web-api-java 10 removed the batch GET /tracks path for
     * development-mode apps, so each distinct id must become its own getTrack
     * request with the custom headers applied. Input order and cardinality must
     * survive (duplicated ids reuse the already fetched track), and a duplicated
     * id must not cause a second HTTP request.
     */
    @Test
    fun `fetches each distinct track once and preserves input order`() {
        val spotifyApi = mockk<SpotifyApi>()
        val headers = mapOf("accept-language" to "zh-CN,zh;q=0.9")

        val trackA = mockk<Track>()
        val trackB = mockk<Track>()
        val tracksById = mapOf("a" to trackA, "b" to trackB)
        val builders = mutableListOf<GetTrackRequest.Builder>()

        tracksById.forEach { (id, track) ->
            val builder = mockk<GetTrackRequest.Builder>()
            val request = mockk<GetTrackRequest>()
            every { builder.setHeader(any<String>(), any<String>()) } returns builder
            every { builder.build() } returns request
            every { request.execute() } returns track
            every { spotifyApi.getTrack(id) } returns builder
            builders += builder
        }

        val result = MetadataFetcher(spotifyApi, headers)
            .fetchTracks(listOf("a", "b", "a"))

        assertSame(trackA, result[0])
        assertSame(trackB, result[1])
        assertSame(trackA, result[2])

        verify(exactly = 1) { spotifyApi.getTrack("a") }
        verify(exactly = 1) { spotifyApi.getTrack("b") }
        builders.forEach { builder ->
            verify(exactly = 1) { builder.setHeader("accept-language", "zh-CN,zh;q=0.9") }
        }
    }

    @Test
    fun `returns an empty list and makes no requests for no ids`() {
        val spotifyApi = mockk<SpotifyApi>()

        val result = MetadataFetcher(spotifyApi, emptyMap()).fetchTracks(emptyList())

        assertEquals(emptyList<Track>(), result)
        confirmVerified(spotifyApi)
    }
}
