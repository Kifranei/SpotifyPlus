package dev.kifranei.spotifyplus.hook

import android.app.Application
import android.content.Context
import dev.kifranei.spotifyplus.BuildConfig
import dev.kifranei.spotifyplus.util.SpotifyPlusLog
import org.luckypray.dexkit.DexKitBridge
import java.lang.reflect.Constructor
import java.lang.reflect.Modifier

/**
 * Resolves R8-obfuscated host names.
 *
 *  1. **Known Spotify version** ([HostSymbols.KNOWN], verified offline by `scripts/verify_host_symbols.py`): use the table.
 *  2. **Unknown version**: DexKit, from string anchors + structure (same fingerprints the verify script re-checks offline):
 *
 *  | key               | anchor                                                                                    |
 *  |-------------------|-------------------------------------------------------------------------------------------|
 *  | menuVm            | ctor using "ContextMenuViewModel cannot contain items with duplicate itemResId"           |
 *  | headers           | return type of the static method using "Expected alternating header names and values"     |
 *  | share.dest        | class of the method using "Unsupported share data type for download: "                   |
 *  | share.id.*        | `<clinit>` using exactly "qr-code" / "spotify-on-platform-users-nearby", super = dest.a() type |
 *  | share.resolver(.m)| method using "Destination with id " (1 param = id type)                                   |
 *  | share.config      | Parcelable using "PLATFORM","KEEP","SMART" with an (int, List)/(List, int) ctor           |
 *  | share.unit        | class using exactly "kotlin.Unit" with static `a` whose toString is "kotlin.Unit"          |
 *  | share.setFactory  | static (Object[]) method invoked by dest.<init>                                            |
 *  | share.lazyMap     | `invoke()` that calls `LinkedHashMap.<init>` and destination.a() (all candidates, ≤4)      |
 *  | share.finalList   | (config, List, *) method                                                                    |
 *  | share.filter      | static (List, List) → ArrayList calling destination.a()                                    |
 *  | share.hiddenSet   | Parcelable with Set + boolean + String fields (all candidates; hook only strips our ids)   |
 *  | share.onPlatform  | static (id) → boolean reading the share.id.share singleton (isOnPlatform)                 |
 *
 * Results are cached per (Spotify versionName, lastUpdateTime, module build). If the table *and* DexKit fail, the
 * affected features are skipped (logged), never forced.
 */
object DexResolver {
    const val ANCHOR_MENU_VM = "ContextMenuViewModel cannot contain items with duplicate itemResId"
    const val ANCHOR_HEADERS = "Expected alternating header names and values"
    const val ANCHOR_DOWNLOAD_DEST = "Unsupported share data type for download: "
    const val ANCHOR_RESOLVER = "Destination with id "

    private const val CACHE = "spotifyplus_dexcache"
    /**
     * Bump whenever discovery gains keys: a cached result from an older discovery would silently lack them (the module
     * versionCode does not change between test builds). 3: `share.onPlatform(.m)`.
     */
    private const val SCHEMA = 3

    data class Resolved(val menuVmClass: String?, val headersClass: String?, val share: Map<String, String>?, val how: String)

    @Volatile private var loaded = false

    /** Loads libdexkit.so: normal loadLibrary first, then straight out of the module APK. */
    fun ensureNative(): Boolean {
        if (loaded) return true
        try { System.loadLibrary("dexkit"); loaded = true; return true } catch (_: Throwable) {}
        val mp = SpotifyHost.modulePath
        if (mp != null) {
            for (abi in android.os.Build.SUPPORTED_ABIS) {
                try { System.load("$mp!/lib/$abi/libdexkit.so"); loaded = true; return true } catch (_: Throwable) {}
            }
            // last resort: copy the .so out of the module APK into the host's cache dir and load that
            val dir = SpotifyHost.app?.codeCacheDir ?: SpotifyHost.app?.cacheDir
            if (dir != null) {
                for (abi in android.os.Build.SUPPORTED_ABIS) {
                    try {
                        java.util.zip.ZipFile(mp).use { z ->
                            val e = z.getEntry("lib/$abi/libdexkit.so") ?: return@use
                            val out = java.io.File(dir, "spotifyplus-libdexkit-$abi.so")
                            z.getInputStream(e).use { i -> out.outputStream().use { o -> i.copyTo(o) } }
                            System.load(out.absolutePath)
                            loaded = true
                        }
                        if (loaded) return true
                    } catch (t: Throwable) { SpotifyPlusLog.w("extract/load libdexkit ($abi) failed", t) }
                }
            }
        }
        SpotifyPlusLog.w("libdexkit.so could not be loaded")
        return false
    }

