package dev.kifranei.spotifyplus.hook

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import dev.kifranei.spotifyplus.util.SpotifyPlusLog
import java.util.concurrent.Executors

/** Process-wide handles for the injected Spotify process. */
object SpotifyHost {
    const val PACKAGE = "com.spotify.music"
    /** Version the static analysis in DESIGN.md was done against. */
    const val ANALYZED_VERSION = "9.1.90.2270"

    @Volatile var app: Application? = null
    @Volatile var classLoader: ClassLoader? = null
    @Volatile var modulePath: String? = null
    @Volatile var versionName: String = ""
    private val ready = ArrayList<(Application) -> Unit>()

    val main = Handler(Looper.getMainLooper())
    val io = Executors.newCachedThreadPool { r -> Thread(r, "SpotifyPlus-io").apply { isDaemon = true } }

    fun context(): Context? = app

    fun onApplicationCreated(a: Application) {
        app = a
        SpotifyPlusLog.attach(a)
        versionName = runCatching { a.packageManager.getPackageInfo(a.packageName, 0).versionName ?: "" }.getOrDefault("")
        SpotifyPlusLog.i("host app ready, spotify=$versionName (analyzed against $ANALYZED_VERSION)")
        val cbs = synchronized(ready) { ArrayList(ready).also { ready.clear() } }
        cbs.forEach { runCatching { it(a) }.onFailure { t -> SpotifyPlusLog.e("onReady callback failed", t) } }
    }

    fun whenReady(cb: (Application) -> Unit) {
        val a = app
        if (a != null) { cb(a); return }
        synchronized(ready) {
            val a2 = app
            if (a2 != null) { cb(a2); return }
            ready += cb
        }
    }

    fun toast(msg: String) {
        val a = app ?: return
        main.post { runCatching { Toast.makeText(a, msg, Toast.LENGTH_SHORT).show() } }
    }
}
