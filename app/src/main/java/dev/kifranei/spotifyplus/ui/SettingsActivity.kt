package dev.kifranei.spotifyplus.ui

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.kifranei.spotifyplus.R
import dev.kifranei.spotifyplus.util.ModulePrefs
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/** Module settings — Miuix 0.9.4 Compose (HyperOS / MIUI style), Chinese-first. */
class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = openPrefs()
        setContent {
            val dark = isSystemInDarkTheme()
            val controller = ThemeController(if (dark) ColorSchemeMode.Dark else ColorSchemeMode.Light)
            MiuixTheme(controller = controller) { SettingsScreen(prefs) }
        }
    }

    private fun openPrefs(): SharedPreferences = try {
        @Suppress("DEPRECATION")
        getSharedPreferences(ModulePrefs.PREFS_NAME, Context.MODE_WORLD_READABLE) // LSPosed new-XSharedPreferences
    } catch (_: SecurityException) {
        getSharedPreferences(ModulePrefs.PREFS_NAME, Context.MODE_PRIVATE)
    }
}

private val LANG_OPTIONS = listOf("" to "跟随系统", "zh-Hans" to "简体中文 (zh-Hans)", "zh-TW" to "繁體中文 (zh-TW)", "en" to "English")
private val MARKET_OPTIONS = listOf("" to "不指定", "TW" to "台湾 (TW)", "CN" to "中国大陆 (CN)", "HK" to "香港 (HK)", "US" to "美国 (US)")

@Composable
private fun SettingsScreen(prefs: SharedPreferences) {
    fun bool(key: String, def: Boolean) = mutableStateOf(prefs.getBoolean(key, def))
    var canvas by remember { bool(ModulePrefs.KEY_CANVAS, true) }
    var lyrics by remember { bool(ModulePrefs.KEY_LYRICS, true) }
    var translation by remember { bool(ModulePrefs.KEY_LYRICS_TRANSLATION, true) }
    var artist by remember { bool(ModulePrefs.KEY_ARTIST, true) }
    var share by remember { bool(ModulePrefs.KEY_SHARE, true) }
    var autoSave by remember { bool(ModulePrefs.KEY_SHARE_AUTOSAVE, false) }
    var lang by remember { mutableStateOf(prefs.getString(ModulePrefs.KEY_LYRICS_LANG, "") ?: "") }
    var market by remember { mutableStateOf(prefs.getString(ModulePrefs.KEY_LYRICS_MARKET, "") ?: "") }

    fun put(key: String, v: Boolean) = prefs.edit().putBoolean(key, v).apply()
    fun put(key: String, v: String) = prefs.edit().putString(key, v).apply()
    fun next(opts: List<Pair<String, String>>, cur: String) = opts[(opts.indexOfFirst { it.first == cur } + 1) % opts.size].first

    Scaffold(topBar = { SmallTopAppBar(title = stringResource(R.string.settings_title)) }) { padding ->
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(padding)) {
            SmallTitle(stringResource(R.string.section_canvas))
            Card(Modifier.padding(horizontal = 12.dp).padding(bottom = 6.dp)) {
                SwitchPreference(
                    title = stringResource(R.string.pref_canvas_title), summary = stringResource(R.string.pref_canvas_summary),
                    checked = canvas, onCheckedChange = { canvas = it; put(ModulePrefs.KEY_CANVAS, it) },
                )
            }
            SmallTitle(stringResource(R.string.section_lyrics))
            Card(Modifier.padding(horizontal = 12.dp).padding(bottom = 6.dp)) {
                SwitchPreference(
                    title = stringResource(R.string.pref_lyrics_title), summary = stringResource(R.string.pref_lyrics_summary),
                    checked = lyrics, onCheckedChange = { lyrics = it; put(ModulePrefs.KEY_LYRICS, it) },
                )
                SwitchPreference(
                    title = stringResource(R.string.pref_translation_title), summary = stringResource(R.string.pref_translation_summary),
                    checked = translation, onCheckedChange = { translation = it; put(ModulePrefs.KEY_LYRICS_TRANSLATION, it) },
                )
                ArrowPreference(
                    title = stringResource(R.string.pref_lang_title),
                    summary = LANG_OPTIONS.firstOrNull { it.first == lang }?.second ?: lang,
                    onClick = { lang = next(LANG_OPTIONS, lang); put(ModulePrefs.KEY_LYRICS_LANG, lang) },
                )
                ArrowPreference(
                    title = stringResource(R.string.pref_market_title),
                    summary = MARKET_OPTIONS.firstOrNull { it.first == market }?.second ?: market,
                    onClick = { market = next(MARKET_OPTIONS, market); put(ModulePrefs.KEY_LYRICS_MARKET, market) },
                )
            }
            SmallTitle(stringResource(R.string.section_artist))
            Card(Modifier.padding(horizontal = 12.dp).padding(bottom = 6.dp)) {
                SwitchPreference(
                    title = stringResource(R.string.pref_artist_title), summary = stringResource(R.string.pref_artist_summary),
                    checked = artist, onCheckedChange = { artist = it; put(ModulePrefs.KEY_ARTIST, it) },
                )
            }
            SmallTitle(stringResource(R.string.section_share))
            Card(Modifier.padding(horizontal = 12.dp).padding(bottom = 6.dp)) {
                SwitchPreference(
                    title = stringResource(R.string.pref_share_title), summary = stringResource(R.string.pref_share_summary),
                    checked = share, onCheckedChange = { share = it; put(ModulePrefs.KEY_SHARE, it) },
                )
                SwitchPreference(
                    title = stringResource(R.string.pref_autosave_title), summary = stringResource(R.string.pref_autosave_summary),
                    checked = autoSave, onCheckedChange = { autoSave = it; put(ModulePrefs.KEY_SHARE_AUTOSAVE, it) },
                )
            }
            SmallTitle("诊断")
            Card(Modifier.padding(horizontal = 12.dp).padding(bottom = 6.dp)) {
                val sp = LocalContext.current.getSharedPreferences(StatusReceiver.PREFS, Context.MODE_PRIVATE)
                val t = sp.getLong("time", 0L)
                val summary = if (t == 0L) "尚未收到 Spotify 的状态。请在 LSPosed 勾选 com.spotify.music，强行停止并重新打开 Spotify，再回到此页。"
                else "最后更新 " + java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(t)) +
                    "\n" + (sp.getString("status", "") ?: "").lines().takeLast(14).joinToString("\n") { it.substringAfter(" ").take(160) }
                Text(summary, modifier = Modifier.padding(16.dp))
                Text("完整日志: Android/data/com.spotify.music/files/SpotifyPlus-log.txt", modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 12.dp))
            }
            SmallTitle(stringResource(R.string.section_about))
            Card(Modifier.padding(horizontal = 12.dp).padding(bottom = 24.dp)) {
                Text(text = stringResource(R.string.pref_about_summary), modifier = Modifier.padding(16.dp))
            }
        }
    }
}
