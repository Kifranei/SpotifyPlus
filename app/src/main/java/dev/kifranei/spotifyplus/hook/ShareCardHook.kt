package dev.kifranei.spotifyplus.hook

import android.app.Instrumentation
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.service.chooser.ChooserAction
import dev.kifranei.spotifyplus.util.ModulePrefs
import dev.kifranei.spotifyplus.util.SpotifyPlusLog
import java.io.File

/**
 * Share-card export.
 *
 * Static findings (9.1.90.2270): the share sheet is Compose (`com.spotify.share.sheet.*`, un-obfuscated package
 * names; card config protos in `com.spotify.sharecard.config.impl.proto.*`). Cards are rendered to Bitmaps,
 * compressed to PNG (`Bitmap.compress(PNG,100,OutputStream)` in the share coroutine, p.rr20 case 13, error type
 * `com.spotify.share.file.api.bitmapconverter.CompressFileException`) into a "shareable" directory
 * (`CreateFileException: Could not make shareable directory`) and handed to destinations through
 * `androidx.core.content.FileProvider.getUriForFile`. Card kinds: `share-menu.swatches.background-color-*`,
 * `…background-canvas-image`, `…background-canvas-video` (p.kgj) and the lyrics card (`mobile-lyrics-card`, p.s1j0).
 *
 * We therefore hook only framework / androidx symbols (stable under R8):
 *  1. `FileProvider.getUriForFile(Context,String,File)` → remember share-card files ([ShareRegistry]);
 *  2. `Instrumentation.execStartActivity(…Intent…)` → for chooser / ACTION_SEND carrying image|video streams
 *     from the host provider: auto-save to Pictures/Spotify (optional) and, on Android 14+, add chooser custom
 *     actions "保存图片" / "以图片分享" (`EXTRA_CHOOSER_CUSTOM_ACTIONS`).
 * **Untested on device.** Which destinations get FileProvider URIs vs. link-only intents is not verified.
 */
object ShareCardHook {
    private const val ACTION_SAVE = "dev.kifranei.spotifyplus.SAVE_SHARE_CARD"
    private const val EXTRA_URIS = "uris"
    @Volatile private var installed = false

    fun install(cl: ClassLoader, prefs: ModulePrefs) {
        if (installed) return
        installed = true
        hookFileProvider(cl)
        hookStartActivity(prefs)
        SpotifyHost.whenReady { registerReceiver(it) }
    }

    private fun hookFileProvider(cl: ClassLoader) {
        val fp = ModernHookRuntime.findClassIfExists("androidx.core.content.FileProvider", cl)
        if (fp == null) { SpotifyPlusLog.w("ShareCardHook: androidx FileProvider missing"); return }
        ModernHookRuntime.hookAllMethods(fp, "getUriForFile", object : ModernHookRuntime.MethodHook() {
            override fun afterHookedMethod(p: ModernHookRuntime.MethodHookParam) {
                val file = p.args.firstOrNull { it is File } as? File ?: return
                val authority = p.args.getOrNull(1) as? String
                val uri = p.result as? Uri ?: return
                if (ShareRegistry.looksLikeShareCard(file.path, authority)) {
                    ShareRegistry.record(file, uri.toString())
                    SpotifyPlusLog.d("share file registered: ${file.name}")
                }
            }
        })
    }

    private fun hookStartActivity(prefs: ModulePrefs) {
        ModernHookRuntime.hookAllMethods(Instrumentation::class.java, "execStartActivity", object : ModernHookRuntime.MethodHook() {
            override fun beforeHookedMethod(p: ModernHookRuntime.MethodHookParam) {
                val i = p.args.indexOfFirst { it is Intent }
                if (i < 0) return
                val ctx = (p.args.firstOrNull() as? Context)?.applicationContext ?: SpotifyHost.context() ?: return
                runCatching { onStart(ctx, p.args[i] as Intent, prefs) }
                    .onFailure { SpotifyPlusLog.w("share intent handling failed", it) }
            }
        })
    }

    @Suppress("DEPRECATION")
    private fun streamsOf(i: Intent): List<Uri> = when (i.action) {
        Intent.ACTION_SEND -> listOfNotNull(i.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
        Intent.ACTION_SEND_MULTIPLE -> i.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
        else -> emptyList()
    }

    @Suppress("DEPRECATION")
    private fun onStart(ctx: Context, intent: Intent, prefs: ModulePrefs) {
        val isChooser = intent.action == Intent.ACTION_CHOOSER
        val inner = if (isChooser) intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) else intent
        if (inner == null) return
        if (inner.action != Intent.ACTION_SEND && inner.action != Intent.ACTION_SEND_MULTIPLE) return
        val type = inner.type.orEmpty()
        var uris = streamsOf(inner).filter { it.scheme == "content" && it.authority?.startsWith(SpotifyHost.PACKAGE) == true }
        val mediaType = type.startsWith("image/") || type.startsWith("video/") || type == "*/*"
        if (uris.isEmpty() || !mediaType) {
            // link-only share: offer the most recent share cards if one was rendered moments ago
            if (!isChooser) return
            val recent = listOfNotNull(ShareRegistry.latestFile())
            if (recent.isEmpty()) return
            if (Build.VERSION.SDK_INT >= 34) addChooserActions(ctx, intent, null, recent)
            return
        }
        // Only the currently selected card: collapse multi-stream shares to a single ACTION_SEND.
        val one = ShareRegistry.pickSingle(uris.map { it.toString() }, ShareRegistry.uriOrder())?.let(Uri::parse)
        if (one == null) return
        uris = listOf(one)
        if (inner.action == Intent.ACTION_SEND_MULTIPLE || streamsOf(inner).size > 1) {
            inner.action = Intent.ACTION_SEND
            inner.removeExtra(Intent.EXTRA_STREAM)
            inner.putExtra(Intent.EXTRA_STREAM, one)
            inner.clipData = android.content.ClipData.newRawUri("card", one)
            if (isChooser) intent.putExtra(Intent.EXTRA_INTENT, inner)
        }
        if (prefs.shareCardAutoSave) {
            val fresh = uris.filter { ShareRegistry.markSaved(it.toString()) }
            if (fresh.isNotEmpty()) Actions.saveShareUris(ctx, fresh)
        }
        if (isChooser && Build.VERSION.SDK_INT >= 34) addChooserActions(ctx, intent, uris, null)
    }

