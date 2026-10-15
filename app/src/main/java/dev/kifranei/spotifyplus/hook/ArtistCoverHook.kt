package dev.kifranei.spotifyplus.hook

import dev.kifranei.spotifyplus.util.ModulePrefs

/**
 * Artist image source: `artist_uri` / `canvas.artist.avatar` from the same ContextTrack metadata as Canvas
 * ([TrackStore]); high-res portrait via spclient metadata (auth headers from [HeaderCapture]) in
 * [Actions.downloadArtistCover]. The menu entry itself is injected by [MoreMenuHook].
 */
object ArtistCoverHook {
    fun install(cl: ClassLoader, @Suppress("UNUSED_PARAMETER") prefs: ModulePrefs) = TrackStore.install(cl)
}
