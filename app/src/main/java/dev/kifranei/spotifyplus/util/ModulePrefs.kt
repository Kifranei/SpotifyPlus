package dev.kifranei.spotifyplus.util

import android.content.SharedPreferences
import dev.kifranei.spotifyplus.BuildConfig

/**
 * Module toggles. Settings Activity writes local SharedPreferences and syncs to LSPosed
 * remote preferences when the Xposed service is available; hooks read via
 * [bindRemote] / [remote] (libxposed [XposedModule.getRemotePreferences]).
 */
data class ModulePrefs(
    val canvasDownload: Boolean = true,
    val lyricsBridge: Boolean = true,
    val artistCoverDownload: Boolean = true,
    val shareCardEnhance: Boolean = true,
    val shareCardAutoSave: Boolean = false,
    val lyricsTranslation: Boolean = true,
    /** BCP-47 tag sent as `clientLanguage`; blank = device locale. e.g. zh-Hans / zh-TW. */
    val lyricsLanguage: String = "",
    /** Optional `market` query param (e.g. TW). Blank = omit. */
    val lyricsMarket: String = "",
    /** Restore like / shuffle / repeat on Xiaomi HyperOS media notification. Default on. */
    val mediaNotifActions: Boolean = true,
) {
    companion object {
        const val PREFS_NAME = "spotifyplus_prefs"
        const val KEY_CANVAS = "canvas_download"
        const val KEY_LYRICS = "lyrics_bridge"
        const val KEY_ARTIST = "artist_cover"
        const val KEY_SHARE = "share_card"
        const val KEY_SHARE_AUTOSAVE = "share_card_autosave"
        const val KEY_LYRICS_TRANSLATION = "lyrics_translation"
        const val KEY_LYRICS_LANG = "lyrics_language"
        const val KEY_LYRICS_MARKET = "lyrics_market"
        const val KEY_MEDIA_NOTIF = "media_notif_actions"

        @Volatile
        private var remoteProvider: ((String) -> SharedPreferences?)? = null

        /** Called from HookEntry once the XposedModule is ready. */
        fun bindRemote(provider: (String) -> SharedPreferences?) {
            remoteProvider = provider
        }

        /** Never throws: any failure yields the all-enabled defaults. */
        fun remote(): ModulePrefs = try {
            readRemote()
        } catch (t: Throwable) {
            SpotifyPlusLog.w("prefs read failed, using defaults", t)
            ModulePrefs()
        }

        fun from(sp: SharedPreferences): ModulePrefs = ModulePrefs(
            canvasDownload = sp.getBoolean(KEY_CANVAS, true),
            lyricsBridge = sp.getBoolean(KEY_LYRICS, true),
            artistCoverDownload = sp.getBoolean(KEY_ARTIST, true),
            shareCardEnhance = sp.getBoolean(KEY_SHARE, true),
            shareCardAutoSave = sp.getBoolean(KEY_SHARE_AUTOSAVE, false),
            lyricsTranslation = sp.getBoolean(KEY_LYRICS_TRANSLATION, true),
            lyricsLanguage = sp.getString(KEY_LYRICS_LANG, "") ?: "",
            lyricsMarket = sp.getString(KEY_LYRICS_MARKET, "") ?: "",
            mediaNotifActions = sp.getBoolean(KEY_MEDIA_NOTIF, true),
        )

        private fun readRemote(): ModulePrefs {
            val provider = remoteProvider
            val sp = provider?.invoke(PREFS_NAME)
            if (sp != null) {
                SpotifyPlusLog.i("prefs via remote SharedPreferences")
                return from(sp)
            }
            // Best-effort: legacy XSharedPreferences if the framework still exposes it.
            val legacy = runCatching {
                val cls = Class.forName("de.robv.android.xposed.XSharedPreferences")
                val ctor = cls.getConstructor(String::class.java, String::class.java)
                val instance = ctor.newInstance(BuildConfig.APPLICATION_ID, PREFS_NAME)
                cls.getMethod("reload").invoke(instance)
                instance as SharedPreferences
            }.getOrNull()
            if (legacy != null) {
                SpotifyPlusLog.i("prefs via legacy XSharedPreferences")
                return from(legacy)
            }
            SpotifyPlusLog.i("prefs unavailable — using defaults (all on)")
            return ModulePrefs()
        }
    }
}
