package dev.kifranei.spotifyplus

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Static guard: nothing reachable from HookEntry.initZygote may need Looper/Context at class-init time. */
class ZygoteSafetyTest {
    private val root = listOf("src/main/java", "app/src/main/java").map(::File).first { it.exists() }
    private fun src(name: String) = root.walkTopDown().first { it.name == name }.readText()

    @Test fun spotifyHostHasNoEagerLooperOrHandler() {
        val bad = src("SpotifyHost.kt").lines().filter {
            Regex("""^\s*(private |internal )?(val|var)\s+\w+(\s*:\s*[\w.<>]+)?\s*=\s*.*(Handler\(|Looper\.)""").containsMatchIn(it)
        }
        assertTrue("eager Looper/Handler initializers: $bad", bad.isEmpty())
        assertTrue(src("SpotifyHost.kt").contains("by lazy"))
    }

    @Test fun noLooperUseOutsideSpotifyHost() {
        root.walkTopDown().filter { it.extension == "kt" && it.name != "SpotifyHost.kt" }.forEach {
            assertFalse("${it.name} uses Looper/Handler(", Regex("""getMainLooper|Handler\(""").containsMatchIn(it.readText()))
        }
    }

    @Test fun initZygoteOnlyStoresModulePath() {
        val body = src("HookEntry.kt").substringAfter("fun initZygote").substringBefore("private inline fun step")
        assertFalse(body.contains("SpotifyPlusLog"))
        assertTrue(body.contains("modulePath"))
    }
}
