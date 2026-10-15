package dev.kifranei.spotifyplus.util

import android.content.Context
import android.content.Intent
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * Logs to logcat (tag SpotifyPlus), framework log, a file inside Spotify's external files dir
 * (/sdcard/Android/data/com.spotify.music/files/SpotifyPlus-log.txt) and, as a status snapshot, to the module
 * app (Settings > 诊断) through an explicit broadcast.
 */
object SpotifyPlusLog {
    private const val TAG = "SpotifyPlus"
    const val FILE_NAME = "SpotifyPlus-log.txt"
    const val ACTION_STATUS = "dev.kifranei.spotifyplus.STATUS"
    const val EXTRA_STATUS = "status"
    private const val MODULE_PKG = "dev.kifranei.spotifyplus"

    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val recent = ArrayDeque<String>()
    private var files = ArrayList<File>()
    private var ctx: Context? = null
    private var lastPublish = 0L

    fun i(msg: String) = emit('I', msg, null)
    fun d(msg: String) { Log.d(TAG, msg) }
    fun w(msg: String, t: Throwable? = null) = emit('W', msg, t)
    fun e(msg: String, t: Throwable? = null) = emit('E', msg, t)

    /** Called once the host Application exists: starts file logging and flushes the buffered lines. */
    @Synchronized
    fun attach(app: Context) {
        if (ctx != null) return
        ctx = app
        runCatching { app.getExternalFilesDir(null)?.let { files.add(File(it, FILE_NAME)) } }
        runCatching { files.add(File(app.filesDir, FILE_NAME)) }
        files.forEach { f -> runCatching { if (f.length() > 512 * 1024) f.delete() } }
        val snapshot = recent.toList()
        files.forEach { f -> runCatching { f.appendText("---- attach ----\n" + snapshot.joinToString("\n") + "\n") } }
        publish(true)
    }

    @Synchronized
    private fun emit(level: Char, msg: String, t: Throwable?) {
        val tr = t?.let { "\n" + Log.getStackTraceString(it) } ?: ""
        val line = "${fmt.format(Date())} $level [${android.os.Process.myPid()}] $msg$tr"
        when (level) {
            'E' -> Log.e(TAG, msg, t)
            'W' -> Log.w(TAG, msg, t)
            else -> Log.i(TAG, msg)
        }
        // framework log via ModernHookRuntime when attached
        runCatching { dev.kifranei.spotifyplus.hook.ModernHookRuntime.log(msg + tr) }
        recent.addLast(line); while (recent.size > 60) recent.removeFirst()
        files.forEach { f -> runCatching { f.appendText(line + "\n") } }
        publish(false)
    }

    @Synchronized
    fun publish(force: Boolean) {
        val c = ctx ?: return
        val now = System.currentTimeMillis()
        if (!force && now - lastPublish < 2000) return
        lastPublish = now
        runCatching {
            c.sendBroadcast(Intent(ACTION_STATUS).setPackage(MODULE_PKG)
                .putExtra(EXTRA_STATUS, recent.toList().takeLast(40).joinToString("\n")))
        }
    }
}