    private fun encode(m: Map<String, String>) = m.entries.joinToString("\n") { "${it.key}=${it.value}" }
    private fun decode(s: String?): Map<String, String>? = s?.lineSequence()?.mapNotNull { l ->
        val i = l.indexOf('='); if (i <= 0) null else l.substring(0, i) to l.substring(i + 1)
    }?.toMap()?.takeIf { it.isNotEmpty() }

    /** [forceDexKit]: ignore the version table (used when a table-based hook failed its structural check on device). */
    fun resolve(app: Application, cl: ClassLoader, forceDexKit: Boolean = false): Resolved {
        SpotifyPlusLog.attach(app)
        val sp = app.getSharedPreferences(CACHE, Context.MODE_PRIVATE)
        val info = runCatching { app.packageManager.getPackageInfo(app.packageName, 0) }.getOrNull()
        val version = info?.versionName
        val table = if (forceDexKit) null else HostSymbols.forVersion(version)
        if (table != null) {
            val r = Resolved(table["menuVm"], table["headers"], table.names.filterKeys { it.startsWith("share.") }, "table:${table.version}")
            SpotifyPlusLog.i("DexResolver: $version is a known version -> ${r.how}")
            return r
        }
        val key = "$SCHEMA|${if (forceDexKit) "force|" else ""}$version|${info?.lastUpdateTime}|${BuildConfig.VERSION_CODE}|${BuildConfig.VERSION_NAME}"
        if (sp.getString("key", null) == key) {
            val m = sp.getString("menuVm", null); val h = sp.getString("headers", null); val s = decode(sp.getString("share", null))
            if (m != null || h != null || s != null) return Resolved(m, h, s, "cache")
        }
        if (!forceDexKit) SpotifyPlusLog.w("DexResolver: Spotify $version has no symbol table (known: ${HostSymbols.KNOWN.joinToString { it.version }}) — using DexKit")
        var menu: String? = null
        var headers: String? = null
        var share: Map<String, String>? = null
        var how = "dexkit"
        if (ensureNative()) {
            try {
                val ai = app.applicationInfo
                val apks = (listOf(ai.sourceDir) + (ai.splitSourceDirs?.toList() ?: emptyList())).distinct()
                SpotifyPlusLog.i("DexResolver: scanning ${apks.size} apk(s): ${apks.joinToString { java.io.File(it).name }}")
                for (apk in apks) {
                    if (menu != null && headers != null && share != null) break
                    try {
                        DexKitBridge.create(apk).use { bridge ->
                            if (menu == null) menu = runCatching {
                                bridge.findMethod { matcher { usingStrings(ANCHOR_MENU_VM) } }.firstOrNull { it.isConstructor }?.className
                            }.onFailure { SpotifyPlusLog.w("DexKit menuVm failed", it) }.getOrNull()
                            // Headers.of(String...) is a static method whose return type is Headers
                            if (headers == null) headers = runCatching {
                                bridge.findMethod { matcher { usingStrings(ANCHOR_HEADERS) } }.firstOrNull { it.isMethod }?.returnTypeName
                            }.onFailure { SpotifyPlusLog.w("DexKit headers failed", it) }.getOrNull()
                            if (share == null) share = runCatching { discoverShare(bridge, cl) }
                                .onFailure { SpotifyPlusLog.w("DexKit share discovery failed", it) }.getOrNull()
                        }
                    } catch (t: Throwable) {
                        SpotifyPlusLog.w("DexKit resolve failed on ${java.io.File(apk).name}", t)
                    }
                }
            } catch (t: Throwable) {
                SpotifyPlusLog.w("DexKit resolve failed", t)
            }
        } else how = "no-native"
        if (menu != null || headers != null || share != null) {
            sp.edit().putString("key", key).putString("menuVm", menu).putString("headers", headers)
                .putString("share", share?.let(::encode)).apply()
        }
        SpotifyPlusLog.i("DexResolver: menuVm=$menu headers=$headers share=$share via $how")
        return Resolved(menu, headers, share, how)
    }

