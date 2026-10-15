package dev.kifranei.spotifyplus.net

import dev.kifranei.spotifyplus.lyrics.SpLyrics
import dev.kifranei.spotifyplus.lyrics.SpotifyLyricsParser
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import java.io.IOException

/**
 * Fetches color-lyrics for a track using the host's captured auth headers.
 * Order: v3 (has the `translation` block) → v2 fallback (word timings + `alternatives`).
 */
class SpotifyLyricsClient(
    private val headers: () -> Map<String, String> = HeaderStore::snapshot,
    private val baseUrl: String = "https://spclient.wg.spotify.com",
) {
    class NotFound(id: String) : IOException("no lyrics for $id")

    fun buildUrl(version: Int, trackId: String, language: String, market: String): String {
        val b = "$baseUrl/color-lyrics/v$version/track/$trackId".toHttpUrl().newBuilder()
            .addQueryParameter("vocalRemoval", "false")
            .addQueryParameter("clientLanguage", language)
            .addQueryParameter("preview", "false")
        if (market.isNotBlank()) b.addQueryParameter("market", market)
        return b.build().toString()
    }

    fun fetch(trackId: String, language: String, market: String = ""): SpLyrics {
        var last: Exception? = null
        for (v in intArrayOf(3, 2)) {
            try {
                val bytes = raw(v, trackId, language, market)
                val parsed = SpotifyLyricsParser.parseAuto(bytes, language)
                if (parsed != null) return parsed
                last = IOException("unparseable v$v response (${bytes.size} bytes)")
            } catch (e: NotFound) {
                last = e
            } catch (e: Exception) {
                last = e
            }
        }
        throw last ?: NotFound(trackId)
    }

    private fun raw(version: Int, trackId: String, language: String, market: String): ByteArray {
        val rb = Request.Builder().url(buildUrl(version, trackId, language, market)).get()
            .header("accept", "application/protobuf")
            .header("app-platform", "Android")
        headers().forEach { (k, v) -> rb.header(k, v) }
        Http.client.newCall(rb.build()).execute().use { r ->
            if (r.code == 404) throw NotFound(trackId)
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            return r.body?.bytes() ?: ByteArray(0)
        }
    }
}
