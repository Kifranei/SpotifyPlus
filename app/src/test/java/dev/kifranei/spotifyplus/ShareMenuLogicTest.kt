package dev.kifranei.spotifyplus

import dev.kifranei.spotifyplus.hook.ShareMenuLogic
import org.junit.Assert.*
import org.junit.Test

class ShareMenuLogicTest {
    @Test fun insertsAfterAnchor() {
        assertEquals(listOf("copy", "save", "share", "wa"), ShareMenuLogic.insertAfter(listOf("copy", "wa"), listOf("save", "share")) { it == "copy" })
    }
    @Test fun appendsWithoutAnchor() {
        assertEquals(listOf("a", "b", "save"), ShareMenuLogic.insertAfter(listOf("a", "b"), listOf("save")) { it == "zzz" })
    }
    @Test fun idempotent() {
        val once = ShareMenuLogic.insertAfter(listOf("copy", "wa"), listOf("save")) { it == "copy" }
        assertSame(once, ShareMenuLogic.insertAfter(once, listOf("save")) { it == "copy" })
    }
    @Test fun imageRefs() {
        assertTrue(ShareMenuLogic.looksLikeImageRef("content://com.spotify.music.share/shareable/a.png"))
        assertTrue(ShareMenuLogic.looksLikeImageRef("file:///data/user/0/x/cache/shareable/b.JPG"))
        assertTrue(ShareMenuLogic.looksLikeImageRef("/data/user/0/x/c.webp"))
        assertFalse(ShareMenuLogic.looksLikeImageRef("spotify:track:1"))
        assertFalse(ShareMenuLogic.looksLikeImageRef("/data/x/v.mp4"))
        assertTrue(ShareMenuLogic.looksLikeVideoRef("file:///x/v.mp4"))
        assertFalse(ShareMenuLogic.looksLikeImageRef(null))
    }
    @Test fun labels() {
        assertEquals("保存图片", ShareMenuLogic.labelFor(ShareMenuLogic.RES_SAVE))
        assertEquals("分享图片", ShareMenuLogic.labelFor(ShareMenuLogic.RES_SHARE))
        assertNull(ShareMenuLogic.labelFor(0x7f1327e8))
    }
}
