package dev.kifranei.spotifyplus

import dev.kifranei.spotifyplus.lyrics.LyriconMapper
import dev.kifranei.spotifyplus.lyrics.SpotifyLyricsParser
import dev.kifranei.spotifyplus.lyrics.SpotifyLyricsParser.SyncChunk
import dev.kifranei.spotifyplus.net.ArtistMetadata
import dev.kifranei.spotifyplus.net.HeaderStore
import dev.kifranei.spotifyplus.net.SpotifyLyricsClient
import dev.kifranei.spotifyplus.proto.ProtoWriter as W
import org.junit.Assert.*
import org.junit.Test

class LyricsParserTest {
    private fun v3Line(start: Long, end: Long, text: String, sync: List<Triple<Long, Long, Int>> = emptyList()): ByteArray =
        W.int(1, start) + W.int(2, end) + W.string(3, text) +
            sync.fold(ByteArray(0)) { acc, (s, e, n) -> acc + W.bytes(4, W.int(1, s) + W.int(2, e) + W.int(3, n.toLong())) }

    private fun v3(lines: List<ByteArray>, tr: List<String>? = null, trLang: String = "zh-TW"): ByteArray {
        var lyr = W.int(1, 2)
        lines.forEach { lyr += W.bytes(2, it) }
        lyr += W.bytes(4, W.string(1, "MusixMatch") + W.string(3, "Musixmatch"))
        if (tr != null) lyr += W.bytes(5, W.bytes(1, W.string(1, trLang) + W.int(2, 0)) + tr.fold(ByteArray(0)) { a, s -> a + W.string(2, s) })
        lyr += W.bytes(6, W.string(1, "ja"))
        return W.bytes(1, lyr) + W.int(2, 0)
    }

    @Test fun v3WordsAndTranslation() {
        val bytes = v3(
            listOf(
                v3Line(1000, 3000, "你好世界", listOf(Triple(1000, 1500, 2), Triple(1500, 3000, 2))),
                v3Line(3000, 0, "second line"),
            ),
            tr = listOf("Hello world", "第二行"),
        )
        val r = SpotifyLyricsParser.parseV3(bytes)
        assertEquals("v3", r.source); assertEquals("ja", r.language); assertEquals("zh-TW", r.translationLanguage)
        assertEquals(2, r.lines.size)
        assertEquals(listOf("你好", "世界"), r.lines[0].words.map { it.text })
        assertEquals(1500L, r.lines[0].words[1].beginMs)
        assertEquals("Hello world", r.lines[0].translation)
        assertEquals("第二行", r.lines[1].translation)
        assertEquals(8000L, r.lines[1].endMs) // last line: +5000 fallback
        assertTrue(r.hasTranslation && r.hasWordSync)
    }

    @Test fun v3RelativeWordTimes() {
        val r = SpotifyLyricsParser.parseV3(v3(listOf(v3Line(5000, 8000, "abcd", listOf(Triple(0, 1000, 2), Triple(1000, 3000, 2))))))
        assertEquals(5000L, r.lines[0].words[0].beginMs)
        assertEquals(8000L, r.lines[0].words[1].endMs)
    }

    @Test fun v3MisalignedTranslationDropped() {
        val r = SpotifyLyricsParser.parseV3(v3(listOf(v3Line(0, 1000, "a"), v3Line(1000, 2000, "b")), tr = listOf("only one")))
        assertFalse(r.hasTranslation); assertNull(r.translationLanguage)
    }

    @Test fun badSyllableCountsFallBackToLine() {
        val r = SpotifyLyricsParser.parseV3(v3(listOf(v3Line(0, 1000, "abc", listOf(Triple(0, 500, 1), Triple(500, 1000, 1))))))
        assertTrue(r.lines[0].words.isEmpty())
        assertEquals("abc", r.lines[0].text)
    }

    @Test fun blankLinesDroppedButTranslationIndexKept() {
        val r = SpotifyLyricsParser.parseV3(v3(listOf(v3Line(0, 1000, "a"), v3Line(1000, 2000, " "), v3Line(2000, 3000, "c")), tr = listOf("A", "", "C")))
        assertEquals(listOf("a", "c"), r.lines.map { it.text })
        assertEquals(listOf("A", "C"), r.lines.map { it.translation })
    }

