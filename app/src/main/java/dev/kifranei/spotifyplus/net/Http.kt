package dev.kifranei.spotifyplus.net

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit

object Http {
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /** Streams a public URL (Canvas mp4 / i.scdn.co image). No auth headers are sent. */
    fun <T> open(url: String, headers: Map<String, String> = emptyMap(), block: (InputStream, String?) -> T): T {
        val req = Request.Builder().url(url).get().apply { headers.forEach { (k, v) -> header(k, v) } }.build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code} for $url")
            val body = resp.body ?: throw IOException("empty body")
            return block(body.byteStream(), body.contentType()?.toString())
        }
    }

    fun bytes(url: String, headers: Map<String, String> = emptyMap()): ByteArray =
        open(url, headers) { s, _ -> s.readBytes() }
}
