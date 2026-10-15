package dev.kifranei.spotifyplus.hook

import android.app.Application
import android.app.Instrumentation
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.IXposedHookZygoteInit
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage
import dev.kifranei.spotifyplus.BuildConfig
import dev.kifranei.spotifyplus.util.ModulePrefs
import dev.kifranei.spotifyplus.util.SpotifyPlusLog

/** LSPosed entry. Scope: [SpotifyHost.PACKAGE]. Every install step is isolated so one failure never blocks the rest. */
class HookEntry : IXposedHookZygoteInit, IXposedHookLoadPackage {
    override fun initZygote(startupParam: IXposedHookZygoteInit.StartupParam) {
        SpotifyHost.modulePath = startupParam.modulePath
        SpotifyPlusLog.i("initZygote modulePath=${startupParam.modulePath} v${BuildConfig.VERSION_NAME}")
    }

    private inline fun step(name: String, block: () -> Unit) {
        try { block(); SpotifyPlusLog.i("install $name: ok") } catch (t: Throwable) { SpotifyPlusLog.e("install $name: FAILED", t) }
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != SpotifyHost.PACKAGE) return
        SpotifyPlusLog.i("module v${BuildConfig.VERSION_NAME} loaded in pid=${android.os.Process.myPid()} process=${lpparam.processName} " +
            "isFirstApplication=${lpparam.isFirstApplication}")
        // Hook in every Spotify process that gets here; only the main process runs the heavy work.
        if (lpparam.processName != lpparam.packageName) {
            SpotifyPlusLog.i("non-main process ${lpparam.processName}: skipped")
            return
        }
        val cl: ClassLoader = lpparam.classLoader ?: HookEntry::class.java.classLoader!!.also {
            SpotifyPlusLog.w("lpparam.classLoader is null; falling back to module loader")
        }
        SpotifyHost.classLoader = cl
        val prefs = ModulePrefs.remote()
        SpotifyPlusLog.i("prefs: $prefs")

        step("application-ready hook") {
            XposedBridge.hookAllMethods(Instrumentation::class.java, "callApplicationOnCreate", object : XC_MethodHook() {
                override fun afterHookedMethod(p: MethodHookParam) {
                    (p.args[0] as? Application)?.let(SpotifyHost::onApplicationCreated)
                }
            })
        }
        // In case the Application already exists (late injection)
        runCatching {
            val cur = Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null) as? Application
            if (cur != null) { SpotifyPlusLog.i("Application already present"); SpotifyHost.onApplicationCreated(cur) }
        }

        step("PlaybackHook") { PlaybackHook.install() }
        if (prefs.canvasDownload) step("CanvasHook") { CanvasHook.install(cl, prefs) }
        if (prefs.artistCoverDownload) step("ArtistCoverHook") { ArtistCoverHook.install(cl, prefs) }
        if (prefs.shareCardEnhance) step("ShareCardHook") { ShareCardHook.install(cl, prefs) }
        if (prefs.lyricsBridge) step("LyricsBridgeHook") { LyricsBridgeHook.install(prefs) }

        // Anything needing obfuscated host classes is resolved off the main thread once the app exists.
        SpotifyHost.whenReady { app ->
            SpotifyHost.io.execute {
                val r = try { DexResolver.resolve(app) } catch (t: Throwable) {
                    SpotifyPlusLog.e("anchor resolution failed", t); null
                }
                if (r != null) {
                    if (prefs.lyricsBridge || prefs.artistCoverDownload) step("HeaderCapture(${r.headersClass})") { HeaderCapture.install(cl, r.headersClass) }
                    if (prefs.canvasDownload || prefs.artistCoverDownload || prefs.shareCardEnhance) step("MoreMenuHook(${r.menuVmClass})") { MoreMenuHook.install(cl, r.menuVmClass, prefs) }
                }
                SpotifyPlusLog.i("setup complete")
                SpotifyPlusLog.publish(true)
            }
        }
    }
}
