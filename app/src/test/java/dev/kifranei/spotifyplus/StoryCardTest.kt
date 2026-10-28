package dev.kifranei.spotifyplus

import dev.kifranei.spotifyplus.hook.StoryCard
import org.junit.Assert.*
import org.junit.Test

/** Fake host classes with the 9.1.98 shapes (p.l271 / p.t7c1 / p.q7c1 / p.nr7 / p.kmt / p.lmt / p.mmt / p.cz70). */
@Suppress("unused")
class StoryCardTest {
    abstract class Data
    abstract class StoryData : Data()
    abstract class BgBase
    class Grad(private val d: Int, private val e: Int) : BgBase()
    class Img(private val d: String) : BgBase()
    class Vid(private val d: String, private val e: Boolean) : BgBase()
    abstract class Other
    class Opts(private val a: String?, private val b: Boolean, private val c: Boolean) : Other()

    class GradientStory(val a: String, val b: Grad, val c: Img?, val d: String?, val e: Map<String, String>, val f: Any?, val g: Opts) : StoryData()
    class ImageStory(val a: String, val b: Img, val c: Img?, val d: String?, val e: Map<String, String>, val f: Any?, val g: Opts) : StoryData()
    class VideoStory(val a: String, val b: Vid, val c: Img?, val d: String?, val e: Map<String, String>, val f: Any?, val g: Opts) : StoryData()
    class ImageData(val a: String?, val b: String, val c: String?, val d: Any?, val e: Map<String, String>, val f: String?, val g: String?) : Data()

    @Test fun sniff() {
        assertEquals("jpg", StoryCard.sniffExt(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())))
        assertEquals("png", StoryCard.sniffExt(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D)))
        assertEquals("webp", StoryCard.sniffExt("RIFF\u0000\u0000\u0000\u0000WEBPVP8 ".toByteArray()))
        assertEquals("mp4", StoryCard.sniffExt("\u0000\u0000\u0000\u0018ftypmp42".toByteArray()))
        assertNull(StoryCard.sniffExt(ByteArray(3)))
        assertTrue(StoryCard.isRemote("https://i.scdn.co/x")); assertFalse(StoryCard.isRemote("content://a/b")); assertFalse(StoryCard.isRemote(null))
    }

    private val opts = Opts(null, false, false)

    @Test fun gradientStoryKeepsColoursAndSticker() {
        val s = StoryCard.parse(GradientStory("spotify:track:x", Grad(0xFF1E3264.toInt(), 0xFF0D1730.toInt()), Img("content://a/sticker.png"), null, emptyMap(), null, opts))!!
        assertEquals(StoryCard.Bg.Gradient(0xFF1E3264.toInt(), 0xFF0D1730.toInt()), s.bg)
        assertEquals("content://a/sticker.png", s.sticker)
    }

    @Test fun imageStoryBackgroundIsFirstField() {
        val s = StoryCard.parse(ImageStory("u", Img("/data/bg.jpg"), Img("/data/st.png"), null, emptyMap(), null, opts))!!
        assertEquals(StoryCard.Bg.Image("/data/bg.jpg"), s.bg)
        assertEquals("/data/st.png", s.sticker)
        val noSticker = StoryCard.parse(ImageStory("u", Img("/data/bg.jpg"), null, null, emptyMap(), null, opts))!!
        assertNull(noSticker.sticker)
        assertEquals(StoryCard.Bg.Image("/data/bg.jpg"), noSticker.bg)
    }

    @Test fun videoStory() {
        val s = StoryCard.parse(VideoStory("u", Vid("file:///v.mp4", true), Img("/st.png"), null, emptyMap(), null, opts))!!
        assertEquals(StoryCard.Bg.Video("file:///v.mp4"), s.bg)
        assertEquals("/st.png", s.sticker)
    }

    @Test fun nonStoryDataIsIgnored() {
        assertNull(StoryCard.parse(ImageData(null, "u", null, null, emptyMap(), null, null)))
        assertNull(StoryCard.parse(null))
        assertNull(StoryCard.parse("x"))
    }

    @Test fun keyChangesWithColour() {
        val a = StoryCard.parse(GradientStory("u", Grad(1, 2), Img("/s.png"), null, emptyMap(), null, opts))!!
        val b = StoryCard.parse(GradientStory("u", Grad(3, 2), Img("/s.png"), null, emptyMap(), null, opts))!!
        assertNotEquals(a.key, b.key)
    }

    @Test fun geometry() {
        assertEquals(1080, StoryCard.CANVAS_W); assertEquals(1920, StoryCard.CANVAS_H)
        // 9.1.98 preview: 504 px sticker in a 567 px frame → exported at the same proportion (≈ 960 px of 1080)
        val p = StoryCard.stickerRect(1080, 1920, 504, 678)
        assertEquals(504f / 567f, (p[2] - p[0]) / 1080f, 0.002f)
        assertEquals(678f / 504f, (p[3] - p[1]) / (p[2] - p[0]), 0.001f)       // aspect kept
        assertEquals(1080f, p[0] + p[2], 0.01f); assertEquals(1920f, p[1] + p[3], 0.01f)  // centred
        // very tall sticker is capped by height instead
        val t = StoryCard.stickerRect(1080, 1920, 500, 4000)
        assertEquals(1920f * StoryCard.STICKER_FRAC, t[3] - t[1], 0.01f)
        assertEquals(1, StoryCard.sampleSize(1080, 1920, 1080, 1920))
        assertEquals(2, StoryCard.sampleSize(2400, 4000, 1080, 1920))
        assertEquals(4, StoryCard.sampleSize(4400, 8000, 1080, 1920))
        // centre-crop
        assertArrayEquals(intArrayOf(0, 0, 900, 1600), StoryCard.centerCrop(900, 1600, 1080, 1920))
        assertArrayEquals(intArrayOf(219, 0, 781, 1000), StoryCard.centerCrop(1000, 1000, 1080, 1920))
        assertArrayEquals(intArrayOf(0, 500, 1080, 2420), StoryCard.centerCrop(1080, 2920, 1080, 1920))
    }

    /** Coil 3 DiskLruCache (sha256(url).1) and OkHttp/Picasso cache (md5(url).1) body-file names. */
    @Test fun hostCacheNames() {
        val n = StoryCard.cacheNames("https://sharing-assets.spotifycdn.com/IA/themed_scribbles.png")
        assertEquals(listOf(
            "e661c04fdfee2ffd0cb652a64fb33096549f2d99b52bff9cbf2e3a761ea5483e.1",
            "b7d5d0a7c4eac1eaeb5eb4cc4a90e21d.1",
            "e661c04fdfee2ffd0cb652a64fb33096549f2d99b52bff9cbf2e3a761ea5483e",
            "b7d5d0a7c4eac1eaeb5eb4cc4a90e21d"), n)
    }
}