    @Test fun v2WithAlternatives() {
        fun line(start: Long, text: String, ch: List<Triple<Long, Int, Long>>) =
            W.int(1, start) + W.string(2, text) + ch.fold(ByteArray(0)) { a, (s, n, e) -> a + W.bytes(3, W.int(1, s) + W.int(2, n.toLong()) + W.int(3, e)) }
        val lyr = W.int(1, 2) +
            W.bytes(2, line(1000, "ab", listOf(Triple(1000L, 1, 1500L), Triple(1500L, 1, 2000L)))) +
            W.bytes(2, line(2000, "cd", emptyList())) +
            W.bytes(9, W.string(1, "zh-TW") + W.string(2, "甲乙") + W.string(2, "丙丁") + W.int(3, 0)) +
            W.string(10, "en")
        val r = SpotifyLyricsParser.parseV2(W.bytes(1, lyr), "zh-TW")
        assertEquals("v2", r.source); assertEquals("zh-TW", r.translationLanguage)
        assertEquals(listOf("a", "b"), r.lines[0].words.map { it.text })
        assertEquals("丙丁", r.lines[1].translation)
    }

    @Test fun autoFallsBackV3ToV2AndRejectsJson() {
        assertNull(SpotifyLyricsParser.parseAuto("{}".toByteArray()))
        assertNull(SpotifyLyricsParser.parseAuto(ByteArray(0)))
        val v3b = v3(listOf(v3Line(0, 1000, "x")))
        assertEquals("v3", SpotifyLyricsParser.parseAuto(v3b)!!.source)
    }

    @Test fun sliceWords() {
        assertTrue(SpotifyLyricsParser.sliceWords("abc", 0, listOf(SyncChunk(0, 1, 2))).isEmpty())
        assertEquals(2, SpotifyLyricsParser.sliceWords("abc", 0, listOf(SyncChunk(0, 1, 1), SyncChunk(1, 2, 2))).size)
        assertTrue(SpotifyLyricsParser.sliceWords("abc", 0, listOf(SyncChunk(0, 1, 5))).isEmpty())
    }

    @Test fun lyriconMapping() {
        val r = SpotifyLyricsParser.parseV3(v3(listOf(v3Line(1000, 3000, "你好世界", listOf(Triple(1000, 1500, 2), Triple(1500, 3000, 2)))), tr = listOf("Hello world")))
        val song = LyriconMapper.toSong("id1", "T", "A", 200000, r)
        assertEquals("id1", song.id); assertEquals(200000L, song.duration)
        val l = song.lyrics!![0]
        assertEquals("Hello world", l.translation)
        assertEquals(2, l.words!!.size)
        assertEquals("世界", l.words!![1].text)
        assertEquals(1500L, l.words!![1].begin)
        assertEquals(2000L, l.duration)
        assertEquals(Long.MAX_VALUE, LyriconMapper.toSong("i", null, null, 0, r).duration)
    }

    @Test fun urlBuilding() {
        val c = SpotifyLyricsClient(headers = { emptyMap() })
        val u = c.buildUrl(3, "TID", "zh-TW", "TW")
        assertTrue(u.startsWith("https://spclient.wg.spotify.com/color-lyrics/v3/track/TID?"))
        assertTrue(u.contains("clientLanguage=zh-TW")); assertTrue(u.contains("market=TW"))
        assertFalse(c.buildUrl(2, "T", "en", "").contains("market"))
    }

    @Test fun headerStoreOnlyKeepsAuthenticatedRequestHeaders() {
        HeaderStore.clear()
        HeaderStore.record(arrayOf("Content-Type", "x", "Server", "y"))
        assertFalse(HeaderStore.hasAuth())
        HeaderStore.record(arrayOf("Authorization", "Bearer abc", "Client-Token", "ct", "Foo", "bar"))
        assertTrue(HeaderStore.hasAuth())
        assertEquals("ct", HeaderStore.snapshot()["client-token"]); assertNull(HeaderStore.snapshot()["foo"])
    }

    @Test fun artistPortraits() {
        fun img(hex: ByteArray, w: Int, h: Int) = W.bytes(1, hex) + W.int(2, 2) + W.int(3, w.toLong()) + W.int(4, h.toLong())
        val a = ByteArray(20) { 0xab.toByte() }; val b = ByteArray(20) { 0x01 }
        val msg = W.string(2, "Artist") + W.bytes(11, img(a, 320, 320)) + W.bytes(17, W.bytes(1, img(b, 640, 640)))
        val l = ArtistMetadata.parsePortraits(msg)
        assertEquals(2, l.size)
        assertEquals("https://i.scdn.co/image/" + "01".repeat(20), ArtistMetadata.largest(l)!!.url)
    }
}
