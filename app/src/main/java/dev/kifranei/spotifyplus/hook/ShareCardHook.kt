package dev.kifranei.spotifyplus.hook

import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.net.Uri
import dev.kifranei.spotifyplus.util.ModulePrefs
import dev.kifranei.spotifyplus.util.SpotifyPlusLog
import java.io.File

/**
 * Share-card registry + optional auto-save.
 *
 * The 「保存图片 / 分享图片」 entries now live inside Spotify's own share sheet ([ShareMenuHook]); the old Android
 * system-chooser custom actions (EXTRA_CHOOSER_CUSTOM_ACTIONS) were removed because they do not work on HyperOS.
 *
 * We only hook framework / androidx symbols here (stable under R8):
 *  1. `FileProvider.getUriForFile(Context,String,File)` → remember share-card files ([ShareRegistry]);
 *  2. `Instrumentation.execStartActivity(…Intent…)` → only when auto-save is on: an ACTION_SEND/CHOOSER carrying a
 *     host share-card stream is copied (selected card only) to Pictures/Spotify. The intent itself is never modified.
 */
object ShareCardHook {
    @Volatile private var installed = false

    fun install(cl: ClassLoader, prefs: ModulePrefs) {
        if (installed) return
        installed = true
        hookFileProvider(cl)
        if (prefs.shareCardAutoSave) hookStartActivity()
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

    private fun hookStartActivity() {
        ModernHookRuntime.hookAllMethods(Instrumentation::class.java, "execStartActivity", object : ModernHookRuntime.MethodHook() {
            override fun beforeHookedMethod(p: ModernHookRuntime.MethodHookParam) {
                val i = p.args.indexOfFirst { it is Intent }
                if (i < 0) return
                val ctx = (p.args.firstOrNull() as? Context)?.applicationContext ?: SpotifyHost.context() ?: return
                runCatching { autoSave(ctx, p.args[i] as Intent) }
                    .onFailure { SpotifyPlusLog.w("auto-save failed", it) }
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
    private fun autoSave(ctx: Context, intent: Intent) {
        val inner = if (intent.action == Intent.ACTION_CHOOSER) intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) else intent
        if (inner == null) return
        val type = inner.type.orEmpty()
        if (!(type.startsWith("image/") || type.startsWith("video/") || type == "*/*")) return
        val uris = streamsOf(inner).filter { it.scheme == "content" && it.authority?.startsWith(SpotifyHost.PACKAGE) == true }
        val one = ShareRegistry.pickSingle(uris.map { it.toString() }, ShareRegistry.uriOrder())?.let(Uri::parse) ?: return
        if (ShareRegistry.markSaved(one.toString())) Actions.saveShareUris(ctx, listOf(one))
    }
}
