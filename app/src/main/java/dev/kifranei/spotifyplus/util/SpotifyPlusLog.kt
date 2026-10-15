package dev.kifranei.spotifyplus.util

import android.util.Log

object SpotifyPlusLog {
    private const val TAG = "SpotifyPlus"
    fun i(msg: String) = Log.i(TAG, msg)
    fun d(msg: String) = Log.d(TAG, msg)
    fun w(msg: String, t: Throwable? = null) = Log.w(TAG, msg, t)
    fun e(msg: String, t: Throwable? = null) = Log.e(TAG, msg, t)
}
