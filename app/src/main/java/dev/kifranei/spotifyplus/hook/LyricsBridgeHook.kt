package dev.kifranei.spotifyplus.hook

import android.app.Application
import android.media.session.PlaybackState
import dev.kifranei.spotifyplus.BuildConfig
import dev.kifranei.spotifyplus.lyrics.LyriconMapper
import dev.kifranei.spotifyplus.lyrics.SpLyrics
import dev.kifranei.spotifyplus.net.HeaderStore
import dev.kifranei.spotifyplus.net.SpotifyLyricsClient
import dev.kifranei.spotifyplus.util.ModulePrefs
import dev.kifranei.spotifyplus.util.SpotifyPlusLog
import io.github.proify.lyricon.provider.LyriconFactory
import io.github.proify.lyricon.provider.LyriconProvider
import io.github.proify.lyricon.provider.ProviderLogo
import java.util.Locale

/**
 * LyricProvider-compatible bridge to 词幕 (Lyricon): registers a provider (`$syllable`,`$translation` tags in the
 * manifest) and pushes `Song(lyrics=[RichLyricLine(words=…, translation=…)])` exactly like LyricProvider's
 * Apple Music mapper does. Lyrics come from color-lyrics v3 (translation block) with v2 fallback.
 *
 * Do not enable together with the official LyricProvider Spotify module (two providers for one player).
 */
object LyricsBridgeHook : PlaybackHook.Listener {
    private const val ICON_SVG =
        "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 24 24\"><path fill=\"#1DB954\" d=\"M12 2a10 10 0 100 20 10 10 0 000-20zm4.6 14.4a.62.62 0 01-.86.2c-2.35-1.44-5.3-1.76-8.8-.96a.62.62 0 11-.28-1.2c3.82-.87 7.1-.5 9.74 1.1.3.18.4.57.2.86zm1.22-2.72a.78.78 0 01-1.07.26c-2.7-1.66-6.8-2.14-9.98-1.17a.78.78 0 11-.46-1.5c3.64-1.1 8.16-.57 11.26 1.34.37.22.48.7.25 1.07zm.1-2.83C14.7 8.94 9.4 8.76 6.33 9.7a.94.94 0 11-.55-1.8c3.53-1.07 9.4-.86 13.1 1.34a.94.94 0 01-.96 1.62z\"/></svg>"

    private var prefs = ModulePrefs()
    private var provider: LyriconProvider? = null
    private val client = SpotifyLyricsClient()
    @Volatile private var currentId: String? = null
    private val cache = object : LinkedHashMap<String, SpLyrics>(32, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, SpLyrics>?) = size > 40
    }

    fun install(p: ModulePrefs) {
        prefs = p
        PlaybackHook.install()
        PlaybackHook.add(this)
        SpotifyHost.whenReady { initProvider(it) }
    }

    private fun initProvider(app: Application) {
        try {
            provider = LyriconFactory.createProvider(
                context = app,
                providerPackageName = BuildConfig.APPLICATION_ID,
                playerPackageName = SpotifyHost.PACKAGE,
                logo = ProviderLogo.fromSvg(ICON_SVG),
            ).also { it.register() }
            provider?.player?.setDisplayTranslation(prefs.lyricsTranslation)
            SpotifyPlusLog.i("Lyricon provider registered")
            PlaybackHook.lastTrack?.let { onTrack(it) }
        } catch (t: Throwable) {
            SpotifyPlusLog.e("Lyricon provider init failed", t)
        }
    }

    override fun onPlaybackState(s: PlaybackState) {
        provider?.player?.setPlaybackState(s)
    }

    override fun onOtherMetadata(title: String?, artist: String?) {
        if (!title.isNullOrBlank() || !artist.isNullOrBlank()) provider?.player?.setSong(LyriconMapper.placeholder(title, artist))
    }

    override fun onTrack(t: PlaybackHook.Track) {
        currentId = t.id
        val pl = provider ?: return
        val cached = synchronized(cache) { cache[t.id] }
        if (cached != null) { push(t, cached); return }
        pl.player.setSong(LyriconMapper.placeholder(t.title, t.artist))
        SpotifyHost.io.execute { fetchAndPush(t) }
    }

    private fun language(): String =
        prefs.lyricsLanguage.ifBlank { Locale.getDefault().toLanguageTag() }

    private fun fetchAndPush(t: PlaybackHook.Track) {
        // The host's auth headers appear after its first spclient call; wait a little for them.
        var waited = 0
        while (!HeaderStore.hasAuth() && waited < 12) { Thread.sleep(1000); waited++; if (currentId != t.id) return }
        if (!HeaderStore.hasAuth()) { SpotifyPlusLog.w("no captured auth headers; cannot fetch lyrics"); return }
        try {
            val lyr = client.fetch(t.id, language(), prefs.lyricsMarket)
            SpotifyPlusLog.i("lyrics ${t.id}: src=${lyr.source} lines=${lyr.lines.size} words=${lyr.hasWordSync} tr=${lyr.translationLanguage}")
            synchronized(cache) { cache[t.id] = lyr }
            if (currentId == t.id) push(t, lyr)
        } catch (e: SpotifyLyricsClient.NotFound) {
            SpotifyPlusLog.d("no lyrics for ${t.id}")
        } catch (e: Exception) {
            SpotifyPlusLog.w("lyrics fetch failed for ${t.id}", e)
        }
    }

    private fun push(t: PlaybackHook.Track, lyr: SpLyrics) {
        val player = provider?.player ?: return
        val shown = if (prefs.lyricsTranslation) lyr else lyr.copy(lines = lyr.lines.map { it.copy(translation = null) })
        player.setSong(LyriconMapper.toSong(t.id, t.title, t.artist, t.durationMs, shown))
        player.setDisplayTranslation(prefs.lyricsTranslation && lyr.hasTranslation)
    }
}
