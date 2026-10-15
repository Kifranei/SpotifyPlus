package dev.kifranei.spotifyplus

import dev.kifranei.spotifyplus.hook.SystemUiMediaActionsHook
import org.junit.Assert.assertEquals
import org.junit.Test

class SystemUiMediaActionsHookTest {
    @Test fun systemUiPackageIsStandard() {
        assertEquals("com.android.systemui", SystemUiMediaActionsHook.SYSTEM_UI)
    }
}
