package dev.kifranei.spotifyplus.hook

/**
 * Version-keyed table of R8-obfuscated Spotify names recovered by static analysis (jadx).
 *
 * Every entry is only a *hint*: each hook re-validates the structure by reflection before hooking and skips its feature
 * (fail-open) on mismatch. For versions not listed here (or when a table name fails that check on device),
 * [DexResolver] derives the names from string anchors + structure with DexKit instead.
 *
 * Keep the `version("…", mapOf("key" to "value", …))` shape: `scripts/verify_host_symbols.py` parses this file and
 * checks every name against the extracted dex of each listed version.
 *
 * Keys (old 9.1.90 name in parentheses → role):
 *  - `menuVm`            (p.yal)  ContextMenuViewModel; ctor (header, List<item>, boolean), anchor "ContextMenuViewModel cannot…"
 *  - `headers`           (p.ad40) okhttp3.Headers; ctor (String[]), anchor "Expected alternating header names and values"
 *  - `share.dest`        (p.aft)  Spotify's own "Download" share destination (anchor "Unsupported share data type for download: ")
 *  - `share.more`        (p.k6k0) "native-share-menu" ("More") destination (icon source for 分享图片)
 *  - `share.resolver`    (p.tch0) + `share.resolver.m` (o): id → destination lookup ("Destination with id … not found")
 *  - `share.config`      (p.uas)  destination-id list config: ctor (int, List) in 9.1.90, (List, int) in 9.1.98
 *  - `share.unit`        (p.aad1) kotlin.Unit
 *  - `share.setFactory`  (p.s2j1) + `share.setFactory.m` (o): host varargs immutable-set factory used for supported kinds
 *  - `share.lazyMap`     (p.cwm0) Function0 that builds the resolver's lazy id→destination LinkedHashMap (`invoke`, field `b`)
 *  - `share.hiddenSet`   (p.a041) per-format share config holding the hidden destination-id Set (ctor has one Set param)
 *  - `share.finalList`   (p.fpx)  + `share.finalList.m` (i): (config, List, Continuation) → final destination list
 *  - `share.filter`      (p.lle0) + `share.filter.m` (h): static (List<dest>, List<id>) → ArrayList
 *  - `share.id.save` / `share.id.share` (p.jn31 "qr-code" / p.tn31 "spotify-on-platform-users-nearby"): spare id singletons
 *  - `share.onPlatform`  (p.wub0) + `share.onPlatform.m` (j): static `isOnPlatform(id): boolean` (single / search / group /
 *                        create-group / users-nearby). True for our borrowed SHARE id → lyrics share gets link data and the
 *                        on-platform flow ("无法分享"); hooked to answer false for our ids.
 */
object HostSymbols {
    const val SPARE_ID_SAVE = "qr-code"
    const val SPARE_ID_SHARE = "spotify-on-platform-users-nearby"

    class Table(val version: String, val names: Map<String, String>) {
        operator fun get(key: String): String? = names[key]
        override fun toString() = "HostSymbols[$version]"
    }

    private fun version(v: String, names: Map<String, String>) = Table(v, names)

    val KNOWN: List<Table> = listOf(
        version(
            "9.1.90.2270",
            mapOf(
                "menuVm" to "p.yal",
                "headers" to "p.ad40",
                "share.dest" to "p.aft",
                "share.more" to "p.k6k0",
                "share.resolver" to "p.tch0",
                "share.resolver.m" to "o",
                "share.config" to "p.uas",
                "share.unit" to "p.aad1",
                "share.setFactory" to "p.s2j1",
                "share.setFactory.m" to "o",
                "share.lazyMap" to "p.cwm0",
                "share.hiddenSet" to "p.a041",
                "share.finalList" to "p.fpx",
                "share.finalList.m" to "i",
                "share.filter" to "p.lle0",
                "share.filter.m" to "h",
                "share.id.save" to "p.jn31",
                "share.id.share" to "p.tn31",
                "share.onPlatform" to "p.wub0",
                "share.onPlatform.m" to "j",
            ),
        ),
        version(
            "9.1.98.521",
            mapOf(
                "menuVm" to "p.ajm",
                "headers" to "p.h360",
                "share.dest" to "p.vqu",
                "share.more" to "p.djm0",
                "share.resolver" to "p.fmj0",
                "share.resolver.m" to "k",
                "share.config" to "p.nlt",
                "share.unit" to "p.bhh1",
                "share.setFactory" to "p.hoo1",
                "share.setFactory.m" to "w",
                "share.lazyMap" to "p.tcp0",
                "share.hiddenSet" to "p.vh71",
                "share.finalList" to "p.sr00",
                "share.finalList.m" to "e",
                "share.filter" to "p.uwo1",
                "share.filter.m" to "o",
                "share.id.save" to "p.w371",
                "share.id.share" to "p.g471",
                "share.onPlatform" to "p.qwo1",
                "share.onPlatform.m" to "i",
            ),
        ),
    )

    fun forVersion(versionName: String?): Table? = KNOWN.firstOrNull { it.version == versionName }

}
