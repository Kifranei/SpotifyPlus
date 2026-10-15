package dev.kifranei.spotifyplus.hook

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import dev.kifranei.spotifyplus.net.HeaderStore
import dev.kifranei.spotifyplus.util.SpotifyPlusLog

/**
 * Captures auth headers from the host's (shaded, R8-renamed) `okhttp3.Headers` constructor `(String[])`.
 * Same technique as LyricProvider PR #121, anchored on a string instead of a structural scan.
 */
object HeaderCapture {
    @Volatile private var done = false

    fun install(cl: ClassLoader, headersClassName: String?) {
        if (done || headersClassName == null) return
        val ctor = DexResolver.loadCtor(cl, headersClassName) { it.size == 1 && it[0] == Array<String>::class.java }
        if (ctor == null) {
            SpotifyPlusLog.w("HeaderCapture: $headersClassName has no (String[]) ctor"); return
        }
        done = true
        XposedBridge.hookMethod(ctor, object : XC_MethodHook() {
            override fun afterHookedMethod(p: MethodHookParam) {
                val arr = p.args[0] as? Array<*> ?: return
                runCatching { HeaderStore.record(arr) }
            }
        })
        SpotifyPlusLog.i("HeaderCapture hooked $headersClassName.<init>(String[])")
    }
}
