package info.skyblond.recorder.spotify.service

import jakarta.inject.Inject
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

class ImageDownloadService @Inject constructor(
    private val httpClient: HttpClient
) {
    fun downloadAlbumImage(
        url: String
    ): File {
        val req = HttpRequest.newBuilder()
            .GET()
            .uri(URI.create(url))
            .build()
        val resp = httpClient.send(req, HttpResponse.BodyHandlers.ofByteArray())
        check(resp.statusCode() == 200) { "Failed to download image: $url" }
        val file = File.createTempFile("image", "")
        file.writeBytes(resp.body())
        return file
    }
}