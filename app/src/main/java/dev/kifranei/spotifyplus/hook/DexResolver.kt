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
        }
        SpotifyPlusLog.w("libdexkit.so could not be loaded")
        return false
    }

    fun resolve(app: Application): Resolved {
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
                DexKitBridge.create(app.applicationInfo.sourceDir).use { bridge ->
                    menu = bridge.findMethod { matcher { usingStrings(ANCHOR_MENU_VM) } }
                        .firstOrNull { it.isConstructor }?.className
                    // Headers.of(String...) is a static method whose return type is Headers
                    headers = bridge.findMethod { matcher { usingStrings(ANCHOR_HEADERS) } }
                        .firstOrNull { it.isMethod }?.returnTypeName
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

    fun loadCtor(cl: ClassLoader, className: String?, argCount: Int, firstParam: Class<*>? = null): Constructor<*>? {
        if (className == null) return null
        return runCatching {
            cl.loadClass(className).declaredConstructors.firstOrNull {
                it.parameterCount == argCount && (firstParam == null || it.parameterTypes[0] == firstParam)
            }?.also { it.isAccessible = true }
        }.onFailure { SpotifyPlusLog.w("loadCtor($className) failed", it) }.getOrNull()
    }
}
