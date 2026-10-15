package dev.kifranei.spotifyplus.hook

import android.app.Application
import android.app.Instrumentation
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.IXposedHookZygoteInit
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage
import dev.kifranei.spotifyplus.util.ModulePrefs
import dev.kifranei.spotifyplus.util.SpotifyPlusLog

/** LSPosed entry. Scope: [SpotifyHost.PACKAGE], main process only. */
class HookEntry : IXposedHookZygoteInit, IXposedHookLoadPackage {
    override fun initZygote(startupParam: IXposedHookZygoteInit.StartupParam) {
        SpotifyHost.modulePath = startupParam.modulePath
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != SpotifyHost.PACKAGE || lpparam.processName != lpparam.packageName) return
        SpotifyPlusLog.i("inject pid=${android.os.Process.myPid()} process=${lpparam.processName}")
        val cl = lpparam.classLoader
        SpotifyHost.classLoader = cl
        val prefs = ModulePrefs.remote()

        // Application-ready signal (Instrumentation is framework → stable under R8)
        XposedBridge.hookAllMethods(Instrumentation::class.java, "callApplicationOnCreate", object : XC_MethodHook() {
            override fun afterHookedMethod(p: MethodHookParam) {
                (p.args[0] as? Application)?.let(SpotifyHost::onApplicationCreated)
            }
        })

        PlaybackHook.install() // now-playing track for menu actions (cheap, framework-only)
        if (prefs.canvasDownload) CanvasHook.install(cl, prefs)
        if (prefs.artistCoverDownload) ArtistCoverHook.install(cl, prefs)
        if (prefs.shareCardEnhance) ShareCardHook.install(cl, prefs)
        if (prefs.lyricsBridge) LyricsBridgeHook.install(prefs)

        // Anything needing obfuscated host classes is resolved off the main thread once the app exists.
        SpotifyHost.whenReady { app ->
            SpotifyHost.io.execute {
                try {
                    val r = DexResolver.resolve(app)
                    if (prefs.lyricsBridge || prefs.artistCoverDownload) HeaderCapture.install(cl, r.headersClass)
                    if (prefs.canvasDownload || prefs.artistCoverDownload || prefs.shareCardEnhance) MoreMenuHook.install(cl, r.menuVmClass, prefs)
                } catch (t: Throwable) {
                    SpotifyPlusLog.e("anchor resolution failed", t)
                }
            }
        }
    }
}
