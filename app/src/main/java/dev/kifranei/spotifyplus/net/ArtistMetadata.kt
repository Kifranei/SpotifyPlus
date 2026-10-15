package dev.kifranei.spotifyplus.net

import dev.kifranei.spotifyplus.proto.ProtoReader
import dev.kifranei.spotifyplus.util.SpotifyUris
import okhttp3.Request
import java.io.IOException

/**
 * Artist portrait lookup via spclient `GET /metadata/4/artist/{gidHex}` (protobuf, same schema as
 * librespot's metadata.proto: Artist.portrait=11 / portrait_group=17 → Image{1:file_id 3:width 4:height}).
 * Needs the host's auth headers ([HeaderStore]). Untested on device.
 */
object ArtistMetadata {
    data class Portrait(val url: String, val width: Int, val height: Int)

    fun parsePortraits(bytes: ByteArray): List<Portrait> {
        val top = ProtoReader.parse(bytes)
        val out = ArrayList<Portrait>()
        fun image(b: ByteArray) {
            val f = ProtoReader.parse(b)
            val id = f.firstOrNull { it.number == 1 }?.bytes ?: return
            val w = f.firstOrNull { it.number == 3 && it.wireType == 0 }?.int() ?: 0
            val h = f.firstOrNull { it.number == 4 && it.wireType == 0 }?.int() ?: 0
            val hex = id.joinToString("") { "%02x".format(it) }
            out += Portrait("https://i.scdn.co/image/$hex", w, h)
        }
        top.filter { it.number == 11 }.mapNotNull { it.bytes }.forEach(::image)
        top.filter { it.number == 17 }.mapNotNull { it.bytes }.forEach { grp ->
            ProtoReader.parse(grp).filter { it.number == 1 }.mapNotNull { it.bytes }.forEach(::image)
        }
        return out.distinctBy { it.url }
    }

    fun largest(list: List<Portrait>): Portrait? = list.maxByOrNull { it.width.toLong() * it.height }

    fun fetchLargestPortrait(artistUri: String, headers: Map<String, String> = HeaderStore.snapshot()): Portrait? {
        val gid = SpotifyUris.artistId(artistUri)?.let(SpotifyUris::base62ToGidHex) ?: return null
        val rb = Request.Builder().url("https://spclient.wg.spotify.com/metadata/4/artist/$gid?market=from_token")
            .get().header("accept", "application/protobuf")
        headers.forEach { (k, v) -> rb.header(k, v) }
        Http.client.newCall(rb.build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            return largest(parsePortraits(r.body?.bytes() ?: return null))
        }
    }
}
