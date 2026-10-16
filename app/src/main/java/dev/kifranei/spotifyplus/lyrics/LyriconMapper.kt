package dev.kifranei.spotifyplus.lyrics

import io.github.proify.lyricon.lyric.model.LyricWord
import io.github.proify.lyricon.lyric.model.RichLyricLine
import io.github.proify.lyricon.lyric.model.Song

/**
 * Maps [SpLyrics] → Lyricon (词幕) [Song]. Same shape as LyricProvider's `AppleSongMapper`:
 * `words` = per-word original, `translation` = per-line translation text.
 */
object LyriconMapper {
    fun toSong(id: String, name: String?, artist: String?, durationMs: Long, lyrics: SpLyrics): Song =
        Song(
            id = id,
            name = name,
            artist = artist,
            duration = if (durationMs <= 0L) Long.MAX_VALUE else durationMs,
            lyrics = lyrics.lines.map(::toLine).toMutableList(),
        )

    fun toLine(l: SpLine): RichLyricLine = RichLyricLine().apply {
        begin = l.beginMs
        end = l.endMs
        duration = (l.endMs - l.beginMs).coerceAtLeast(0L)
        text = l.text
        words = l.words.map {
            LyricWord(begin = it.beginMs, end = it.endMs, duration = (it.endMs - it.beginMs).coerceAtLeast(0L), text = it.text)
        }.toMutableList()
        translation = l.translation
    }

    /** Placeholder while lyrics are loading / unavailable (title+artist only). */
    fun placeholder(title: String?, artist: String?) = Song(name = title, artist = artist)
}
