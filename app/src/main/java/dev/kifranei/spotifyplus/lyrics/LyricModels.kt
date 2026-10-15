package dev.kifranei.spotifyplus.lyrics

/** One syllable/word with absolute timing (ms). */
data class SpWord(val beginMs: Long, val endMs: Long, val text: String)

/**
 * One lyric line. [translation] is filled from the color-lyrics v3 `translation` block when the
 * server returned one for the requested client language.
 */
data class SpLine(
    val beginMs: Long,
    val endMs: Long,
    val text: String,
    val words: List<SpWord> = emptyList(),
    val translation: String? = null,
)

data class SpLyrics(
    val lines: List<SpLine>,
    val syncType: Int = 0,
    val provider: String? = null,
    val language: String? = null,
    val translationLanguage: String? = null,
    val source: String = "v3",
) {
    val hasTranslation: Boolean get() = lines.any { !it.translation.isNullOrBlank() }
    val hasWordSync: Boolean get() = lines.any { it.words.isNotEmpty() }
}
