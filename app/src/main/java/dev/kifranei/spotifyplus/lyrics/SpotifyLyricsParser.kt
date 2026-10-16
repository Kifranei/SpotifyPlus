package dev.kifranei.spotifyplus.lyrics

import dev.kifranei.spotifyplus.proto.ProtoField
import dev.kifranei.spotifyplus.proto.ProtoReader

/**
 * Parsers for Spotify color-lyrics responses (9.1.90.2270 wire schema, recovered statically from
 * the protobuf-lite `newMessageInfo` descriptors — see DESIGN.md §3 for the field table).
 *
 *  v3 `GET color-lyrics/v3/track/{id}` → LyricsWrapperResponse{1:LyricsV3Response, 2:hasVocalRemoval}
 *      LyricsV3Response{1:syncType 2:lines 3:previewLines 4:provider 5:translation 6:language 7:capStatus}
 *      LyricsLine{1:startMs 2:endMs 3:words 4:syncData*{1:startMs 2:endMs 3:numChars}}
 *      Translation{1:Language{1:code 2:isRtl} 2:lines*(string, index-aligned with lines)}
 *  v2 `GET color-lyrics/v2/track/{id}` → ColorLyricsResponse{1:LyricsResponse 2:colors 3:hasVocalRemoval}
 *      LyricsResponse{1:syncType 2:lines 3:provider 9:alternatives* 10:language 17:previewLines 19:translationLanguages*}
 *      LyricsLine{1:startMs 2:words 3:syllables*{1:startMs 2:numChars 3:endMs}}
 *      Alternative{1:language 2:lines*(string) 3:isRtl}
 */
object SpotifyLyricsParser {

    /** Auto-detect: JSON is not supported (protobuf only). Tries v3 first, then v2. */
    fun parseAuto(bytes: ByteArray, preferredTranslation: String? = null): SpLyrics? {
        if (bytes.isEmpty() || bytes[0] == '{'.code.toByte()) return null
        return runCatching { parseV3(bytes) }.getOrNull()?.takeIf { it.lines.isNotEmpty() }
            ?: runCatching { parseV2(bytes, preferredTranslation) }.getOrNull()?.takeIf { it.lines.isNotEmpty() }
    }

    // ---------------------------------------------------------------- v3

    fun parseV3(bytes: ByteArray): SpLyrics {
        val wrapper = ProtoReader.parse(bytes)
        val lyricsBytes = wrapper.firstBytes(1) ?: throw IllegalArgumentException("no lyrics field")
        val root = ProtoReader.parse(lyricsBytes)

        val rawLines = root.filter { it.number == 2 }.mapNotNull { it.bytes }.map(::parseV3Line)
        val provider = root.firstBytes(4)?.let { ProtoReader.parse(it).firstString(3) ?: ProtoReader.parse(it).firstString(1) }
        val language = root.firstBytes(6)?.let { ProtoReader.parse(it).firstString(1) }

        var trLang: String? = null
        var trLines: List<String> = emptyList()
        root.firstBytes(5)?.let { tb ->
            val tf = ProtoReader.parse(tb)
            trLang = tf.firstBytes(1)?.let { ProtoReader.parse(it).firstString(1) }
            trLines = tf.filter { it.number == 2 }.map { it.string() }
        }
        val aligned = trLines.size == rawLines.size
        val lines = finishLines(rawLines.mapIndexed { i, l ->
            l.copy(translation = if (aligned) trLines[i].takeIf { it.isNotBlank() } else null)
        })
        return SpLyrics(
            lines = lines,
            syncType = root.firstVarint(1) ?: 0,
            provider = provider,
            language = language,
            translationLanguage = if (aligned && trLines.any { it.isNotBlank() }) trLang else null,
            source = "v3",
        )
    }

    private fun parseV3Line(b: ByteArray): SpLine {
        val f = ProtoReader.parse(b)
        val begin = f.firstVarintL(1) ?: 0L
        val end = f.firstVarintL(2) ?: 0L
        val text = f.firstString(3) ?: ""
        val sync = f.filter { it.number == 4 }.mapNotNull { it.bytes }.map {
            val s = ProtoReader.parse(it)
            Triple(s.firstVarintL(1) ?: 0L, s.firstVarintL(2) ?: 0L, s.firstVarint(3) ?: 0)
        }
        return SpLine(begin, end, text, sliceWords(text, begin, sync.map { SyncChunk(it.first, it.second, it.third) }))
    }

    // ---------------------------------------------------------------- v2

