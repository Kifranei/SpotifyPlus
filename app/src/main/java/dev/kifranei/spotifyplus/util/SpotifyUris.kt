package dev.kifranei.spotifyplus.util

/** Pure helpers (JVM-testable) for Spotify URIs / CDN URLs. */
object SpotifyUris {
    private const val B62 = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"

    fun trackId(uri: String?): String? = idOf(uri, "track")
    fun artistId(uri: String?): String? = idOf(uri, "artist")

    private fun idOf(uri: String?, type: String): String? {
        if (uri == null) return null
        val p = "spotify:$type:"
        if (!uri.startsWith(p)) return null
        return uri.removePrefix(p).substringBefore('?').takeIf { it.isNotBlank() }
    }

    /** 22-char base62 id → 32-char lowercase hex gid (as used by spclient /metadata/4). */
    fun base62ToGidHex(id: String): String? {
        if (id.length != 22) return null
        var n = java.math.BigInteger.ZERO
        val b = java.math.BigInteger.valueOf(62)
        for (c in id) {
            val d = B62.indexOf(c)
            if (d < 0) return null
            n = n.multiply(b).add(java.math.BigInteger.valueOf(d.toLong()))
        }
        return n.toString(16).padStart(32, '0').takeIf { it.length == 32 }
    }

    /** `spotify:image:<hex>` or bare hex → https://i.scdn.co/image/<hex>; http(s) URLs pass through. */
    fun imageUrl(ref: String?): String? {
        if (ref.isNullOrBlank()) return null
        return when {
            ref.startsWith("spotify:image:") -> "https://i.scdn.co/image/" + ref.removePrefix("spotify:image:")
            ref.startsWith("http://") || ref.startsWith("https://") -> ref
            Regex("^[0-9a-f]{40}$").matches(ref) -> "https://i.scdn.co/image/$ref"
            else -> null
        }
    }

    // i.scdn.co image ids = 16 hex chars "size prefix" + 24 hex chars hash.
    private val ARTIST_PREFIX = "ab6761610000"
    private val ARTIST_SIZES = mapOf("f178" to 160, "5174" to 320, "e5eb" to 640)
    private val COVER_PREFIX = "ab67616d0000"
    private val COVER_SIZES = mapOf("4851" to 64, "1e02" to 300, "b273" to 640)

    /** Upgrade known small i.scdn.co sizes to the largest *public* size (640px). Unknown shapes unchanged. */
    fun upgradeImageUrl(url: String): String {
        val m = Regex("^(https://i\\.scdn\\.co/image/)([0-9a-f]{12})([0-9a-f]{4})([0-9a-f]{24})$").find(url) ?: return url
        val (host, prefix, size, hash) = m.destructured
        return when {
            prefix == ARTIST_PREFIX && size in ARTIST_SIZES -> "$host$prefix" + "e5eb" + hash
            prefix == COVER_PREFIX && size in COVER_SIZES -> "$host$prefix" + "b273" + hash
            else -> url
        }
    }

    fun isHttp(url: String?) = url != null && (url.startsWith("https://") || url.startsWith("http://"))

    /** Canvas CDN hosts seen in the wild (spotify-video:// is the player-internal scheme, not downloadable). */
    fun isCanvasHost(url: String): Boolean {
        val host = runCatching { java.net.URI(url).host }.getOrNull()?.lowercase() ?: return false
        return host == "canvaz.scdn.co" || host.endsWith(".scdn.co") || host.endsWith(".spotifycdn.com") || host.endsWith(".spotifycdn.net")
    }

    fun extensionOf(url: String, default: String): String {
        val path = runCatching { java.net.URI(url).path }.getOrNull() ?: return default
        val ext = path.substringAfterLast('.', "").lowercase()
        return if (ext.length in 2..5 && ext.all { it.isLetterOrDigit() }) ext else default
    }

    fun safeFileName(s: String): String =
        s.replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_").trim('_').take(80).ifEmpty { "spotify" }

    fun mimeForExt(ext: String): String = when (ext.lowercase()) {
        "mp4", "m4v" -> "video/mp4"
        "webm" -> "video/webm"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        else -> "application/octet-stream"
    }
}
