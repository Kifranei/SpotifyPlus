package dev.kifranei.spotifyplus.hook

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.media.MediaMetadataRetriever
import android.net.Uri
import dev.kifranei.spotifyplus.util.SpotifyPlusLog
import java.io.File
import java.io.InputStream
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.net.URL

/**
 * Story-format share data of the selected share-sheet card, and how to flatten it into one opaque image.
 *
 * Why: our two share-sheet destinations advertise the story kinds (gradient/image/video story), so the host's
 * share-data factory (`p.npg` → story provider `p.u7c1`, 9.1.98) hands them **story data**, not a flat image:
 *  - gradient story `p.q7c1` (9.1.90 `p.xh81`): `b` = background colours `p.kmt(top, bottom)` (9.1.90 `p.sbs(c, d)`),
 *    `c` = sticker `p.lmt(uri)` — the sticker is the card **foreground only, transparent PNG**;
 *  - image story `p.r7c1` (`p.yh81`): `b` = background image `p.lmt`, `c` = optional sticker `p.lmt`;
 *  - video story `p.s7c1` (`p.zh81`): `b` = background video `p.mmt(uri, boolean)`, `c` = optional sticker.
 * Spotify itself never flattens a gradient story: Instagram/Facebook/TikTok get the sticker plus
 * `top_background_color`/`bottom_background_color` and paint the vertical gradient themselves; Spotify's own gradient
 * renderer (`p.r4c1.a`) draws `LinearGradient(0,0 → 0,H, top, bottom)` on a screen-width 9:16 bitmap. Saving the
 * sticker alone therefore lost the colour (transparent → shown black). We do what the story apps do: 9:16 canvas,
 * background (gradient / centre-cropped image / first video frame), sticker centred on top at the preview's proportion.
 * Remote backgrounds (template images `ShareCard.getImageUrl()`, animated backgrounds) are downloaded with timeouts first.
 *
 * The share-data classes are matched by **shape** only (same layout in 9.1.90 and 9.1.98), never by name.
 */
object StoryCard {
    sealed class Bg {
        data class Gradient(val top: Int, val bottom: Int) : Bg()
        data class Image(val ref: String) : Bg()
        data class Video(val ref: String) : Bg()
    }

    /** Background + optional sticker reference of a story share-data object. */
    data class Story(val bg: Bg?, val sticker: String?) {
        /** Stable key for re-using an already rendered composite. */
        val key: String get() = "story|$bg|$sticker"
    }

    private fun instanceFields(c: Class<*>): List<Field> =
        c.declaredFields.filter { !Modifier.isStatic(it.modifiers) && !it.isSynthetic }.sortedBy { it.name }

    /** Shape of a background/sticker wrapper class: 2 ints = colours, 1 String = image ref, String+boolean = video ref. */
    internal enum class Shape { GRADIENT, IMAGE, VIDEO }

    internal fun shapeOf(c: Class<*>): Shape? {
        val f = instanceFields(c).map { it.type }
        return when {
            f.size == 2 && f.all { it == Int::class.javaPrimitiveType } -> Shape.GRADIENT
            f.size == 1 && f[0] == String::class.java -> Shape.IMAGE
            f.size == 2 && f.count { it == String::class.java } == 1 && f.count { it == Boolean::class.javaPrimitiveType } == 1 -> Shape.VIDEO
            else -> null
        }
    }

    private fun get(f: Field, o: Any): Any? = runCatching { f.isAccessible = true; f.get(o) }.getOrNull()

