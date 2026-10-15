package dev.kifranei.spotifyplus

import dev.kifranei.spotifyplus.hook.MediaNotifActionsHook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaNotifActionsHookTest {
    @Test fun spotifyActionIdsMatchDexAnchors() {
        assertEquals("ADD_TO_COLLECTION", MediaNotifActionsHook.ACTION_ADD)
        assertEquals("REMOVE_FROM_COLLECTION", MediaNotifActionsHook.ACTION_REMOVE)
        assertEquals("com.spotify.toggle_shuffle", MediaNotifActionsHook.ACTION_TOGGLE_SHUFFLE)
        assertEquals("com.spotify.toggle_repeat", MediaNotifActionsHook.ACTION_TOGGLE_REPEAT)
        assertTrue(MediaNotifActionsHook.ACTION_ADD_FULL.contains("NOTIFICATION_ADD_TO_COLLECTION"))
        assertTrue(MediaNotifActionsHook.ACTION_REMOVE_FULL.contains("NOTIFICATION_REMOVE_FROM_COLLECTION"))
    }
}