    private fun ClassLoader.tryLoad(n: String?): Class<*>? = n?.let { runCatching { loadClass(it) }.getOrNull() }

    /** Unknown-version fallback for [ShareMenuHook]; returns null when the essential pieces are not found. */
    private fun discoverShare(b: DexKitBridge, cl: ClassLoader): Map<String, String>? {
        val out = LinkedHashMap<String, String>()
        fun step(name: String, block: () -> Unit) = runCatching(block).onFailure { SpotifyPlusLog.w("DexKit share.$name failed", it) }

        val destName = b.findMethod { matcher { usingStrings(ANCHOR_DOWNLOAD_DEST) } }
            .map { it.className }.distinct()
            .firstOrNull { n -> cl.tryLoad(n)?.let { c -> runCatching { c.getDeclaredMethod("a") }.isSuccess } == true }
            ?: return null
        val dest = cl.tryLoad(destName)!!
        val idType = dest.getDeclaredMethod("a").returnType
        val destBase = dest.superclass?.superclass ?: return null
        // R8 emits invoke-virtual destination.a() against the topmost class declaring a() (9.1.90 p.rm31, 9.1.98 p.f371)
        val aOwner = generateSequence(dest as Class<*>) { it.superclass }.lastOrNull { c ->
            c.declaredMethods.any { it.name == "a" && it.parameterTypes.isEmpty() && it.returnType == idType }
        }?.name ?: destBase.name
        out["share.dest"] = destName

        for ((key, value) in listOf("share.id.save" to HostSymbols.SPARE_ID_SAVE, "share.id.share" to HostSymbols.SPARE_ID_SHARE)) {
            b.findMethod { matcher { usingEqStrings(value) } }.filter { it.isStaticInitializer }.map { it.className }
                .firstOrNull { cl.tryLoad(it)?.superclass == idType }?.let { out[key] = it }
        }
        b.findMethod { matcher { usingStrings(ANCHOR_RESOLVER) } }.firstOrNull { m ->
            m.isMethod && m.paramTypeNames == listOf(idType.name) && m.returnTypeName == destBase.name
        }?.let { out["share.resolver"] = it.className; out["share.resolver.m"] = it.methodName }

        step("config") {
            b.findMethod { matcher { usingStrings("PLATFORM", "KEEP", "SMART") } }.map { it.className }.distinct().firstOrNull { n ->
                cl.tryLoad(n)?.declaredConstructors?.any { c ->
                    val t = c.parameterTypes
                    t.size == 2 && t.count { it == Int::class.javaPrimitiveType } == 1 && t.any { java.util.List::class.java.isAssignableFrom(it) }
                } == true
            }?.let { out["share.config"] = it }
        }
        step("unit") {
            b.findMethod { matcher { usingEqStrings("kotlin.Unit") } }.map { it.className }.distinct().firstOrNull { n ->
                runCatching { cl.loadClass(n).getField("a").get(null)?.toString() == "kotlin.Unit" }.getOrDefault(false)
            }?.let { out["share.unit"] = it }
        }
        step("setFactory") {
            b.findMethod { matcher { declaredClass(destName); name("<init>") } }.flatMap { it.invokes }.firstOrNull { m ->
                m.isMethod && Modifier.isStatic(m.modifiers) && m.paramTypeNames == listOf("java.lang.Object[]")
            }?.let { out["share.setFactory"] = it.className; out["share.setFactory.m"] = it.methodName }
        }
        step("lazyMap") {
            b.findMethod {
                matcher {
                    name("invoke"); paramCount(0)
                    addInvoke { declaredClass("java.util.LinkedHashMap"); name("<init>") }
                    addInvoke { declaredClass(aOwner); name("a") }
                }
            }.map { it.className }.distinct().filter { n -> cl.tryLoad(n)?.let { c -> runCatching { c.getDeclaredField("b") }.isSuccess } == true }
                .takeIf { it.isNotEmpty() }?.let { out["share.lazyMap"] = it.take(4).joinToString(",") }
        }
        out["share.config"]?.let { cfg ->
            step("finalList") {
                b.findMethod { matcher { paramTypes(cfg, "java.util.List", null) } }.firstOrNull { m ->
                    m.paramTypeNames.getOrNull(0) == cfg && m.paramTypeNames.getOrNull(1) == "java.util.List"
                }?.let { out["share.finalList"] = it.className; out["share.finalList.m"] = it.methodName }
            }
        }
        step("filter") {
            b.findMethod {
                matcher {
                    paramTypes("java.util.List", "java.util.List"); returnType("java.util.ArrayList")
                    addInvoke { declaredClass(aOwner); name("a") }
                }
            }.firstOrNull { Modifier.isStatic(it.modifiers) }?.let { out["share.filter"] = it.className; out["share.filter.m"] = it.methodName }
        }
        step("hiddenSet") {
            b.findClass {
                matcher {
                    addInterface("android.os.Parcelable")
                    addField { type("java.util.Set") }; addField { type("boolean") }; addField { type("java.lang.String") }
                }
            }.map { it.name }.filter { n ->
                cl.tryLoad(n)?.let { c ->
                    c.declaredFields.count { !Modifier.isStatic(it.modifiers) } <= 4 &&
                        c.declaredConstructors.any { k -> !k.isSynthetic && k.parameterTypes.count { java.util.Set::class.java.isAssignableFrom(it) } == 1 }
                } == true
            }.takeIf { it.size in 1..3 }?.let { out["share.hiddenSet"] = it.joinToString(",") }
        }
        // static isOnPlatform(id): boolean — reads the users-nearby singleton (our SHARE id) among the on-platform ids
        out["share.id.share"]?.let { shareId ->
            step("onPlatform") {
                b.findMethod {
                    matcher { paramTypes(idType.name); returnType("boolean"); addUsingField(org.luckypray.dexkit.query.matchers.FieldMatcher().apply { declaredClass(shareId); type(shareId) }) }
                }.firstOrNull { Modifier.isStatic(it.modifiers) }?.let { out["share.onPlatform"] = it.className; out["share.onPlatform.m"] = it.methodName }
            }
        }
        // the "native-share-menu" destination only donates its icon; optional
        step("more") {
            val moreId = b.findMethod { matcher { usingEqStrings("native-share-menu") } }.filter { it.isStaticInitializer }
                .map { it.className }.firstOrNull { cl.tryLoad(it)?.superclass == idType }
            if (moreId != null) b.findMethod {
                matcher { name("a"); paramCount(0); returnType(idType.name); addUsingField(org.luckypray.dexkit.query.matchers.FieldMatcher().apply { declaredClass(moreId); type(moreId) }) }
            }.map { it.className }.firstOrNull { it != moreId && cl.tryLoad(it)?.superclass == dest.superclass }?.let { out["share.more"] = it }
        }
        SpotifyPlusLog.i("DexKit share discovery: $out")
        return out.takeIf { "share.resolver" in it && "share.config" in it && "share.id.save" in it && "share.id.share" in it }
    }

    /** First declared constructor accepting [accept]. Types matter: R8/Kotlin add synthetic (…, int) overloads. */
    fun loadCtor(cl: ClassLoader, className: String?, accept: (Array<Class<*>>) -> Boolean): Constructor<*>? {
        if (className == null) return null
        return runCatching {
            cl.loadClass(className).declaredConstructors.firstOrNull {
                !it.isSynthetic && accept(it.parameterTypes)
            }?.also { it.isAccessible = true }
        }.onFailure { SpotifyPlusLog.w("loadCtor($className) failed", it) }.getOrNull()
    }
}