    /**
     * Pure reflection (unit-tested with fake classes): read [data] as story share data, or null when it is not one.
     * Story data = an object whose superclass is abstract and which has exactly two instance fields typed with
     * subclasses of one shared abstract "background" base; by name order the first is the background, the second the sticker.
     */
    fun parse(data: Any?): Story? {
        if (data == null) return null
        val c = data.javaClass
        val sup = c.superclass ?: return null
        if (sup == Any::class.java || !Modifier.isAbstract(sup.modifiers)) return null
        val wrappers = instanceFields(c).filter { f ->
            val base = f.type.superclass
            !f.type.isPrimitive && base != null && base != Any::class.java && Modifier.isAbstract(base.modifiers) && shapeOf(f.type) != null
        }
        val group = wrappers.groupBy { it.type.superclass }.values.firstOrNull { it.size == 2 } ?: return null
        val (bgField, stickerField) = group
        if (shapeOf(stickerField.type) != Shape.IMAGE) return null
        val bg = get(bgField, data)?.let { v -> background(v) }
        val sticker = get(stickerField, data)?.let { v -> instanceFields(v.javaClass).firstOrNull { it.type == String::class.java }?.let { get(it, v) as? String } }
        if (bg == null && sticker == null) return null
        return Story(bg, sticker?.takeIf { it.isNotEmpty() })
    }

    private fun background(v: Any): Bg? {
        val fs = instanceFields(v.javaClass)
        return when (shapeOf(v.javaClass)) {
            Shape.GRADIENT -> Bg.Gradient(get(fs[0], v) as Int, get(fs[1], v) as Int)
            Shape.IMAGE -> (get(fs[0], v) as? String)?.takeIf { it.isNotEmpty() }?.let { Bg.Image(it) }
            Shape.VIDEO -> (fs.firstOrNull { it.type == String::class.java }?.let { get(it, v) } as? String)?.takeIf { it.isNotEmpty() }?.let { Bg.Video(it) }
            null -> null
        }
    }

    // ---- pure geometry (unit-tested) ----------------------------------------------------------------------------

    /** Output size: 1080 × 1920 (9:16, like the share-sheet preview frame and Spotify's story background renderer). */
    const val CANVAS_W = 1080
    const val CANVAS_H = 1920

    /**
     * Sticker width as a fraction of the 9:16 frame, measured on the 9.1.98 share-sheet preview (frame 567 px wide,
     * sticker 504 px → 0.889; the sticker bitmap is captured at exactly that on-screen size, i.e. the preview frame minus
     * ~12 dp padding per side). Height is capped by the same fraction.
     */
    const val STICKER_FRAC = 0.889f

    /** Sticker destination (l, t, r, b): scaled (up or down, aspect kept) to [frac] of the frame, centred. */
    fun stickerRect(cw: Int, ch: Int, sw: Int, sh: Int, frac: Float = STICKER_FRAC): FloatArray {
        val s = minOf(cw * frac / sw, ch * frac / sh)
        val w = sw * s; val h = sh * s
        val l = (cw - w) / 2f; val t = (ch - h) / 2f
        return floatArrayOf(l, t, l + w, t + h)
    }

    /** Largest power-of-two sample size that keeps a sw×sh image at least dw×dh. */
    fun sampleSize(sw: Int, sh: Int, dw: Int, dh: Int): Int {
        var n = 1
        while (sw / (n * 2) >= dw && sh / (n * 2) >= dh) n *= 2
        return n
    }

    fun isRemote(ref: String?) = ref != null && (ref.startsWith("http://") || ref.startsWith("https://"))

    /** Source rect (l, t, r, b) of a centre-crop of a sw×sh image into a dw×dh frame. */
    fun centerCrop(sw: Int, sh: Int, dw: Int, dh: Int): IntArray {
        return if (sw.toLong() * dh > sh.toLong() * dw) {          // source wider: crop left/right
            val w = (sh.toLong() * dw / dh).toInt(); val l = (sw - w) / 2
            intArrayOf(l, 0, l + w, sh)
        } else {
            val h = (sw.toLong() * dh / dw).toInt(); val t = (sh - h) / 2
            intArrayOf(0, t, sw, t + h)
        }
    }

    /** Spotify's own fallback colour for a story whose video background cannot be used (`#1F1F1F`). */
    const val FALLBACK_BG = 0xFF1F1F1F.toInt()

    /** File-name marker of a render whose image / video background was unavailable (drawn on [FALLBACK_BG]). */
    const val NO_BG_SUFFIX = "_nobg"

    // ---- Android rendering ------------------------------------------------------------------------------------

    private const val T = "share-menu: "

    private const val MAX_DOWNLOAD = 64L * 1024 * 1024

    private fun cacheDir(ctx: Context) = File(ctx.cacheDir, "spotifyplus-cards").apply { mkdirs() }

