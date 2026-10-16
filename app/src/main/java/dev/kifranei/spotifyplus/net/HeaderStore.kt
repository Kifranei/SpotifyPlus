package dev.kifranei.spotifyplus.net

import java.util.concurrent.ConcurrentHashMap

/**
 * Auth headers captured from the host's own OkHttp traffic (R8-obfuscated `okhttp3.Headers`,
 * located by the string anchor "Expected alternating header names and values").
 * Only keys needed to call spclient as the logged-in client are kept.
 */
object HeaderStore {
    private val wanted = setOf(
        "authorization", "client-token", "user-agent", "spotify-app-version", "app-platform",
        "x-client-id", "accept-language",
    )
    private val map = ConcurrentHashMap<String, String>()

    /** @param namesAndValues OkHttp's alternating [name, value, name, value…] array. */
    fun record(namesAndValues: Array<*>) {
        var i = 0
        var sawBearer = false
        val tmp = HashMap<String, String>()
        while (i + 1 < namesAndValues.size) {
            val k = (namesAndValues[i] as? String)?.lowercase()
            val v = namesAndValues[i + 1] as? String
            if (k != null && v != null && k in wanted) {
                tmp[k] = v
                if (k == "authorization" && v.startsWith("Bearer ")) sawBearer = true
            }
            i += 2
        }
        // Only trust header sets that look like authenticated requests (skip response headers).
        if (sawBearer || tmp.containsKey("client-token")) map.putAll(tmp)
    }

    fun snapshot(): Map<String, String> = HashMap(map)
    fun hasAuth(): Boolean = map["authorization"]?.startsWith("Bearer ") == true
    fun clear() = map.clear()
}
