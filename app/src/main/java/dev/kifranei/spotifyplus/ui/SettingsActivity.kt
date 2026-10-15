package dev.kifranei.spotifyplus.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.content.SharedPreferences
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.kifranei.spotifyplus.BuildConfig
import dev.kifranei.spotifyplus.R
import dev.kifranei.spotifyplus.util.ModulePrefs
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.preference.WindowSpinnerPreference
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController
import java.util.concurrent.atomic.AtomicReference

/** Module settings — Miuix 0.9.4 Compose (HyperOS / MIUI style), Chinese-first. */
class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PrefsBridge.ensure(this)
        val prefs = openPrefs()
        setContent {
            val dark = isSystemInDarkTheme()
            val controller = ThemeController(if (dark) ColorSchemeMode.Dark else ColorSchemeMode.Light)
            MiuixTheme(controller = controller) { SettingsScreen(prefs) }
        }
    }

    private fun openPrefs(): SharedPreferences = try {
        @Suppress("DEPRECATION")
        getSharedPreferences(ModulePrefs.PREFS_NAME, Context.MODE_WORLD_READABLE) // LSPosed new-XSharedPreferences fallback
    } catch (_: SecurityException) {
        getSharedPreferences(ModulePrefs.PREFS_NAME, Context.MODE_PRIVATE)
    }
}

/** Dual-write: local SharedPreferences + LSPosed remote preferences when the service is up. */
internal object PrefsBridge {
    private val remote = AtomicReference<SharedPreferences?>(null)
    @Volatile private var registered = false

    fun ensure(ctx: Context) {
        if (registered) return
        registered = true
        runCatching {
            XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
                override fun onServiceBind(service: XposedService) {
                    runCatching {
                        remote.set(service.getRemotePreferences(ModulePrefs.PREFS_NAME))
                        // Seed remote from local once.
                        val local = try {
                            @Suppress("DEPRECATION")
                            ctx.applicationContext.getSharedPreferences(
                                ModulePrefs.PREFS_NAME,
                                Context.MODE_WORLD_READABLE,
                            )
                        } catch (_: SecurityException) {
                            ctx.applicationContext.getSharedPreferences(
                                ModulePrefs.PREFS_NAME,
                                Context.MODE_PRIVATE,
                            )
                        }
                        syncLocalToRemote(local)
                    }
                }

                override fun onServiceDied(service: XposedService) {
                    remote.set(null)
                }
            })
        }
    }

    fun putBoolean(local: SharedPreferences, key: String, value: Boolean) {
        local.edit().putBoolean(key, value).apply()
        remote.get()?.edit()?.putBoolean(key, value)?.apply()
    }

    fun putString(local: SharedPreferences, key: String, value: String) {
        local.edit().putString(key, value).apply()
        remote.get()?.edit()?.putString(key, value)?.apply()
    }

    private fun syncLocalToRemote(local: SharedPreferences) {
        val r = remote.get() ?: return
        val e = r.edit()
        e.putBoolean(ModulePrefs.KEY_CANVAS, local.getBoolean(ModulePrefs.KEY_CANVAS, true))
        e.putBoolean(ModulePrefs.KEY_LYRICS, local.getBoolean(ModulePrefs.KEY_LYRICS, true))
        e.putBoolean(ModulePrefs.KEY_ARTIST, local.getBoolean(ModulePrefs.KEY_ARTIST, true))
        e.putBoolean(ModulePrefs.KEY_SHARE, local.getBoolean(ModulePrefs.KEY_SHARE, true))
        e.putBoolean(ModulePrefs.KEY_SHARE_AUTOSAVE, local.getBoolean(ModulePrefs.KEY_SHARE_AUTOSAVE, false))
        e.putBoolean(ModulePrefs.KEY_LYRICS_TRANSLATION, local.getBoolean(ModulePrefs.KEY_LYRICS_TRANSLATION, true))
        e.putBoolean(ModulePrefs.KEY_MEDIA_NOTIF, local.getBoolean(ModulePrefs.KEY_MEDIA_NOTIF, true))
        e.putString(ModulePrefs.KEY_LYRICS_LANG, local.getString(ModulePrefs.KEY_LYRICS_LANG, "") ?: "")
        e.putString(ModulePrefs.KEY_LYRICS_MARKET, local.getString(ModulePrefs.KEY_LYRICS_MARKET, "") ?: "")
        e.apply()
    }
}