    /**
     * Spotify's own on-disk image caches (cacheDir-relative). The share-sheet preview already loaded the template
     * background through Coil 3 (`coil3_disk_cache` / `coil_cache`, DiskLruCache: `<sha256(url)>.1` = body) or Picasso /
     * OkHttp (`picasso-cache`: `<md5(url)>.1` = body), so the bytes are usually on disk before the user taps.
     */
    val HOST_IMAGE_CACHES = listOf("coil3_disk_cache", "coil_cache", "picasso-cache", "image_manager_disk_cache")

    /** Candidate cache file names for [url] in a DiskLruCache-style directory (pure, unit-tested). */
    fun cacheNames(url: String): List<String> {
        fun hex(alg: String) = java.security.MessageDigest.getInstance(alg).digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
        val sha = hex("SHA-256"); val md5 = hex("MD5")
        return listOf("$sha.1", "$md5.1", sha, md5)
    }

    private fun stem(ref: String) = "bg_${ref.hashCode().toUInt().toString(16)}"

    private fun sniffFile(f: File): String? = runCatching {
        val head = ByteArray(16); f.inputStream().use { it.read(head) }; sniffExt(head)
    }.getOrNull()

    /**
     * Local copy of a remote [ref] WITHOUT network: our own earlier download, else Spotify's image disk caches
     * ([HOST_IMAGE_CACHES]). Only files whose magic bytes are an image / mp4 are accepted.
     */
    fun findLocal(ctx: Context, ref: String): File? {
        if (!isRemote(ref)) return null
        cacheDir(ctx).listFiles()?.firstOrNull { it.name.startsWith("${stem(ref)}.") && !it.name.contains(".part") && it.length() > 0 }
            ?.let { return it }
        val names = cacheNames(ref)
        val roots = listOfNotNull(ctx.cacheDir, ctx.externalCacheDir)
        for (root in roots) for (d in HOST_IMAGE_CACHES) {
            val dir = File(root, d)
            if (!dir.isDirectory) continue
            for (n in names) {
                val f = File(dir, n)
                if (f.isFile && f.length() > 0 && sniffFile(f) != null) {
                    SpotifyPlusLog.i(T + "background from Spotify cache ${dir.name}/${f.name} (${f.length()} B)")
                    return f
                }
            }
        }
        SpotifyPlusLog.i(T + "background not in local caches (checked ours + ${HOST_IMAGE_CACHES.filter { d -> roots.any { File(it, d).isDirectory } }})")
        return null
    }

    private val fetches = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.FutureTask<File?>>()

    /**
     * Local file for [ref] (remote http(s) only; local refs → null, opened directly). Local caches first; otherwise ONE
     * shared download per URL (concurrent callers join it, unique temp file + atomic rename) that the caller waits for
     * at most [waitMs]. A download that is still running when the wait expires keeps going in the background and fills
     * the cache for the next tap; it never emits anything itself.
     */
    fun localize(ctx: Context, ref: String, waitMs: Long = 6_000): File? {
        if (!isRemote(ref)) return null
        findLocal(ctx, ref)?.let { return it }
        val task = fetches.computeIfAbsent(ref) { r ->
            java.util.concurrent.FutureTask<File?> { try { download(ctx, r) } finally { fetches.remove(r) } }
                .also { t -> Thread(t, "SpotifyPlus-bg").apply { isDaemon = true }.start() }
        }
        return try { task.get(waitMs, java.util.concurrent.TimeUnit.MILLISECONDS) }
        catch (t: java.util.concurrent.TimeoutException) { SpotifyPlusLog.w(T + "background download still running after $waitMs ms — composing without it: $ref"); null }
        catch (t: Throwable) { SpotifyPlusLog.w(T + "background download failed: $ref", (t as? java.util.concurrent.ExecutionException)?.cause ?: t); null }
    }