    fun parseV2(bytes: ByteArray, preferredTranslation: String? = null): SpLyrics {
        val top = ProtoReader.parse(bytes)
        val root = ProtoReader.parse(top.firstBytes(1) ?: throw IllegalArgumentException("no lyrics field"))
        val rawLines = root.filter { it.number == 2 }.mapNotNull { it.bytes }.map { lb ->
            val f = ProtoReader.parse(lb)
            val begin = f.firstVarintL(1) ?: 0L
            val text = f.firstString(2) ?: ""
            val chunks = f.filter { it.number == 3 }.mapNotNull { it.bytes }.map {
                val s = ProtoReader.parse(it)
                SyncChunk(s.firstVarintL(1) ?: 0L, s.firstVarintL(3) ?: 0L, s.firstVarint(2) ?: 0)
            }
            SpLine(begin, 0L, text, sliceWords(text, begin, chunks))
        }
        val language = root.firstString(10)
        // v2 carries translations as `alternatives` (index-aligned string lists).
        data class Alt(val lang: String?, val lines: List<String>)
        val alts = root.filter { it.number == 9 }.mapNotNull { it.bytes }.map {
            val a = ProtoReader.parse(it)
            Alt(a.firstString(1), a.filter { f -> f.number == 2 }.map { f -> f.string() })
        }.filter { it.lines.size == rawLines.size }
        val chosen = alts.firstOrNull { preferredTranslation != null && sameLanguage(it.lang, preferredTranslation) }
            ?: alts.firstOrNull { !sameLanguage(it.lang, language) }
        val lines = finishLines(rawLines.mapIndexed { i, l ->
            l.copy(translation = chosen?.lines?.get(i)?.takeIf { it.isNotBlank() })
        })
        return SpLyrics(
            lines = lines,
            syncType = root.firstVarint(1) ?: 0,
            provider = root.firstString(3),
            language = language,
            translationLanguage = chosen?.lang,
            source = "v2",
        )
    }

    fun sameLanguage(a: String?, b: String?): Boolean {
        if (a == null || b == null) return false
        val x = a.lowercase().replace('_', '-'); val y = b.lowercase().replace('_', '-')
        return x == y || x.substringBefore('-') == y.substringBefore('-') && (!x.contains('-') || !y.contains('-'))
    }

    // ---------------------------------------------------------------- shared

    data class SyncChunk(val start: Long, val end: Long, val chars: Int)

    /**
     * Cuts [text] into words using cumulative `numChars` (UTF-16 units). Returns empty when the counts
     * do not exactly cover the text (caller falls back to line-level). Chunk starts are treated as
     * line-relative when the first one is before the line start.
     */
    fun sliceWords(text: String, lineBegin: Long, chunks: List<SyncChunk>): List<SpWord> {
        if (chunks.isEmpty() || text.isEmpty()) return emptyList()
        val relative = chunks.first().start < lineBegin
        val base = if (relative) lineBegin else 0L
        var off = 0
        val out = ArrayList<SpWord>(chunks.size)
        for ((i, c) in chunks.withIndex()) {
            if (c.chars < 0 || off + c.chars > text.length) return emptyList()
            val s = base + c.start
            val e = if (c.end > 0) base + c.end else chunks.getOrNull(i + 1)?.let { base + it.start } ?: s
            out += SpWord(s, maxOf(e, s), text.substring(off, off + c.chars))
            off += c.chars
        }
        return if (off == text.length) out else emptyList()
    }

    /** Fill missing line ends, drop blank lines (like LyricProvider does). */
    fun finishLines(raw: List<SpLine>): List<SpLine> =
        raw.mapIndexedNotNull { i, l ->
            if (l.text.isBlank()) return@mapIndexedNotNull null
            val end = if (l.endMs > l.beginMs) l.endMs else raw.getOrNull(i + 1)?.beginMs?.takeIf { it > l.beginMs } ?: (l.beginMs + 5000L)
            l.copy(endMs = end)
        }

    private fun List<ProtoField>.firstBytes(n: Int) = firstOrNull { it.number == n }?.bytes
    private fun List<ProtoField>.firstString(n: Int) = firstOrNull { it.number == n && it.bytes != null }?.string()
    private fun List<ProtoField>.firstVarint(n: Int) = firstOrNull { it.number == n && it.wireType == 0 }?.int()
    private fun List<ProtoField>.firstVarintL(n: Int) = firstOrNull { it.number == n && it.wireType == 0 }?.varint
}
