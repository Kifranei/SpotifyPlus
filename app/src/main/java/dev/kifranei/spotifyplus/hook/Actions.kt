package dev.kifranei.spotifyplus.hook

import android.content.Context
import android.net.Uri
import dev.kifranei.spotifyplus.media.MediaSaver
import dev.kifranei.spotifyplus.net.ArtistMetadata
import dev.kifranei.spotifyplus.net.HeaderStore
import dev.kifranei.spotifyplus.net.Http
import dev.kifranei.spotifyplus.util.SpotifyPlusLog
import dev.kifranei.spotifyplus.util.SpotifyUris

/** User-triggered actions (menu items / chooser actions). All network+IO runs on [SpotifyHost.io]. */
object Actions {

    fun downloadCanvas() = SpotifyHost.io.execute {
        val ctx = SpotifyHost.context() ?: return@execute
        val info = TrackStore.current()
        if (info == null) { SpotifyHost.toast("SpotifyPlus：没有正在播放的曲目"); return@execute }
        val url = info.canvasUrl
        if (url == null) {
            SpotifyHost.toast(
                if (info.canvasFileId != null) "SpotifyPlus：此曲目的 Canvas 没有可下载的直链" else "SpotifyPlus：当前曲目没有 Canvas"
            )
            return@execute
        }
        if (!SpotifyUris.isHttp(url)) { SpotifyHost.toast("SpotifyPlus：Canvas 地址不是 http(s)"); return@execute }
        try {
            val ext = SpotifyUris.extensionOf(url, "mp4")
            val name = "Canvas_" + SpotifyUris.safeFileName("${info.artistName ?: ""}_${info.title ?: ""}_${SpotifyUris.trackId(info.uri) ?: ""}") + "." + ext
            val saved = Http.open(url) { s, ct ->
                MediaSaver.save(ctx, s, name, ct?.substringBefore(';')?.takeIf { it.startsWith("video/") } ?: SpotifyUris.mimeForExt(ext), MediaSaver.Kind.VIDEO)
            }
            SpotifyHost.toast("Canvas 已保存到 影片/Spotify")
            SpotifyPlusLog.i("canvas saved $saved")
        } catch (t: Throwable) {
            SpotifyPlusLog.e("canvas download failed", t)
            SpotifyHost.toast("Canvas 下载失败：${t.message}")
        }
    }

    fun downloadArtistCover() = SpotifyHost.io.execute {
        val ctx = SpotifyHost.context() ?: return@execute
        val info = TrackStore.current()
        if (info == null) { SpotifyHost.toast("SpotifyPlus：没有正在播放的曲目"); return@execute }
        val candidates = ArrayList<String>()
        try {
            val uri = info.artistUri
            if (uri != null && HeaderStore.hasAuth()) {
                ArtistMetadata.fetchLargestPortrait(uri)?.let { candidates += it.url }
            }
        } catch (t: Throwable) {
            SpotifyPlusLog.w("artist metadata lookup failed, falling back to avatar", t)
        }
        SpotifyUris.imageUrl(info.canvasArtistAvatar)?.let { candidates += SpotifyUris.upgradeImageUrl(it) }
        if (candidates.isEmpty()) { SpotifyHost.toast("SpotifyPlus：找不到艺人图片地址"); return@execute }
        var lastErr: Throwable? = null
        for (url in candidates.distinct()) {
            try {
                val ext = SpotifyUris.extensionOf(url, "jpg")
                val name = "Artist_" + SpotifyUris.safeFileName(info.artistName ?: SpotifyUris.artistId(info.artistUri) ?: "artist") + "." + ext
                val bytes = Http.bytes(url)
                MediaSaver.saveBytes(ctx, bytes, name, ext)
                SpotifyHost.toast("艺人封面已保存到 图片/Spotify")
                return@execute
            } catch (t: Throwable) { lastErr = t }
        }
        SpotifyPlusLog.e("artist cover failed", lastErr)
        SpotifyHost.toast("艺人封面下载失败：${lastErr?.message}")
    }

    fun saveLatestShareCard() = SpotifyHost.io.execute {
        val files = ShareRegistry.recentFiles()
        if (files.isEmpty()) { SpotifyHost.toast("SpotifyPlus：还没有捕获到分享卡片，请先在分享页选择卡片"); return@execute }
        saveShareFiles(listOf(files.last()))
    }

    fun saveShareFiles(files: List<java.io.File>) {
        val ctx = SpotifyHost.context() ?: return
        var n = 0
        files.forEach { f ->
            runCatching {
                val ext = f.extension.ifEmpty { "png" }
                MediaSaver.save(ctx, f.inputStream(), "ShareCard_${System.currentTimeMillis()}_${f.nameWithoutExtension.take(24)}.$ext",
                    SpotifyUris.mimeForExt(ext), MediaSaver.kindFor(ext))
                n++
            }.onFailure { SpotifyPlusLog.w("share card save failed: ${f.name}", it) }
        }
        SpotifyHost.toast(if (n > 0) "已保存当前分享卡片到 图片/Spotify" else "分享卡片保存失败")
    }

    fun saveShareUris(ctx: Context, allUris: List<Uri>) = SpotifyHost.io.execute {
        var n = 0
        val one = ShareRegistry.pickSingle(allUris.map { it.toString() }, ShareRegistry.uriOrder())
        val uris = if (one == null) emptyList() else listOf(Uri.parse(one))
        uris.forEach { u ->
            runCatching {
                val mime = ctx.contentResolver.getType(u) ?: "image/png"
                val ext = when { mime.contains("mp4") || mime.startsWith("video/") -> "mp4"; mime.contains("jpeg") -> "jpg"; mime.contains("webp") -> "webp"; else -> "png" }
                ctx.contentResolver.openInputStream(u)?.use { s ->
                    MediaSaver.save(ctx, s, "ShareCard_${System.currentTimeMillis()}_${n}.$ext", mime, MediaSaver.kindFor(ext))
                    n++
                }
            }.onFailure { SpotifyPlusLog.w("share uri save failed: $u", it) }
        }
        SpotifyHost.toast(if (n > 0) "已保存当前分享卡片到 图片/Spotify" else "分享卡片保存失败")
    }
}
