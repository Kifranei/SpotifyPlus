package dev.kifranei.spotifyplus

import dev.kifranei.spotifyplus.hook.ExplicitLabelHook
import dev.kifranei.spotifyplus.hook.ShareRegistry
import org.junit.Assert.*
import org.junit.Test

class ShareSelectionTest {
    @Test fun emptyAndSingle() {
        assertNull(ShareRegistry.pickSingle(emptyList(), listOf("a")))
        assertEquals("a", ShareRegistry.pickSingle(listOf("a"), emptyList()))
    }
    @Test fun newestRenderedWins() {
        assertEquals("b", ShareRegistry.pickSingle(listOf("a", "b", "c"), listOf("c", "x", "b")))
    }
    @Test fun fallbackLastCandidate() {
        assertEquals("c", ShareRegistry.pickSingle(listOf("a", "b", "c"), listOf("z")))
    }
    @Test fun explicitReplace() {
        val s = setOf("儿童不宜", "EXPLICIT")
        assertTrue(ExplicitLabelHook.shouldReplace(" 儿童不宜 ", s))
        assertFalse(ExplicitLabelHook.shouldReplace("儿童不宜内容", s))
        assertFalse(ExplicitLabelHook.shouldReplace(null, s))
    }
}
