package dev.kifranei.spotifyplus.hook

import android.app.Application
import android.app.Instrumentation
import android.util.Log
import dev.kifranei.spotifyplus.BuildConfig
import dev.kifranei.spotifyplus.util.ModulePrefs
import dev.kifranei.spotifyplus.util.SpotifyPlusLog
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Modern libxposed API 102 entry. Do not touch Looper/Handler/Context in [onModuleLoaded]
 * (zygote / early process) — only remember the module path and attach the runtime.
 *
 * Scopes: [SpotifyHost.PACKAGE] (app hooks) and [SystemUiMediaActionsHook.SYSTEM_UI]
 * (HyperOS CustomAction denylist unblock).
 */
class HookEntry : XposedModule() {
    private val spotifyInstalled = AtomicBoolean(false)
    private val systemUiInstalled = AtomicBoolean(false)

    @Volatile private var processName: String = ""

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        // Zygote-safe: no Looper / Context / Application / logging infra beyond framework log.
        processName = param.processName
        ModernHookRuntime.attach(this)
        runCatching {
            val info = getModuleApplicationInfo()
            SpotifyHost.modulePath = info.sourceDir ?: info.publicSourceDir
        }
        log(
            Log.INFO,
            "SpotifyPlus",
            "module v${BuildConfig.VERSION_NAME} loaded process=${param.processName} " +
                "framework=$frameworkName $frameworkVersion api=$apiVersion systemServer=${param.isSystemServer}",
        )
    }

    override fun onPackageReady(param: PackageReadyParam) {
        ModernHookRuntime.attach(this)
        when (param.packageName) {
            SpotifyHost.PACKAGE -> installSpotify(param)
            SystemUiMediaActionsHook.SYSTEM_UI -> installSystemUi(param)
        }
    }

    private fun installSystemUi(param: PackageReadyParam) {
        if (!systemUiInstalled.compareAndSet(false, true)) return
        ModulePrefs.bindRemote { name -> runCatching { getRemotePreferences(name) }.getOrNull() }
        val prefs = ModulePrefs.remote()
        if (!prefs.mediaNotifActions) {
            log(Log.INFO, "SpotifyPlus", "SystemUI: mediaNotifActions off — skip unblock")
            return
        }
        val cl = param.classLoader ?: HookEntry::class.java.classLoader!!
        step("SystemUiMediaActionsHook") { SystemUiMediaActionsHook.install(cl) }
    }

    private fun installSpotify(param: PackageReadyParam) {
        // Main process only (processName equals package name).
        if (processName.isNotEmpty() && processName != SpotifyHost.PACKAGE) {
            log(Log.INFO, "SpotifyPlus", "non-main process $processName: skipped")
            return
        }
        if (!spotifyInstalled.compareAndSet(false, true)) return

        val cl = param.classLoader ?: HookEntry::class.java.classLoader!!
        SpotifyHost.classLoader = cl
        ModulePrefs.bindRemote { name -> runCatching { getRemotePreferences(name) }.getOrNull() }
        val prefs = ModulePrefs.remote()
        SpotifyPlusLog.i(
            "module v${BuildConfig.VERSION_NAME} ready in pid=${android.os.Process.myPid()} " +
                "process=$processName prefs=$prefs",
        )

        step("application-ready hook") {
            ModernHookRuntime.hookAllMethods(
                Instrumentation::class.java,
                "callApplicationOnCreate",
                object : ModernHookRuntime.MethodHook() {
                    override fun afterHookedMethod(p: ModernHookRuntime.MethodHookParam) {
                        (p.args.getOrNull(0) as? Application)?.let(SpotifyHost::onApplicationCreated)
                    }
                },
            )
        }
        runCatching {
            val cur = Class.forName("android.app.ActivityThread")
                .getMethod("currentApplication").invoke(null) as? Application
            if (cur != null) {
                SpotifyPlusLog.i("Application already present")
                SpotifyHost.onApplicationCreated(cur)
            }
        }

        step("PlaybackHook") { PlaybackHook.install() }
        if (prefs.mediaNotifActions) step("MediaNotifActionsHook") { MediaNotifActionsHook.install(prefs) }
        if (prefs.canvasDownload) step("CanvasHook") { CanvasHook.install(cl, prefs) }
        if (prefs.artistCoverDownload) step("ArtistCoverHook") { ArtistCoverHook.install(cl, prefs) }
        if (prefs.shareCardEnhance) step("ShareCardHook") { ShareCardHook.install(cl, prefs) }
        if (prefs.explicitAsE) step("ExplicitLabelHook") { ExplicitLabelHook.install() }
        if (prefs.lyricsBridge) step("LyricsBridgeHook") { LyricsBridgeHook.install(prefs) }

        SpotifyHost.whenReady { app ->
            SpotifyHost.io.execute {
                val r = try {
                    DexResolver.resolve(app)
                } catch (t: Throwable) {
                    SpotifyPlusLog.e("anchor resolution failed", t); null
                }
                if (r != null) {
                    if (prefs.lyricsBridge || prefs.artistCoverDownload) {
                        step("HeaderCapture(${r.headersClass})") { HeaderCapture.install(cl, r.headersClass) }
                    }
                    if (prefs.canvasDownload || prefs.artistCoverDownload || prefs.shareCardEnhance) {
                        step("MoreMenuHook(${r.menuVmClass})") { MoreMenuHook.install(cl, r.menuVmClass, prefs) }
                    }
                }
                SpotifyPlusLog.i("setup complete")
                SpotifyPlusLog.publish(true)
            }
        }
    }

    private inline fun step(name: String, block: () -> Unit) {
        try {
            block()
            SpotifyPlusLog.i("install $name: ok")
            log(Log.INFO, "SpotifyPlus", "install $name: ok")
        } catch (t: Throwable) {
            SpotifyPlusLog.e("install $name: FAILED", t)
            log(Log.ERROR, "SpotifyPlus", "install $name: FAILED", t)
        }
    }
}
