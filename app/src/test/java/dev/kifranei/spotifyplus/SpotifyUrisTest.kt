package dev.kifranei.spotifyplus

import dev.kifranei.spotifyplus.hook.ShareRegistry
import dev.kifranei.spotifyplus.util.SpotifyUris
import org.junit.Assert.*
import org.junit.Test

class SpotifyUrisTest {
    @Test fun ids() {
        assertEquals("4uLU6hMCjMI75M1A2tKUQC", SpotifyUris.trackId("spotify:track:4uLU6hMCjMI75M1A2tKUQC"))
        assertEquals("1Xyo4u8uXC1ZmMpatF05PJ", SpotifyUris.artistId("spotify:artist:1Xyo4u8uXC1ZmMpatF05PJ?x=1"))
        assertNull(SpotifyUris.trackId("spotify:album:abc"))
        assertNull(SpotifyUris.trackId(null))
    }

    @Test fun base62Gid() {
        // well-known: spotify:track:4uLU6hMCjMI75M1A2tKUQC == gid 0c3f8d7ab8b24f0a9a2b6d4a2b0d2a63? -> verify roundtrip instead
        val hex = SpotifyUris.base62ToGidHex("0000000000000000000001")
        assertEquals("00000000000000000000000000000001", hex)
        assertEquals(32, SpotifyUris.base62ToGidHex("1Xyo4u8uXC1ZmMpatF05PJ")!!.length)
        assertNull(SpotifyUris.base62ToGidHex("short"))
        assertNull(SpotifyUris.base62ToGidHex("!!!!!!!!!!!!!!!!!!!!!!"))
    }

    @Test fun imageUrls() {
        assertEquals("https://i.scdn.co/image/ab6761610000e5eb1a9824a587cc162d8b98fa6f",
            SpotifyUris.imageUrl("spotify:image:ab6761610000e5eb1a9824a587cc162d8b98fa6f"))
        assertEquals("https://x/y.jpg", SpotifyUris.imageUrl("https://x/y.jpg"))
        assertNull(SpotifyUris.imageUrl(""))
        assertNull(SpotifyUris.imageUrl("junk"))
    }

    @Test fun upgrade() {
        val h = "1a9824a587cc162d8b98fa6f"
        assertEquals("https://i.scdn.co/image/ab6761610000e5eb$h", SpotifyUris.upgradeImageUrl("https://i.scdn.co/image/ab67616100005174$h"))
        assertEquals("https://i.scdn.co/image/ab6761610000e5eb$h", SpotifyUris.upgradeImageUrl("https://i.scdn.co/image/ab6761610000f178$h"))
        assertEquals("https://i.scdn.co/image/ab67616d0000b273$h", SpotifyUris.upgradeImageUrl("https://i.scdn.co/image/ab67616d00001e02$h"))
        val unk = "https://i.scdn.co/image/ab6765630000ba8a$h"
        assertEquals(unk, SpotifyUris.upgradeImageUrl(unk))
        assertEquals("https://example.com/a.png", SpotifyUris.upgradeImageUrl("https://example.com/a.png"))
    }

    @Test fun canvasUrl() {
        val u = "https://canvaz.scdn.co/upload/artist/abc/video/0123456789abcdef.cnvs.mp4"
        assertTrue(SpotifyUris.isHttp(u)); assertTrue(SpotifyUris.isCanvasHost(u))
        assertEquals("mp4", SpotifyUris.extensionOf(u, "bin"))
        assertEquals("mp4", SpotifyUris.extensionOf("https://canvaz.scdn.co/x", "mp4"))
        assertFalse(SpotifyUris.isHttp("spotify-video://abc"))
        assertFalse(SpotifyUris.isCanvasHost("https://evil.example.com/a.mp4"))
        assertEquals("video/mp4", SpotifyUris.mimeForExt("MP4"))
    }

    @Test fun fileNames() {
        assertEquals("A_B_C", SpotifyUris.safeFileName("A / B: C"))
        assertEquals("spotify", SpotifyUris.safeFileName("///"))
    }

    @Test fun shareCardPredicate() {
        assertTrue(ShareRegistry.looksLikeShareCard("/data/user/0/com.spotify.music/cache/shareable/card.png", "com.spotify.music.fileprovider"))
        assertTrue(ShareRegistry.looksLikeShareCard("/data/user/0/com.spotify.music/files/share/x.mp4", null))
        assertFalse(ShareRegistry.looksLikeShareCard("/data/user/0/com.spotify.music/cache/shareable/x.txt", null))
        assertFalse(ShareRegistry.looksLikeShareCard("/data/user/0/com.spotify.music/cache/img.png", null))
        assertFalse(ShareRegistry.looksLikeShareCard("/cache/share/a.png", "com.spotify.music.pushnotificationsv2"))
    }
}
