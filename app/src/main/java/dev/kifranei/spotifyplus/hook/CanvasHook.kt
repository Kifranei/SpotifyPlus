package dev.kifranei.spotifyplus.hook

import dev.kifranei.spotifyplus.util.ModulePrefs

/**
 * Canvas URL capture. The URL is **not** fetched by us: the host already puts it into each track's
 * `ContextTrack.metadata()` under `canvas.url` (+ `canvas.fileId`, `canvas.id`, `canvas.type`,
 * `canvas.artist.*`) — see p.az0.b(ContextTrack) → p.mlc → p.cmc.O ("Canvas video URL is null or blank").
 * [TrackStore] captures those maps; [Actions.downloadCanvas] downloads the mp4 (public CDN, no DRM).
 */
object CanvasHook {
    fun install(cl: ClassLoader, @Suppress("UNUSED_PARAMETER") prefs: ModulePrefs) = TrackStore.install(cl)
}
