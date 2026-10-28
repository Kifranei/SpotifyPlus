package dev.kifranei.spotifyplus

import dev.kifranei.spotifyplus.hook.HostSymbols
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Shape of the version tables (the names themselves are checked against real dex by scripts/verify_host_symbols.py). */
class HostSymbolsTest {
    private val required = setOf(
        "menuVm", "headers", "share.dest", "share.more", "share.resolver", "share.resolver.m", "share.config", "share.unit",
        "share.setFactory", "share.setFactory.m", "share.lazyMap", "share.hiddenSet", "share.finalList", "share.finalList.m",
        "share.filter", "share.filter.m", "share.id.save", "share.id.share", "share.onPlatform", "share.onPlatform.m",
    )

    @Test fun everyTableHasEveryKey() {
        HostSymbols.KNOWN.forEach { t -> assertEquals("${t.version} keys", required, t.names.keys) }
    }

    @Test fun knownVersions() {
        assertNotNull(HostSymbols.forVersion("9.1.90.2270"))
        assertNotNull(HostSymbols.forVersion("9.1.98.521"))
        assertNull(HostSymbols.forVersion("9.1.99.0"))
        assertEquals(HostSymbols.KNOWN.size, HostSymbols.KNOWN.map { it.version }.toSet().size)
    }

    @Test fun classNamesLookObfuscatedAndMethodNamesAreBare() {
        HostSymbols.KNOWN.forEach { t ->
            t.names.forEach { (k, v) ->
                if (k.endsWith(".m")) assertTrue("$k=$v", v.matches(Regex("[a-zA-Z_$][\\w$]*")))
                else assertTrue("$k=$v", v.split(',').all { it.startsWith("p.") })
            }
        }
    }
}
