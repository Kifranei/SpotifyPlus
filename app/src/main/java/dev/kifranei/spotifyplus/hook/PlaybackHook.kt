package dev.kifranei.spotifyplus.hook

import android.media.MediaMetadata
import android.media.session.PlaybackState
import dev.kifranei.spotifyplus.util.SpotifyUris
import dev.kifranei.spotifyplus.util.SpotifyPlusLog
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Hooks the framework `MediaSession.setMetadata/setPlaybackState` (stable, same approach as LyricProvider)
 * to learn the now-playing track and playback state. METADATA_KEY_MEDIA_ID carries `spotify:track:<id>`.
 */
object PlaybackHook {
    data class Track(val id: String, val title: String?, val artist: String?, val durationMs: Long)

    interface Listener {
        fun onTrack(t: Track) {}
        fun onPlaybackState(s: PlaybackState) {}
        fun onOtherMetadata(title: String?, artist: String?) {}
    }

    private val listeners = CopyOnWriteArrayList<Listener>()
    @Volatile private var installed = false
    @Volatile var lastTrack: Track? = null
        private set

    fun add(l: Listener) { listeners += l }

    @Synchronized
    fun install() {
        if (installed) return
        installed = true
        val ms = android.media.session.MediaSession::class.java
        ModernHookRuntime.hookAllMethods(ms, "setMetadata", object : ModernHookRuntime.MethodHook() {
            override fun afterHookedMethod(p: ModernHookRuntime.MethodHookParam) {
                val md = p.args.getOrNull(0) as? MediaMetadata ?: return
                val mediaId = md.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)
                val id = SpotifyUris.trackId(mediaId)
                if (id == null) {
                    listeners.forEach { it.onOtherMetadata(md.getString(MediaMetadata.METADATA_KEY_TITLE), md.getString(MediaMetadata.METADATA_KEY_ARTIST)) }
                    return
                }
                TrackStore.currentUri = mediaId
                val t = Track(id, md.getString(MediaMetadata.METADATA_KEY_TITLE), md.getString(MediaMetadata.METADATA_KEY_ARTIST),
                    md.getLong(MediaMetadata.METADATA_KEY_DURATION))
                if (t.id == lastTrack?.id) return
                lastTrack = t
                listeners.forEach { l -> runCatching { l.onTrack(t) }.onFailure { SpotifyPlusLog.w("listener", it) } }
            }
        })
        ModernHookRuntime.hookAllMethods(ms, "setPlaybackState", object : ModernHookRuntime.MethodHook() {
            override fun afterHookedMethod(p: ModernHookRuntime.MethodHookParam) {
                val s = p.args.getOrNull(0) as? PlaybackState ?: return
                listeners.forEach { l -> runCatching { l.onPlaybackState(s) } }
            }
        })
        SpotifyPlusLog.i("PlaybackHook installed")
    }
}
