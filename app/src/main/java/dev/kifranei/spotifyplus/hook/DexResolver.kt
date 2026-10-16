package dev.kifranei.spotifyplus.hook

import android.app.Application
import android.content.Context
import dev.kifranei.spotifyplus.util.SpotifyPlusLog
import org.luckypray.dexkit.DexKitBridge
import java.lang.reflect.Constructor

/**
 * Resolves R8-obfuscated host classes from **string anchors** with DexKit, caches the answer per
 * (versionName, lastUpdateTime), and falls back to the names recovered from 9.1.90.2270 only if that
 * exact version is installed.
 *
 *  | target                         | anchor string (method must contain it)                                        | 9.1.90.2270 fallback |
 *  |--------------------------------|--------------------------------------------------------------------------------|----------------------|
 *  | ContextMenuViewModel.<init>    | "ContextMenuViewModel cannot contain items with duplicate itemResId"          | p.yal                |
 *  | okhttp3.Headers (String[] ctor)| "Expected alternating header names and values" (Headers.of(vararg) → returns Headers) | p.ad40       |
 */
object DexResolver {
    const val ANCHOR_MENU_VM = "ContextMenuViewModel cannot contain items with duplicate itemResId"
    const val ANCHOR_HEADERS = "Expected alternating header names and values"

    private val FALLBACK_9_1_90_2270 = mapOf("menuVm" to "p.yal", "headers" to "p.ad40")
    private const val CACHE = "spotifyplus_dexcache"

    data class Resolved(val menuVmClass: String?, val headersClass: String?, val how: String)

    @Volatile private var loaded = false

    /** Loads libdexkit.so: normal loadLibrary first, then straight out of the module APK. */
    fun ensureNative(): Boolean {
        if (loaded) return true
        try { System.loadLibrary("dexkit"); loaded = true; return true } catch (_: Throwable) {}
        val mp = SpotifyHost.modulePath
        if (mp != null) {
            for (abi in android.os.Build.SUPPORTED_ABIS) {
                try { System.load("$mp!/lib/$abi/libdexkit.so"); loaded = true; return true } catch (_: Throwable) {}
            }
            // last resort: copy the .so out of the module APK into the host's cache dir and load that
            val dir = SpotifyHost.app?.codeCacheDir ?: SpotifyHost.app?.cacheDir
            if (dir != null) {
                for (abi in android.os.Build.SUPPORTED_ABIS) {
                    try {
                        java.util.zip.ZipFile(mp).use { z ->
                            val e = z.getEntry("lib/$abi/libdexkit.so") ?: return@use
                            val out = java.io.File(dir, "spotifyplus-libdexkit-$abi.so")
                            z.getInputStream(e).use { i -> out.outputStream().use { o -> i.copyTo(o) } }
                            System.load(out.absolutePath)
                            loaded = true
                        }
                        if (loaded) return true
                    } catch (t: Throwable) { SpotifyPlusLog.w("extract/load libdexkit ($abi) failed", t) }
                }
            }
        }
        SpotifyPlusLog.w("libdexkit.so could not be loaded")
        return false
    }

    fun resolve(app: Application): Resolved {
        SpotifyPlusLog.attach(app)
        val sp = app.getSharedPreferences(CACHE, Context.MODE_PRIVATE)
        val info = runCatching { app.packageManager.getPackageInfo(app.packageName, 0) }.getOrNull()
        val key = "${info?.versionName}|${info?.lastUpdateTime}"
        if (sp.getString("key", null) == key) {
            val m = sp.getString("menuVm", null); val h = sp.getString("headers", null)
            if (m != null || h != null) return Resolved(m, h, "cache")
        }
        var menu: String? = null
        var headers: String? = null
        var how = "dexkit"
        if (ensureNative()) {
            try {
                val ai = app.applicationInfo
                val apks = (listOf(ai.sourceDir) + (ai.splitSourceDirs?.toList() ?: emptyList())).distinct()
                SpotifyPlusLog.i("DexResolver: scanning ${apks.size} apk(s): ${apks.joinToString { java.io.File(it).name }}")
                for (apk in apks) {
                    if (menu != null && headers != null) break
                    try {
                        DexKitBridge.create(apk).use { bridge ->
                            if (menu == null) menu = bridge.findMethod { matcher { usingStrings(ANCHOR_MENU_VM) } }
                                .firstOrNull { it.isConstructor }?.className
                            // Headers.of(String...) is a static method whose return type is Headers
                            if (headers == null) headers = bridge.findMethod { matcher { usingStrings(ANCHOR_HEADERS) } }
                                .firstOrNull { it.isMethod }?.returnTypeName
                        }
                    } catch (t: Throwable) {
                        SpotifyPlusLog.w("DexKit resolve failed on ${java.io.File(apk).name}", t)
                    }
                }
            } catch (t: Throwable) {
                SpotifyPlusLog.w("DexKit resolve failed", t)
            }
        } else how = "no-native"
        if ((menu == null || headers == null) && info?.versionName == SpotifyHost.ANALYZED_VERSION) {
            menu = menu ?: FALLBACK_9_1_90_2270["menuVm"]
            headers = headers ?: FALLBACK_9_1_90_2270["headers"]
            how += "+fallback-names"
        }
        if (menu != null || headers != null) {
            sp.edit().putString("key", key).putString("menuVm", menu).putString("headers", headers).apply()
        }
        SpotifyPlusLog.i("DexResolver: menuVm=$menu headers=$headers via $how")
        return Resolved(menu, headers, how)
    }

    /** First declared constructor accepting [accept]. Types matter: R8/Kotlin add synthetic (…, int) overloads. */
    fun loadCtor(cl: ClassLoader, className: String?, accept: (Array<Class<*>>) -> Boolean): Constructor<*>? {
        if (className == null) return null
        return runCatching {
            cl.loadClass(className).declaredConstructors.firstOrNull {
                !it.isSynthetic && accept(it.parameterTypes)
            }?.also { it.isAccessible = true }
        }.onFailure { SpotifyPlusLog.w("loadCtor($className) failed", it) }.getOrNull()
    }
}