    private fun download(ctx: Context, ref: String): File {
        val ext = ref.substringBefore('?').substringAfterLast('/').substringAfterLast('.', "").lowercase().takeIf { it.length in 2..4 } ?: "bin"
        val f = File(cacheDir(ctx), "${stem(ref)}.$ext")
        val t0 = System.currentTimeMillis()
        val c = URL(ref).openConnection().apply { connectTimeout = 5_000; readTimeout = 8_000 }
        val tmp = File(f.path + ".part" + Thread.currentThread().id)
        try {
            c.getInputStream().use { inp -> tmp.outputStream().use { out ->
                val buf = ByteArray(64 * 1024); var total = 0L
                while (true) {
                    val n = inp.read(buf); if (n < 0) break; total += n
                    if (total > MAX_DOWNLOAD) error("download > 64 MB"); out.write(buf, 0, n)
                    if (System.currentTimeMillis() - t0 > 60_000) error("download > 60 s")
                }
            } }
            val dst = sniffFile(tmp)?.takeIf { it != ext }?.let { File(f.path.substringBeforeLast('.') + ".$it") } ?: f
            if (!tmp.renameTo(dst)) error("rename failed")
            SpotifyPlusLog.i(T + "downloaded background ${dst.length()} B (${dst.extension}) in ${System.currentTimeMillis() - t0} ms: $ref")
            return dst
        } finally { tmp.delete() }
    }

    /** File type from magic bytes (pure, unit-tested): jpg / png / webp / gif / mp4, else null. */
    fun sniffExt(h: ByteArray): String? {
        fun at(i: Int, vararg b: Int) = h.size >= i + b.size && b.withIndex().all { (k, v) -> (h[i + k].toInt() and 0xFF) == v }
        return when {
            at(0, 0xFF, 0xD8, 0xFF) -> "jpg"
            at(0, 0x89, 0x50, 0x4E, 0x47) -> "png"
            at(0, 0x52, 0x49, 0x46, 0x46) && at(8, 0x57, 0x45, 0x42, 0x50) -> "webp"
            at(0, 0x47, 0x49, 0x46, 0x38) -> "gif"
            at(4, 0x66, 0x74, 0x79, 0x70) -> "mp4"
            else -> null
        }
    }

    private fun open(ctx: Context, ref: String, waitMs: Long): InputStream? {
        if (isRemote(ref)) return localize(ctx, ref, waitMs)?.inputStream()
        return if (ref.startsWith("/")) File(ref).inputStream()
        else Uri.parse(ref).let { u -> if (u.scheme == "file") File(u.path!!).inputStream() else ctx.contentResolver.openInputStream(u) }
    }

    /** Decode [ref], subsampled so it is still at least [minW]×[minH] (avoids OOM on large template backgrounds). */
    private fun decode(ctx: Context, ref0: String, minW: Int = 0, minH: Int = 0, waitMs: Long = 0): Bitmap? = runCatching {
        // remote: resolve to a local file once (cache / bounded download); unavailable → null, never stream from network
        val ref = if (isRemote(ref0)) localize(ctx, ref0, waitMs)?.path ?: return null else ref0
        val o = BitmapFactory.Options()
        if (minW > 0) {
            o.inJustDecodeBounds = true
            open(ctx, ref, waitMs)?.use { BitmapFactory.decodeStream(it, null, o) }
            o.inSampleSize = if (o.outWidth > 0 && o.outHeight > 0) sampleSize(o.outWidth, o.outHeight, minW, minH) else 1
            o.inJustDecodeBounds = false
        }
        val b = open(ctx, ref, waitMs)?.use { BitmapFactory.decodeStream(it, null, o) }
        SpotifyPlusLog.i(T + "decode ${ref.take(160)} -> ${b?.let { "${it.width}x${it.height} hasAlpha=${it.hasAlpha()}" } ?: "null"} (sample=${o.inSampleSize})")
        b
    }.onFailure { SpotifyPlusLog.w(T + "decode ${ref0.take(160)} failed", it) }.getOrNull()

