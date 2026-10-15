package dev.kifranei.spotifyplus.media

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import dev.kifranei.spotifyplus.util.SpotifyUris
import java.io.InputStream

/** Saves bytes into MediaStore (scoped storage, no permission needed on API 29+ for own inserts). */
object MediaSaver {
    enum class Kind(val dir: String, val sub: String) {
        VIDEO(Environment.DIRECTORY_MOVIES, "Spotify"),
        IMAGE(Environment.DIRECTORY_PICTURES, "Spotify"),
        DOWNLOAD(Environment.DIRECTORY_DOWNLOADS, "Spotify"),
    }

    fun kindFor(ext: String): Kind = when (ext.lowercase()) {
        "mp4", "m4v", "webm" -> Kind.VIDEO
        "png", "jpg", "jpeg", "webp", "gif" -> Kind.IMAGE
        else -> Kind.DOWNLOAD
    }

    /** @return content Uri of the saved item. Throws on failure (item is rolled back). */
    fun save(ctx: Context, input: InputStream, displayName: String, mime: String, kind: Kind): Uri {
        val resolver = ctx.contentResolver
        val collection = when (kind) {
            Kind.VIDEO -> MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            Kind.IMAGE -> MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            Kind.DOWNLOAD -> MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, kind.dir + "/" + kind.sub)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: error("MediaStore insert returned null")
        try {
            resolver.openOutputStream(uri)?.use { out -> input.copyTo(out) } ?: error("openOutputStream null")
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            return uri
        } catch (t: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            throw t
        }
    }

    fun saveBytes(ctx: Context, data: ByteArray, displayName: String, ext: String): Uri =
        save(ctx, data.inputStream(), displayName, SpotifyUris.mimeForExt(ext), kindFor(ext))
}
