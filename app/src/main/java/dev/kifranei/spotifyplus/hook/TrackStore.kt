package dev.kifranei.spotifyplus.hook

import dev.kifranei.spotifyplus.util.SpotifyPlusLog
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.lang.reflect.Method

/**
 * Remembers per-track metadata seen in the host's `ContextTrack.metadata()` maps.
 *
 * Anchors (all verified by static analysis on 9.1.90.2270; `com.spotify.player.model.*` is NOT obfuscated):
 *  - class `com.spotify.player.model.AutoValue_ContextTrack` (concrete AutoValue of abstract `ContextTrack`)
 *  - metadata keys read by the host's own Canvas code (p.az0.b): `canvas.url`, `canvas.fileId`, `canvas.id`,
 *    `canvas.type`, `canvas.artist.name`, `canvas.artist.uri`, `canvas.artist.avatar`
 *  - general keys used across the app: `title`, `artist_name`, `artist_uri`, `image_url`, `image_xlarge_url`
 */
object TrackStore {
    const val CONTEXT_TRACK_IMPL = "com.spotify.player.model.AutoValue_ContextTrack"

    data class Info(
        val uri: String,
        val title: String? = null,
        val artistName: String? = null,
        val artistUri: String? = null,
        val imageUrl: String? = null,
        val canvasUrl: String? = null,
        val canvasFileId: String? = null,
        val canvasId: String? = null,
        val canvasType: String? = null,
        val canvasArtistAvatar: String? = null,
    ) {
        fun merge(o: Info) = Info(
            uri,
            o.title ?: title, o.artistName ?: artistName, o.artistUri ?: artistUri, o.imageUrl ?: imageUrl,
            o.canvasUrl ?: canvasUrl, o.canvasFileId ?: canvasFileId, o.canvasId ?: canvasId,
            o.canvasType ?: canvasType, o.canvasArtistAvatar ?: canvasArtistAvatar,
        )
    }

    private val lock = Any()
    private val map = object : LinkedHashMap<String, Info>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Info>?) = size > 160
    }
    @Volatile var currentUri: String? = null
    @Volatile private var lastCanvasUri: String? = null

    fun infoFromMetadata(uri: String, md: Map<*, *>): Info {
        fun g(k: String) = (md[k] as? String)?.takeIf { it.isNotBlank() }
        return Info(
            uri = uri,
            title = g("title"),
            artistName = g("artist_name") ?: g("canvas.artist.name"),
            artistUri = g("artist_uri") ?: g("canvas.artist.uri"),
            imageUrl = g("image_xlarge_url") ?: g("image_large_url") ?: g("image_url"),
            canvasUrl = g("canvas.url"),
            canvasFileId = g("canvas.fileId"),
            canvasId = g("canvas.id"),
            canvasType = g("canvas.type"),
            canvasArtistAvatar = g("canvas.artist.avatar"),
        )
    }

    fun record(info: Info) {
        synchronized(lock) {
            map[info.uri] = map[info.uri]?.merge(info) ?: info
            if (info.canvasUrl != null) lastCanvasUri = info.uri
        }
    }

    fun get(uri: String?): Info? = uri?.let { synchronized(lock) { map[it] } }

    /** Now-playing track (from MediaSession), else the last track we saw a Canvas for. */
    fun current(): Info? = get(currentUri) ?: get(lastCanvasUri)

    @Volatile private var installed = false
    private var uriM: Method? = null
    private var mdM: Method? = null

    /** Idempotent. Safe to call before Application exists. */
    fun install(cl: ClassLoader) {
        if (installed) return
        synchronized(lock) {
            if (installed) return
            val cls = XposedHelpers.findClassIfExists(CONTEXT_TRACK_IMPL, cl)
            if (cls == null) {
                SpotifyPlusLog.w("TrackStore: $CONTEXT_TRACK_IMPL not found — Canvas/artist metadata capture disabled")
                installed = true
                return
            }
            runCatching {
                uriM = cls.getMethod("uri")
                mdM = cls.getMethod("metadata")
            }.onFailure { SpotifyPlusLog.w("TrackStore: uri()/metadata() missing", it); installed = true; return }
            XposedBridge.hookAllConstructors(cls, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    runCatching {
                        val uri = uriM!!.invoke(param.thisObject) as? String ?: return
                        if (!uri.startsWith("spotify:track:") && !uri.startsWith("spotify:episode:")) return
                        val md = mdM!!.invoke(param.thisObject) as? Map<*, *> ?: return
                        if (md.isEmpty()) return
                        record(infoFromMetadata(uri, md))
                    }
                }
            })
            installed = true
            SpotifyPlusLog.i("TrackStore hooked $CONTEXT_TRACK_IMPL")
        }
    }
}