    private fun videoFrame(ctx: Context, ref: String, waitMs: Long): Bitmap? = runCatching {
        val local = localize(ctx, ref, waitMs)
        if (isRemote(ref) && local == null) return null
        val r = MediaMetadataRetriever()
        try {
            when {
                local != null -> r.setDataSource(local.path)
                ref.startsWith("/") -> r.setDataSource(ref)
                else -> r.setDataSource(ctx, Uri.parse(ref))
            }
            r.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC).also {
                SpotifyPlusLog.i(T + "video frame ${ref.take(160)} -> ${it?.let { b -> "${b.width}x${b.height}" }}")
            }
        } finally { runCatching { r.release() } }
    }.onFailure { SpotifyPlusLog.w(T + "video frame ${ref.take(160)} failed", it) }.getOrNull()

    /**
     * Flatten [story] into an opaque 1080×1920 PNG in the cache dir: background (gradient / centre-cropped image / first
     * video frame / #1F1F1F) + sticker centred at [STICKER_FRAC] of the width. Returns null when the sticker cannot be
     * drawn (caller falls back). Never throws. A remote background is taken from local caches; a download is waited
     * for at most [bgWaitMs] (image) / 2×[bgWaitMs] (video) — when it is not there in time the sticker is still composed,
     * on #1F1F1F, so the output always contains the song card.
     */
    fun render(ctx: Context, story: Story, bgWaitMs: Long = 6_000): File? = runCatching {
        if (story.sticker == null) { SpotifyPlusLog.i(T + "story render skipped: no sticker (bg=${story.bg})"); return null }
        val t0 = System.currentTimeMillis()
        val cw = CANVAS_W; val ch = CANVAS_H
        val sticker = decode(ctx, story.sticker) ?: run { SpotifyPlusLog.w(T + "story render: sticker not decodable"); return null }
        val out = Bitmap.createBitmap(cw, ch, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
        var bgDesc = "fallback #1F1F1F"
        when (val bg = story.bg) {
            is Bg.Gradient -> {
                paint.shader = LinearGradient(0f, 0f, 0f, ch.toFloat(), bg.top, bg.bottom, Shader.TileMode.CLAMP)
                canvas.drawRect(0f, 0f, cw.toFloat(), ch.toFloat(), paint)
                paint.shader = null; bgDesc = "gradient"
            }
            is Bg.Image, is Bg.Video -> {
                val b = if (bg is Bg.Image) decode(ctx, bg.ref, cw, ch, bgWaitMs) else videoFrame(ctx, (bg as Bg.Video).ref, bgWaitMs * 2)
                if (b != null) {
                    val s = centerCrop(b.width, b.height, cw, ch)
                    canvas.drawColor(Color.BLACK)
                    canvas.drawBitmap(b, Rect(s[0], s[1], s[2], s[3]), Rect(0, 0, cw, ch), paint)
                    bgDesc = "${if (bg is Bg.Image) "image" else "video frame"} ${b.width}x${b.height}"
                    b.recycle()
                } else canvas.drawColor(FALLBACK_BG)
            }
            null -> canvas.drawColor(FALLBACK_BG)
        }
        val r = stickerRect(cw, ch, sticker.width, sticker.height)
        canvas.drawBitmap(sticker, null, RectF(r[0], r[1], r[2], r[3]), paint)
        val sw = sticker.width; val sh = sticker.height
        sticker.recycle()
        val dir = cacheDir(ctx)
        dir.listFiles()?.filter { val age = System.currentTimeMillis() - it.lastModified()
            if (it.name.startsWith("bg_")) age > 7 * 86_400_000L else age > 3_600_000L }?.forEach { it.delete() }
        // "_nobg": an image / video background was wanted but not available in time — not cached by the caller
        val missing = (story.bg is Bg.Image || story.bg is Bg.Video) && bgDesc.startsWith("fallback")
        val f = File(dir, "ShareCard_${System.currentTimeMillis()}${if (missing) NO_BG_SUFFIX else ""}.png")
        f.outputStream().use { if (!out.compress(Bitmap.CompressFormat.PNG, 100, it)) error("compress failed") }
        out.recycle()
        SpotifyPlusLog.i(T + "story rendered ${cw}x$ch bg=$bgDesc sticker ${sw}x$sh -> ${r.joinToString { "%.0f".format(it) }} " +
            "in ${System.currentTimeMillis() - t0} ms -> ${f.name} (${f.length()} B)")
        f
    }.onFailure { SpotifyPlusLog.w(T + "story render failed", it) }.getOrNull()
}
