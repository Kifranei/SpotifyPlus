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
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
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
        val fp = XposedHelpers.findClassIfExists("androidx.core.content.FileProvider", cl)
        if (fp == null) { SpotifyPlusLog.w("ShareCardHook: androidx FileProvider missing"); return }
        XposedBridge.hookAllMethods(fp, "getUriForFile", object : XC_MethodHook() {
            override fun afterHookedMethod(p: MethodHookParam) {
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
        XposedBridge.hookAllMethods(Instrumentation::class.java, "execStartActivity", object : XC_MethodHook() {
            override fun beforeHookedMethod(p: MethodHookParam) {
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
            val recent = ShareRegistry.recentFiles()
            if (recent.isEmpty()) return
            if (Build.VERSION.SDK_INT >= 34) addChooserActions(ctx, intent, null, recent)
            return
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
        val save = PendingIntent.getBroadcast(
            ctx, 0x5301,
            Intent(ACTION_SAVE).setPackage(ctx.packageName).putStringArrayListExtra(EXTRA_URIS, ArrayList(list.map { it.toString() })),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val send = Intent(Intent.ACTION_SEND).setType("image/*")
            .putExtra(Intent.EXTRA_STREAM, list.first())
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = android.content.ClipData.newRawUri("card", list.first())
        val share = PendingIntent.getActivity(
            ctx, 0x5302, Intent.createChooser(send, "分享图片").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val icon = Icon.createWithResource(ctx, android.R.drawable.ic_menu_save)
        val icon2 = Icon.createWithResource(ctx, android.R.drawable.ic_menu_share)
        val actions = arrayOf(
            ChooserAction.Builder(icon, "保存图片", save).build(),
            ChooserAction.Builder(icon2, "以图片分享", share).build(),
        )
        chooser.putExtra(Intent.EXTRA_CHOOSER_CUSTOM_ACTIONS, actions)
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