    private fun addChooserActions(ctx: Context, chooser: Intent, uris: List<Uri>?, files: List<File>?) {
        val list = ArrayList<Uri>()
        uris?.let(list::addAll)
        files?.forEach { f -> ShareRegistry.entryUri(f)?.let { list += Uri.parse(it) } }
        if (list.isEmpty()) return
        val src = list.first()
        SpotifyPlusLog.i("share-as-image: picked source uri=$src file=${ShareRegistry.fileForUri(src.toString())?.path}")
        val save = PendingIntent.getBroadcast(
            ctx, 0x5301,
            Intent(ACTION_SAVE).setPackage(ctx.packageName).putStringArrayListExtra(EXTRA_URIS, ArrayList(list.map { it.toString() })),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        // The action runs in SystemUI and the host FileProvider URI is only granted to the chooser's targets for the
        // original share (and the file may be recycled), so export a stable copy to MediaStore NOW (we are in Spotify's
        // process and have access) and share that content Uri, which any target app can read.
        val exported = exportForShare(ctx, src)
        val shareUri = exported ?: src
        SpotifyPlusLog.i("share-as-image: share uri=$shareUri (mediastore=${exported != null})")
        val mime = mimeFor(ctx, shareUri, src)
        val send = Intent(Intent.ACTION_SEND).setType(mime)
            .putExtra(Intent.EXTRA_STREAM, shareUri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = android.content.ClipData.newRawUri("card", shareUri)
        val outer = Intent.createChooser(send, "分享图片")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        outer.clipData = android.content.ClipData.newRawUri("card", shareUri)
        val opts = if (Build.VERSION.SDK_INT >= 34) {
            runCatching {
                android.app.ActivityOptions.makeBasic()
                    .setPendingIntentCreatorBackgroundActivityStartMode(android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                    .toBundle()
            }.getOrNull()
        } else null
        val share = PendingIntent.getActivity(
            ctx, 0x5302, outer,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT, opts,
        )
        val icon = Icon.createWithResource(ctx, android.R.drawable.ic_menu_save)
        val icon2 = Icon.createWithResource(ctx, android.R.drawable.ic_menu_share)
        val actions = arrayOf(
            ChooserAction.Builder(icon, "保存图片", save).build(),
            ChooserAction.Builder(icon2, "以图片分享", share).build(),
        )
        chooser.putExtra(Intent.EXTRA_CHOOSER_CUSTOM_ACTIONS, actions)
    }

    private val exportCache = java.util.concurrent.ConcurrentHashMap<String, Uri>()

    internal fun mimeFor(ctx: Context, u: Uri, fallback: Uri): String {
        val m = runCatching { ctx.contentResolver.getType(u) ?: ctx.contentResolver.getType(fallback) }.getOrNull()
        return if (m != null && (m.startsWith("image/") || m.startsWith("video/"))) m else "image/png"
    }

    /** Copy a host share-card Uri into MediaStore (Pictures/Spotify, IS_PENDING handled); null + log on failure. */
    private fun exportForShare(ctx: Context, src: Uri): Uri? {
        exportCache[src.toString()]?.let { c ->
            val ok = runCatching { ctx.contentResolver.openInputStream(c)?.use { true } ?: false }.getOrDefault(false)
            if (ok) { SpotifyPlusLog.d("share-as-image: reuse export $c"); return c }
            exportCache.remove(src.toString())
        }
        return try {
            val mime = runCatching { ctx.contentResolver.getType(src) }.getOrNull() ?: "image/png"
            val ext = when { mime.startsWith("video/") -> "mp4"; mime.contains("jpeg") -> "jpg"; mime.contains("webp") -> "webp"; else -> "png" }
            val input = ctx.contentResolver.openInputStream(src) ?: error("openInputStream returned null (file gone?)")
            val out = input.use {
                dev.kifranei.spotifyplus.media.MediaSaver.save(ctx, it, "ShareCard_${System.currentTimeMillis()}.$ext",
                    dev.kifranei.spotifyplus.util.SpotifyUris.mimeForExt(ext), dev.kifranei.spotifyplus.media.MediaSaver.kindFor(ext))
            }
            exportCache[src.toString()] = out
            SpotifyPlusLog.i("share-as-image: exported $src -> $out")
            out
        } catch (t: Throwable) {
            SpotifyPlusLog.w("share-as-image: export to MediaStore failed for $src", t)
            SpotifyHost.toast("以图片分享：导出图片失败（${t.javaClass.simpleName}: ${t.message}），将尝试直接分享")
            null
        }
    }

    private fun registerReceiver(app: Context) {
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val uris = i.getStringArrayListExtra(EXTRA_URIS).orEmpty().map(Uri::parse)
                Actions.saveShareUris(c.applicationContext, uris)
            }
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(r, IntentFilter(ACTION_SAVE), Context.RECEIVER_NOT_EXPORTED)
            else app.registerReceiver(r, IntentFilter(ACTION_SAVE))
        }.onFailure { SpotifyPlusLog.w("receiver registration failed", it) }
    }
}