private val LANG_OPTIONS = listOf(
    "" to "跟随系统",
    "zh-Hans" to "简体中文",
    "zh-TW" to "繁體中文",
    "en" to "English",
    "ja" to "日本語",
    "ko" to "한국어",
)
private val MARKET_OPTIONS = listOf(
    "" to "不指定",
    "TW" to "台湾",
    "US" to "美国",
    "JP" to "日本",
    "HK" to "香港",
    "CN" to "中国大陆",
)

@Composable
private fun SettingsScreen(prefs: SharedPreferences) {
    fun bool(key: String, def: Boolean) = mutableStateOf(prefs.getBoolean(key, def))
    var canvas by remember { bool(ModulePrefs.KEY_CANVAS, true) }
    var lyrics by remember { bool(ModulePrefs.KEY_LYRICS, true) }
    var translation by remember { bool(ModulePrefs.KEY_LYRICS_TRANSLATION, true) }
    var artist by remember { bool(ModulePrefs.KEY_ARTIST, true) }
    var share by remember { bool(ModulePrefs.KEY_SHARE, true) }
    var autoSave by remember { bool(ModulePrefs.KEY_SHARE_AUTOSAVE, false) }
    var mediaNotif by remember { bool(ModulePrefs.KEY_MEDIA_NOTIF, true) }
    var lang by remember { mutableStateOf(prefs.getString(ModulePrefs.KEY_LYRICS_LANG, "") ?: "") }
    var market by remember { mutableStateOf(prefs.getString(ModulePrefs.KEY_LYRICS_MARKET, "") ?: "") }

    fun put(key: String, v: Boolean) = PrefsBridge.putBoolean(prefs, key, v)
    fun put(key: String, v: String) = PrefsBridge.putString(prefs, key, v)

    val langIndex = LANG_OPTIONS.indexOfFirst { it.first == lang }.coerceAtLeast(0)
    val marketIndex = MARKET_OPTIONS.indexOfFirst { it.first == market }.coerceAtLeast(0)
    val langItems = remember { LANG_OPTIONS.map { DropdownItem(title = it.second) } }
    val marketItems = remember { MARKET_OPTIONS.map { DropdownItem(title = it.second) } }

    Scaffold(topBar = { SmallTopAppBar(title = stringResource(R.string.settings_title)) }) { padding ->
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(padding)) {
            SmallTitle(stringResource(R.string.section_canvas))
            Card(Modifier.padding(horizontal = 12.dp).padding(bottom = 6.dp)) {
                SwitchPreference(
                    title = stringResource(R.string.pref_canvas_title),
                    summary = stringResource(R.string.pref_canvas_summary),
                    checked = canvas,
                    onCheckedChange = { canvas = it; put(ModulePrefs.KEY_CANVAS, it) },
                )
            }
            SmallTitle(stringResource(R.string.section_lyrics))
            Card(Modifier.padding(horizontal = 12.dp).padding(bottom = 6.dp)) {
                SwitchPreference(
                    title = stringResource(R.string.pref_lyrics_title),
                    summary = stringResource(R.string.pref_lyrics_summary),
                    checked = lyrics,
                    onCheckedChange = { lyrics = it; put(ModulePrefs.KEY_LYRICS, it) },
                )
                SwitchPreference(
                    title = stringResource(R.string.pref_translation_title),
                    summary = stringResource(R.string.pref_translation_summary),
                    checked = translation,
                    onCheckedChange = { translation = it; put(ModulePrefs.KEY_LYRICS_TRANSLATION, it) },
                )
                WindowSpinnerPreference(
                    title = stringResource(R.string.pref_lang_title),
                    summary = LANG_OPTIONS[langIndex].second,
                    items = langItems,
                    selectedIndex = langIndex,
                    onSelectedIndexChange = {
                        lang = LANG_OPTIONS[it].first
                        put(ModulePrefs.KEY_LYRICS_LANG, lang)
                    },
                )
                WindowSpinnerPreference(
                    title = stringResource(R.string.pref_market_title),
                    summary = MARKET_OPTIONS[marketIndex].second,
                    items = marketItems,
                    selectedIndex = marketIndex,
                    onSelectedIndexChange = {
                        market = MARKET_OPTIONS[it].first
                        put(ModulePrefs.KEY_LYRICS_MARKET, market)
                    },
                )
            }
            SmallTitle(stringResource(R.string.section_artist))
            Card(Modifier.padding(horizontal = 12.dp).padding(bottom = 6.dp)) {
                SwitchPreference(
                    title = stringResource(R.string.pref_artist_title),
                    summary = stringResource(R.string.pref_artist_summary),
                    checked = artist,
                    onCheckedChange = { artist = it; put(ModulePrefs.KEY_ARTIST, it) },
                )
            }
            SmallTitle(stringResource(R.string.section_share))
            Card(Modifier.padding(horizontal = 12.dp).padding(bottom = 6.dp)) {
                SwitchPreference(
                    title = stringResource(R.string.pref_share_title),
                    summary = stringResource(R.string.pref_share_summary),
                    checked = share,
                    onCheckedChange = { share = it; put(ModulePrefs.KEY_SHARE, it) },
                )
                SwitchPreference(
                    title = stringResource(R.string.pref_autosave_title),
                    summary = stringResource(R.string.pref_autosave_summary),
                    checked = autoSave,
                    onCheckedChange = { autoSave = it; put(ModulePrefs.KEY_SHARE_AUTOSAVE, it) },
                )
            }
            SmallTitle(stringResource(R.string.section_notif))
            Card(Modifier.padding(horizontal = 12.dp).padding(bottom = 6.dp)) {
                SwitchPreference(
                    title = stringResource(R.string.pref_media_notif_title),
                    summary = stringResource(R.string.pref_media_notif_summary),
                    checked = mediaNotif,
                    onCheckedChange = { mediaNotif = it; put(ModulePrefs.KEY_MEDIA_NOTIF, it) },
                )
            }
            SmallTitle("诊断")
            Card(Modifier.padding(horizontal = 12.dp).padding(bottom = 6.dp)) {
                val sp = LocalContext.current.getSharedPreferences(StatusReceiver.PREFS, Context.MODE_PRIVATE)
                val t = sp.getLong("time", 0L)
                val summary = if (t == 0L) {
                    "尚未收到 Spotify 的状态。请在 LSPosed 勾选 com.spotify.music，强行停止并重新打开 Spotify，再回到此页。"
                } else {
                    "最后更新 " + java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.getDefault())
                        .format(java.util.Date(t)) + "\n" +
                        (sp.getString("status", "") ?: "").lines().takeLast(14)
                            .joinToString("\n") { it.substringAfter(" ").take(160) }
                }
                Text(summary, modifier = Modifier.padding(16.dp))
                Text(
                    "完整日志: Android/data/com.spotify.music/files/SpotifyPlus-log.txt",
                    modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 12.dp),
                )
            }
            SmallTitle(stringResource(R.string.section_about))
            Card(Modifier.padding(horizontal = 12.dp).padding(bottom = 24.dp)) {
                // Same BasicComponent row style/height as SwitchPreference — no DESIGN dump.
                BasicComponent(
                    title = stringResource(R.string.pref_about_author_title),
                    summary = stringResource(R.string.pref_about_author_summary),
                )
                val ctx = LocalContext.current
                BasicComponent(
                    title = stringResource(R.string.pref_about_opensource_title),
                    summary = stringResource(R.string.pref_about_opensource_summary),
                    onClick = {
                        runCatching {
                            ctx.startActivity(
                                Intent(
                                    Intent.ACTION_VIEW,
                                    Uri.parse("https://github.com/compose-miuix-ui/miuix"),
                                ),
                            )
                        }
                    },
                )
                BasicComponent(
                    title = stringResource(R.string.pref_about_version_title),
                    summary = BuildConfig.VERSION_NAME,
                )
                BasicComponent(
                    title = stringResource(R.string.pref_about_module_title),
                    summary = stringResource(R.string.pref_about_module_summary),
                )
            }
        }
    }
}

