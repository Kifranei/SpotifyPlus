package dev.kifranei.spotifyplus.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.kifranei.spotifyplus.util.SpotifyPlusLog

/** Receives the status snapshot the hook sends from inside Spotify; shown in Settings > 诊断. */
class StatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != SpotifyPlusLog.ACTION_STATUS) return
        val text = intent.getStringExtra(SpotifyPlusLog.EXTRA_STATUS) ?: return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("status", text).putLong("time", System.currentTimeMillis()).apply()
    }
    companion object { const val PREFS = "spotifyplus_status" }
}
