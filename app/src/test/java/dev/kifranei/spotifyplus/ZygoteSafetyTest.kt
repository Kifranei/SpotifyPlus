package dev.kifranei.spotifyplus

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Static guard: nothing reachable from HookEntry.onModuleLoaded may need Looper/Context at class-init time. */
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

    @Test fun onModuleLoadedOnlyAttachesRuntimeAndPath() {
        val body = src("HookEntry.kt").substringAfter("fun onModuleLoaded").substringBefore("override fun onPackageReady")
        assertFalse("onModuleLoaded must not touch SpotifyPlusLog", body.contains("SpotifyPlusLog"))
        assertFalse("onModuleLoaded must not install hooks", body.contains("PlaybackHook"))
        assertTrue(body.contains("modulePath") || body.contains("ModernHookRuntime.attach"))
        assertTrue(src("HookEntry.kt").contains("class HookEntry : XposedModule()"))
    }

    @Test fun shipsModernApi102Metadata() {
        val meta = listOf(
            File("src/main/resources/META-INF/xposed/module.prop"),
            File("app/src/main/resources/META-INF/xposed/module.prop"),
        ).first { it.exists() }.readText()
        val init = listOf(
            File("src/main/resources/META-INF/xposed/java_init.list"),
            File("app/src/main/resources/META-INF/xposed/java_init.list"),
        ).first { it.exists() }.readText()
        val scope = listOf(
            File("src/main/resources/META-INF/xposed/scope.list"),
            File("app/src/main/resources/META-INF/xposed/scope.list"),
        ).first { it.exists() }.readText()
        assertTrue(meta.contains("minApiVersion=102"))
        assertTrue(meta.contains("targetApiVersion=102"))
        assertTrue(init.contains("dev.kifranei.spotifyplus.hook.HookEntry"))
        assertTrue(scope.contains("com.spotify.music"))
        // Legacy assets/xposed_init should be gone (modern-only).
        val legacy = listOf(
            File("src/main/assets/xposed_init"),
            File("app/src/main/assets/xposed_init"),
        )
        assertFalse("legacy xposed_init should be removed", legacy.any { it.exists() })
    }
}
