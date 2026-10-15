package dev.kifranei.spotifyplus.util

import de.robv.android.xposed.XSharedPreferences
import dev.kifranei.spotifyplus.BuildConfig

/**
 * Module toggles. Settings Activity writes the prefs file (LSPosed new-XSharedPreferences,
 * `xposedsharedprefs=true`); hooks read it through [XSharedPreferences].
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
    /** Optional `market` query param (e.g. TW). Blank = omit. Unverified whether server honours it. */
    val lyricsMarket: String = "",
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

        /** Never throws: any failure (unreadable / missing prefs file) yields the all-enabled defaults. */
        fun remote(): ModulePrefs = try { readRemote() } catch (t: Throwable) {
            SpotifyPlusLog.w("XSharedPreferences read failed, using defaults", t); ModulePrefs()
        }

        private fun readRemote(): ModulePrefs {
            val sp = XSharedPreferences(BuildConfig.APPLICATION_ID, PREFS_NAME)
            sp.reload()
            SpotifyPlusLog.i("prefs file=${sp.file} exists=${sp.file.exists()} readable=${sp.file.canRead()} (missing file => defaults, all on)")
            return ModulePrefs(
                canvasDownload = sp.getBoolean(KEY_CANVAS, true),
                lyricsBridge = sp.getBoolean(KEY_LYRICS, true),
                artistCoverDownload = sp.getBoolean(KEY_ARTIST, true),
                shareCardEnhance = sp.getBoolean(KEY_SHARE, true),
                shareCardAutoSave = sp.getBoolean(KEY_SHARE_AUTOSAVE, false),
                lyricsTranslation = sp.getBoolean(KEY_LYRICS_TRANSLATION, true),
                lyricsLanguage = sp.getString(KEY_LYRICS_LANG, "") ?: "",
                lyricsMarket = sp.getString(KEY_LYRICS_MARKET, "") ?: "",
            )
        }
    }
}
