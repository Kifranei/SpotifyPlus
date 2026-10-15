package dev.kifranei.spotifyplus.hook

import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/** Share-card files (png/jpg/mp4…) the host exposed through its FileProvider, newest last. */
object ShareRegistry {
    data class Entry(val file: File, val uri: String, val at: Long)
    private val entries = CopyOnWriteArrayList<Entry>()
    private val saved = java.util.Collections.synchronizedSet(HashSet<String>())
    const val WINDOW_MS = 3 * 60_000L

    val MEDIA_EXT = setOf("png", "jpg", "jpeg", "webp", "mp4", "gif")

    /** Pure predicate (unit-tested): is this FileProvider file plausibly a share card? */
    fun looksLikeShareCard(path: String, authority: String?): Boolean {
        val ext = path.substringAfterLast('.', "").lowercase()
        if (ext !in MEDIA_EXT) return false
        val p = path.lowercase()
        if (authority != null && authority.lowercase().contains("pushnotification")) return false
        return p.contains("share")
    }

    fun record(file: File, uri: String) {
        entries += Entry(file, uri, System.currentTimeMillis())
        while (entries.size > 24) entries.removeAt(0)
    }

    fun recentFiles(now: Long = System.currentTimeMillis()): List<File> =
        entries.filter { now - it.at <= WINDOW_MS && it.file.exists() && it.file.length() > 0 }.map { it.file }.distinct()

    fun entryUri(file: File): String? = entries.lastOrNull { it.file == file }?.uri

    fun fileForUri(uri: String): File? = entries.lastOrNull { it.uri == uri }?.file

    /** @return true the first time a key is seen (used for auto-save de-duplication). */
    fun markSaved(key: String) = saved.add(key)
}
