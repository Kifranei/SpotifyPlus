package dev.kifranei.spotifyplus.hook

import android.app.Activity
import android.app.Application
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.net.Uri
import android.os.Bundle
import dev.kifranei.spotifyplus.media.MediaSaver
import dev.kifranei.spotifyplus.util.SpotifyPlusLog
import dev.kifranei.spotifyplus.util.SpotifyUris
import java.io.File
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentHashMap

/** Pure, unit-tested helpers (no Android / host classes). */
object ShareMenuLogic {
    /** Insert [extras] right after the first element matching [anchor]; append when there is none. Skips already-present extras. */
    fun <T> insertAfter(list: List<T>, extras: List<T>, anchor: (T) -> Boolean): List<T> {
        val add = extras.filter { e -> list.none { it === e || it == e } }
        if (add.isEmpty()) return list
        val i = list.indexOfFirst(anchor)
        val out = ArrayList<T>(list.size + add.size)
        if (i < 0) { out.addAll(list); out.addAll(add) }
        else { out.addAll(list.subList(0, i + 1)); out.addAll(add); out.addAll(list.subList(i + 1, list.size)) }
        return out
    }

    private val IMAGE_EXT = setOf("png", "jpg", "jpeg", "webp")

    /** Does a string value from the host's share-data object look like a reference to a local image? */
    fun looksLikeImageRef(s: String?): Boolean {
        if (s.isNullOrEmpty() || s.length > 2048) return false
        val isRef = s.startsWith("content://") || s.startsWith("file://") || s.startsWith("/")
        if (!isRef) return false
        val ext = s.substringBefore('?').substringAfterLast('.', "").lowercase()
        // content:// FileProvider paths normally keep the extension; extension-less content Uris are still tried.
        return ext in IMAGE_EXT || (s.startsWith("content://") && ext !in setOf("mp4", "m4v", "webm", "gif"))
    }

    fun looksLikeVideoRef(s: String?): Boolean =
        !s.isNullOrEmpty() && s.substringBefore('?').substringAfterLast('.', "").lowercase() in setOf("mp4", "m4v", "webm")

    /** Fake string-resource ids we hand to the host UI (real ids stay below 0x7f13f000). */
    const val RES_SAVE = 0x7f13f001
    const val RES_SAVE_A11Y = 0x7f13f002
    const val RES_SHARE = 0x7f13f003
    const val RES_SHARE_A11Y = 0x7f13f004
    fun labelFor(id: Int): String? = when (id) {
        RES_SAVE -> "保存图片"
        RES_SAVE_A11Y -> "保存图片到相册"
        RES_SHARE -> "分享图片"
        RES_SHARE_A11Y -> "分享图片"
        else -> null
    }
}

/**
 * Adds 「保存图片」/「分享图片」 as destination items inside **Spotify's own share sheet**
 * (replaces the old Android system-chooser custom actions, which did not work on HyperOS).
 *
 * Names are per Spotify version ([HostSymbols], keys `share.*`; DexKit discovery for unknown versions). Static findings,
 * 9.1.90.2270 → 9.1.98.521 (all log lines use prefix `share-menu: `):
 *  - id type `p.xn31` → `p.k471` (Parcelable singletons: "copy-link", "native-share-menu"/More, "qr-code" `p.jn31` → `p.w371`,
 *    "spotify-on-platform-users-nearby" `p.tn31` → `p.g471`, …).
 *  - destination-id config `p.uas(int, List)` → `p.nlt(List, int)` (**ctor parameter order swapped in 9.1.98**).
 *  - destination base `p.om31` → `p.c371` (label res `e()`, a11y `c()`, icon `d()`, kinds `b()`, `a()` declared on `p.rm31` → `p.f371`);
 *    `p.aft` → `p.vqu` = Spotify's own "Download" destination (anchor "Unsupported share data type for download: "), click handler
 *    `f(p.ao31 → p.n471, Continuation)`; More destination `p.k6k0` → `p.djm0` (icon field `d`).
 *  - resolver `p.tch0.o(id)` → `p.fmj0.k(id)` (lazy `LinkedHashMap` in field `c`, built by `p.cwm0` → `p.tcp0` `invoke()`).
 *  - hidden-destination set holder `p.a041(tq10, Set, boolean, String)` → `p.vh71(Set, boolean, String)` (**Set index moved**).
 *  - final list `p.fpx.i(uas, List, cont)` → `p.sr00.e(nlt, List, cont)`; filter `p.lle0.h(List, List)` → `p.uwo1.o`.
 *  - host kotlin.Unit `p.aad1` → `p.bhh1`; immutable-set factory `p.s2j1.o(Object[])` → `p.hoo1.w(Object[])`.
 *  - share data of the **currently selected carousel card** is field `a` of the click-handler argument. Because our
 *    destinations advertise the story kinds, that is usually *story* data (colour/image/video background + a
 *    transparent sticker); it is flattened by [StoryCard] so the saved image keeps the card colour.
 *
 * Mechanism: build two extra download-destination instances (same ctor args as the real one → same icon/kinds scaffolding), key them in the
 * resolver map under two otherwise-unused existing id singletons (qr-code, users-nearby), append those ids to the config list right
 * after `copy-link`, override `a()/c()/e()` for our instances and replace `f()` with our click handler.
 * Labels are fake string-resource ids answered from a `Resources.getText` hook.
 */
object ShareMenuHook {
    private const val T = "share-menu: "
    private val OUR_IDS = setOf(HostSymbols.SPARE_ID_SAVE, HostSymbols.SPARE_ID_SHARE)
    private val logLookups = java.util.concurrent.atomic.AtomicInteger()
    private const val ID_COPY_LINK = "copy-link"

    private class Item(val kind: Kind, val dest: Any, val id: Any)
    private enum class Kind { SAVE, SHARE }

    @Volatile private var installed = false
    private val items = Collections.synchronizedMap(IdentityHashMap<Any, Item>())
    private val injectedMaps = Collections.synchronizedSet(Collections.newSetFromMap(IdentityHashMap<Any, Boolean>()))
    private val ids = ArrayList<Any>()
    private var unit: Any? = null
    @Volatile private var moreClass: String? = null
    @Volatile private var setFactory: Method? = null
    @Volatile private var topActivity = WeakReference<Activity>(null)
    private val exportCache = ConcurrentHashMap<String, Uri>()

    /**
     * [names] uses the `share.*` keys of [HostSymbols] (version table or DexKit discovery). Every class / member is
     * re-validated here; anything missing disables only the affected step (or the whole share-sheet feature when a
     * required piece is missing). Never throws into the host.
     */
    /** @return true when the share-sheet entries are active (core hooks installed). */
    fun install(cl: ClassLoader, names: Map<String, String>?): Boolean {
        if (installed) return true
        if (names == null) { SpotifyPlusLog.w(T + "no symbol table for this Spotify version — share-menu entries disabled"); return false }
        fun cls(k: String) = names[k]?.let { cl.tryLoad(it) }
        val aft = cls("share.dest"); val tch0 = cls("share.resolver"); val uas = cls("share.config")
        if (aft == null || tch0 == null || uas == null) {
            SpotifyPlusLog.w(T + "classes missing dest=$aft resolver=$tch0 config=$uas (names=$names) — share-menu entries disabled"); return false
        }
        val aMethod = runCatching { aft.getDeclaredMethod("a") }.getOrNull()
        val xn31 = aMethod?.returnType
        val om31 = aft.superclass?.superclass
        val oName = names["share.resolver.m"]
        val oMethod = xn31?.let { x ->
            tch0.declaredMethods.firstOrNull { (oName == null || it.name == oName) && it.parameterTypes.toList() == listOf(x) && it.returnType == om31 }
        }
        val uasCtor = uas.declaredConstructors.firstOrNull { c ->
            val t = c.parameterTypes
            !c.isSynthetic && t.size == 2 && t.count { it == Int::class.javaPrimitiveType } == 1 && t.any { java.util.List::class.java.isAssignableFrom(it) }
        }
        val uasListIdx = uasCtor?.parameterTypes?.indexOfFirst { java.util.List::class.java.isAssignableFrom(it) } ?: -1
        val fMethod = aft.declaredMethods.firstOrNull { it.name == "f" && it.parameterTypes.size == 2 && it.returnType == Any::class.java }
        val cMethod = runCatching { aft.getDeclaredMethod("c") }.getOrNull()
        val eMethod = runCatching { aft.getDeclaredMethod("e") }.getOrNull()
        if (xn31 == null || om31 == null || oMethod == null || uasCtor == null || uasListIdx < 0 || fMethod == null || cMethod == null || eMethod == null ||
            aMethod.returnType != xn31) {
            SpotifyPlusLog.w(T + "structure mismatch xn31=$xn31 om31=$om31 o=$oMethod uas=$uasCtor f=$fMethod — share-menu entries disabled"); return false
        }
        // our two id singletons, verified by their string value
        for ((key, expect) in listOf("share.id.save" to HostSymbols.SPARE_ID_SAVE, "share.id.share" to HostSymbols.SPARE_ID_SHARE)) {
            val c = cls(key) ?: continue
            val inst = runCatching { c.getDeclaredField("d").also { it.isAccessible = true }.get(null) }.getOrNull()
                ?: runCatching { c.declaredFields.firstOrNull { Modifier.isStatic(it.modifiers) && it.type == c }?.also { it.isAccessible = true }?.get(null) }.getOrNull()
                ?: continue
            val v = idValue(xn31, inst)
            if (v == expect) ids += inst else SpotifyPlusLog.w(T + "id ${c.name} value=$v != $expect, skipped")
        }
        if (ids.size < 2) { SpotifyPlusLog.w(T + "need 2 spare ids, got ${ids.size} — disabled"); ids.clear(); return false }
        unit = runCatching { cls("share.unit")!!.getField("a").get(null) }.getOrNull()?.takeIf { it.toString() == "kotlin.Unit" }
        if (unit == null) SpotifyPlusLog.w(T + "host kotlin.Unit not found (click returns null)")
        moreClass = names["share.more"]
        setFactory = runCatching {
            cls("share.setFactory")!!.declaredMethods.first {
                it.name == names["share.setFactory.m"] && Modifier.isStatic(it.modifiers) && it.parameterTypes.size == 1 && it.parameterTypes[0] == Array<Any>::class.java
            }.also { it.isAccessible = true }
        }.getOrNull()
        installed = true
        val done = ArrayList<String>()

        // 1. resolver: inject our destinations into the id→destination map the first time it is queried
        ModernHookRuntime.hookMethod(oMethod, object : ModernHookRuntime.MethodHook() {
            override fun beforeHookedMethod(p: ModernHookRuntime.MethodHookParam) {
                runCatching { ensureInjected(cl, p.thisObject ?: return, aft, xn31) }
                    .onFailure { SpotifyPlusLog.w(T + "inject failed", it) }
                val id = p.args.getOrNull(0)?.let { idValue(xn31, it) }
                if (id != null && (id in OUR_IDS || logLookups.incrementAndGet() <= 40)) SpotifyPlusLog.i(T + "resolver lookup id=$id")
            }
        }); done += "resolver.${oMethod.name}"
        // 1b. ROOT-CAUSE FIX (a): the host builds its destination list from the resolver's lazy map values the first time the
        // sheet opens — usually BEFORE any resolver lookup. Inject at lazy creation (Function0.invoke of the builder) instead.
        for (lazyName in names["share.lazyMap"].orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }) runCatching {
            val cwm = cl.tryLoad(lazyName) ?: error("class missing")
            val inv = cwm.getDeclaredMethod("invoke")
            val fb = field(cwm, "b") ?: error("field b missing")
            ModernHookRuntime.hookMethod(inv, object : ModernHookRuntime.MethodHook() {
                override fun afterHookedMethod(p: ModernHookRuntime.MethodHookParam) {
                    val r = p.result as? MutableMap<*, *> ?: return
                    val owner = runCatching { fb.get(p.thisObject) }.getOrNull() ?: return
                    if (owner.javaClass != tch0) return
                    @Suppress("UNCHECKED_CAST")
                    runCatching { injectMap(cl, r as MutableMap<Any?, Any?>, aft, xn31) }
                        .onFailure { SpotifyPlusLog.w(T + "lazy-map inject failed", it) }
                }
            }); done += "$lazyName.invoke"
        }.onFailure { SpotifyPlusLog.w(T + "lazy-map builder $lazyName unavailable (${it.message})") }
        if (done.none { it.endsWith(".invoke") }) SpotifyPlusLog.w(T + "no lazy-map builder hooked — injection only on resolver lookups")
        // 1c. ROOT-CAUSE FIX (b): per-format hidden-destination sets are subtracted from the final list. Strip our two borrowed
        // ids (qr-code / users-nearby are hidden for card/image formats). 9.1.90: ctor (X, Set, boolean, String); 9.1.98: (Set, boolean, String).
        for (hsName in names["share.hiddenSet"].orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }) runCatching {
            val hs = cl.tryLoad(hsName) ?: error("class missing")
            val ctor = hs.declaredConstructors.firstOrNull { c -> !c.isSynthetic && c.parameterTypes.count { java.util.Set::class.java.isAssignableFrom(it) } == 1 }
                ?: error("ctor with one Set param missing")
            val si = ctor.parameterTypes.indexOfFirst { java.util.Set::class.java.isAssignableFrom(it) }
            ModernHookRuntime.hookMethod(ctor, object : ModernHookRuntime.MethodHook() {
                override fun beforeHookedMethod(p: ModernHookRuntime.MethodHookParam) {
                    val set = p.args[si] as? Set<*> ?: return
                    val hit = set.filter { it != null && (it in ids || idValue(xn31, it) in OUR_IDS) }
                    if (hit.isEmpty()) return
                    p.args[si] = LinkedHashSet(set.filter { it !in hit })
                    SpotifyPlusLog.i(T + "hidden-set ${set.mapNotNull { it?.let { x -> idValue(xn31, x) } }} -> stripped ${hit.map { idValue(xn31, it!!) }}")
                }
            }); done += "$hsName.<init>"
        }.onFailure { SpotifyPlusLog.w(T + "hidden-set class $hsName unavailable (${it.message})") }
        // 1d. final list builder (config, List, Continuation): diagnostics + guaranteed fallback insertion
        runCatching {
            val fpx = cls("share.finalList") ?: error("class missing")
            val mName = names["share.finalList.m"]
            val m = fpx.declaredMethods.firstOrNull {
                (mName == null || it.name == mName) && it.parameterTypes.size == 3 && it.parameterTypes[0] == uas && java.util.List::class.java.isAssignableFrom(it.parameterTypes[1])
            } ?: error("method missing")
            val listField = uas.declaredFields.firstOrNull { !Modifier.isStatic(it.modifiers) && java.util.List::class.java.isAssignableFrom(it.type) }?.also { it.isAccessible = true }
            ModernHookRuntime.hookMethod(m, object : ModernHookRuntime.MethodHook() {
                override fun afterHookedMethod(p: ModernHookRuntime.MethodHookParam) {
                    try {
                        val r = p.result as? List<*> ?: return  // suspended: result arrives later via continuation
                        val cfg = runCatching { (listField?.get(p.args[0]) as? List<*>)?.map { idValue(xn31, it!!) } }.getOrNull()
                        val got = r.map { d -> d?.let { runCatching { idValue(xn31, d.javaClass.getMethod("a").invoke(d)!!) }.getOrNull() } }
                        SpotifyPlusLog.i(T + "final list: config=$cfg rendered=$got")
                        if (got.none { it in OUR_IDS }) {
                            SpotifyPlusLog.w(T + "our entries NOT in final list -> fallback insert")
                            val mine = items.values.map { it.dest }.sortedBy { items[it]!!.kind.ordinal }
                            if (mine.isNotEmpty()) {
                                val out = ShareMenuLogic.insertAfter(r.filterNotNull(), mine) { d ->
                                    runCatching { idValue(xn31, d.javaClass.getMethod("a").invoke(d)!!) }.getOrNull() == ID_COPY_LINK }
                                p.result = out
                            }
                        }
                    } catch (t: Throwable) { SpotifyPlusLog.w(T + "final-list after failed", t) }
                }
            }); done += "finalList.${m.name}"
        }.onFailure { SpotifyPlusLog.w(T + "final-list builder unavailable (${it.message})") }
        // 1e. available-destination filter diagnostics
        runCatching {
            val lle = cls("share.filter") ?: error("class missing")
            val hName = names["share.filter.m"]
            val h = lle.declaredMethods.firstOrNull {
                (hName == null || it.name == hName) && Modifier.isStatic(it.modifiers) && it.parameterTypes.size == 2 &&
                    it.parameterTypes.all { t -> java.util.List::class.java.isAssignableFrom(t) }
            } ?: error("method missing")
            ModernHookRuntime.hookMethod(h, object : ModernHookRuntime.MethodHook() {
                override fun afterHookedMethod(p: ModernHookRuntime.MethodHookParam) {
                    runCatching {
                        val avail = (p.args[0] as List<*>).map { d -> idValue(xn31, d!!.javaClass.getMethod("a").invoke(d)!!) }
                        val cfg = (p.args[1] as List<*>).map { idValue(xn31, it!!) }
                        val res = (p.result as List<*>).map { d -> idValue(xn31, d!!.javaClass.getMethod("a").invoke(d)!!) }
                        if (cfg.any { it in OUR_IDS } || avail.any { it in OUR_IDS })
                            SpotifyPlusLog.i(T + "filter available=$avail config=$cfg -> kept=$res")
                    }
                }
            }); done += "filter.${h.name}"
        }.onFailure { SpotifyPlusLog.w(T + "filter diagnostics unavailable (${it.message})") }
        // 1f. ROOT-CAUSE FIX (lyrics "无法分享"): our SHARE entry borrows the "spotify-on-platform-users-nearby" id, which the
        // host's static isOnPlatform(id) reports as an in-app (send-to-Spotify-user) destination. The lyrics card builder
        // then hands it LINK data and the on-platform flow fails before our click handler runs. Answer false for our ids.
        runCatching {
            val c = cls("share.onPlatform") ?: error("class missing")
            val mName = names["share.onPlatform.m"]
            val m = c.declaredMethods.firstOrNull {
                (mName == null || it.name == mName) && Modifier.isStatic(it.modifiers) && it.returnType == Boolean::class.javaPrimitiveType &&
                    it.parameterTypes.toList() == listOf(xn31)
            } ?: error("method missing")
            val logged = java.util.concurrent.atomic.AtomicInteger()
            ModernHookRuntime.hookMethod(m, object : ModernHookRuntime.MethodHook() {
                override fun beforeHookedMethod(p: ModernHookRuntime.MethodHookParam) {
                    val a = p.args.getOrNull(0) ?: return
                    if (ids.any { it === a }) {
                        p.result = false
                        if (logged.incrementAndGet() <= 5) SpotifyPlusLog.i(T + "isOnPlatform(${idValue(xn31, a)}) -> false (borrowed id)")
                    }
                }
            }); done += "onPlatform.${m.name}"
        }.onFailure { SpotifyPlusLog.w(T + "isOnPlatform hook unavailable (${it.message}) — lyrics share may get link data") }
        // 2. destination id list: put ours right after "copy-link"
        ModernHookRuntime.hookMethod(uasCtor, object : ModernHookRuntime.MethodHook() {
            override fun beforeHookedMethod(p: ModernHookRuntime.MethodHookParam) {
                try {
                    @Suppress("UNCHECKED_CAST") val list = p.args[uasListIdx] as? List<Any?> ?: return
                    val nn = list.filterNotNull()
                    if (nn.isEmpty() || nn.any { it in ids } || nn.none { idValue(xn31, it) == ID_COPY_LINK }) return
                    val out = ShareMenuLogic.insertAfter(list.filterNotNull(), ids.toList()) { idValue(xn31, it) == ID_COPY_LINK }
                    p.args[uasListIdx] = out
                    SpotifyPlusLog.i(T + "destination list ${list.size} -> ${out.size} (inserted after copy-link)")
                } catch (t: Throwable) { SpotifyPlusLog.w(T + "config hook failed", t) }
            }
        }); done += "config.<init>"
        // 3. per-instance overrides of the cloned download destinations
        ModernHookRuntime.hookMethod(aMethod, object : ModernHookRuntime.MethodHook() {
            override fun beforeHookedMethod(p: ModernHookRuntime.MethodHookParam) { items[p.thisObject]?.let { p.result = it.id } }
        })
        ModernHookRuntime.hookMethod(eMethod, object : ModernHookRuntime.MethodHook() {
            override fun beforeHookedMethod(p: ModernHookRuntime.MethodHookParam) {
                items[p.thisObject]?.let { p.result = if (it.kind == Kind.SAVE) ShareMenuLogic.RES_SAVE else ShareMenuLogic.RES_SHARE }
            }
        })
        ModernHookRuntime.hookMethod(cMethod, object : ModernHookRuntime.MethodHook() {
            override fun beforeHookedMethod(p: ModernHookRuntime.MethodHookParam) {
                items[p.thisObject]?.let { p.result = if (it.kind == Kind.SAVE) ShareMenuLogic.RES_SAVE_A11Y else ShareMenuLogic.RES_SHARE_A11Y }
            }
        })
        ModernHookRuntime.hookMethod(fMethod, object : ModernHookRuntime.MethodHook() {
            override fun beforeHookedMethod(p: ModernHookRuntime.MethodHookParam) {
                val it = items[p.thisObject] ?: return
                p.result = unit
                runCatching { onClick(it.kind, p.args[0]) }
                    .onFailure { t -> SpotifyPlusLog.w(T + "click handler failed", t); SpotifyHost.toast("SpotifyPlus：操作失败（${t.javaClass.simpleName}）") }
            }
        }); done += "dest.{a,c,e,f}"
        // 4. labels for the fake string-resource ids
        val rh = object : ModernHookRuntime.MethodHook() {
            override fun beforeHookedMethod(p: ModernHookRuntime.MethodHookParam) {
                val id = p.args.getOrNull(0) as? Int ?: return
                ShareMenuLogic.labelFor(id)?.let { p.result = it }
            }
        }
        ModernHookRuntime.hookAllMethods(Resources::class.java, "getText", rh)
        SpotifyHost.whenReady { app -> trackActivities(app) }
        SpotifyPlusLog.i(T + "hooked ${done.joinToString()} (dest=${aft.name}, resolver=${tch0.name}, config=${uas.name}); " +
            "ids=${ids.map { idValue(xn31, it) }}; unit=${unit != null}; setFactory=${setFactory?.let { it.declaringClass.name + "." + it.name }}")
        return true
    }

    private fun ClassLoader.tryLoad(n: String): Class<*>? = runCatching { loadClass(n) }.getOrNull()

    private fun idValue(xn31: Class<*>, id: Any): String? = runCatching { xn31.getMethod("getValue").invoke(id) as? String }.getOrNull()

    private fun field(c: Class<*>, name: String): Field? =
        generateSequence(c) { it.superclass }.mapNotNull { k -> runCatching { k.getDeclaredField(name) }.getOrNull() }.firstOrNull()?.also { it.isAccessible = true }

    @Suppress("UNCHECKED_CAST")
    private fun ensureInjected(cl: ClassLoader, tch0: Any, aft: Class<*>, xn31: Class<*>) {
        val lazy = field(tch0.javaClass, "c")?.get(tch0) ?: return
        val map = lazy.javaClass.getMethod("getValue").invoke(lazy) as? MutableMap<Any?, Any?> ?: return
        injectMap(cl, map, aft, xn31)
    }

    @Suppress("UNCHECKED_CAST")
    private fun injectMap(cl: ClassLoader, map: MutableMap<Any?, Any?>, aft: Class<*>, xn31: Class<*>) {
        if (!injectedMaps.add(map)) return
        val template = map.values.firstOrNull { it != null && it.javaClass == aft } ?: run {
            SpotifyPlusLog.w(T + "no aft template in destination map (size=${map.size})"); return
        }
        val more = moreClass?.let { mc -> map.values.firstOrNull { it != null && it.javaClass.name == mc } }
        val ctor = aft.declaredConstructors.firstOrNull { it.parameterTypes.size == 3 && !it.isSynthetic } ?: error("aft ctor")
        ctor.isAccessible = true
        val fa = field(aft, "a")!!; val fb = field(aft, "b")!!; val fc = field(aft, "c")!!
        val fKinds = field(aft, "d")!!; val fPerm = field(aft, "e")!!; val fIcon = field(aft, "f")!!
        for ((n, kind) in Kind.values().withIndex()) {
            val id = ids[n]
            if (map.containsKey(id)) { SpotifyPlusLog.w(T + "id ${idValue(xn31, id)} already mapped — skipping ${kind.name}"); continue }
            val d = ctor.newInstance(fa.get(template), fb.get(template), fc.get(template))
            // widen the supported card kinds (all but link/message) so image + story-image + video cards show the entries
            broadKinds(fKinds.type, fKinds.get(template))?.let { fKinds.set(d, it) }
            // no runtime-permission prompt for our actions
            runCatching { fPerm.set(d, fPerm.type.getDeclaredConstructor().newInstance()) }
                .onFailure { SpotifyPlusLog.w(T + "could not clear permission set", it) }
            if (kind == Kind.SHARE && more != null) {
                runCatching { fIcon.set(d, field(more.javaClass, "d")!!.get(more)) }
                    .onFailure { SpotifyPlusLog.w(T + "share icon copy failed", it) }
            }
            items[d] = Item(kind, d, id)
            map[id] = d
        }
        SpotifyPlusLog.i(T + "destination map injected via ${Thread.currentThread().stackTrace.getOrNull(3)?.methodName} (size=${map.size}, template=${aft.name}, more=${more?.javaClass?.name})")
    }

    /** Build a kinds set (same host set type) without LINK/MESSAGE via the host's varargs set factory; null → keep template's set. */
    private fun broadKinds(fieldType: Class<*>, templateKinds: Any?): Any? = runCatching {
        val enumCls = (templateKinds as Iterable<*>).first()!!.javaClass
        val values = enumCls.enumConstants!!.filter { (it as Enum<*>).name !in setOf("LINK", "MESSAGE") }
        val arr = java.lang.reflect.Array.newInstance(enumCls, values.size)
        values.forEachIndexed { i, v -> java.lang.reflect.Array.set(arr, i, v) }
        val r = (setFactory ?: error("host set factory not resolved")).invoke(null, arr)
        if (fieldType.isInstance(r)) r else null
    }.onFailure { SpotifyPlusLog.w(T + "broad kinds unavailable, using template kinds", it) }.getOrNull()

    private fun trackActivities(app: Application) {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(a: Activity) { topActivity = WeakReference(a) }
            override fun onActivityCreated(a: Activity, b: Bundle?) {}
            override fun onActivityStarted(a: Activity) {}
            override fun onActivityPaused(a: Activity) {}
            override fun onActivityStopped(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })
    }

    // ---- click handling -------------------------------------------------------------------------------------------

    private fun onClick(kind: Kind, ao31: Any?) {
        val ctx = SpotifyHost.context() ?: return
        val data = ao31?.let { field(it.javaClass, "a")?.get(it) }
        SpotifyPlusLog.i(T + "click ${kind.name}; card data=${data?.javaClass?.name}")
        // one action at a time: a second tap while an image is being produced is ignored (never two outputs)
        if (!busy.compareAndSet(false, true)) {
            SpotifyPlusLog.i(T + "click ${kind.name} ignored: previous action still running")
            SpotifyHost.toast("SpotifyPlus：正在生成图片，请稍候…"); return
        }
        SpotifyHost.io.execute {
            val t0 = System.currentTimeMillis()
            try {
                val src = pickSource(ctx, data)
                if (src == null) { SpotifyHost.toast("SpotifyPlus：当前卡片不是可保存的图片"); return@execute }
                // the same composed file is inserted into MediaStore once; save + share of one card reuse that entry
                val u = exportToMediaStore(ctx, src, reuse = true)
                when (kind) {
                    Kind.SAVE -> {
                        SpotifyPlusLog.i(T + "saved $src -> $u in ${System.currentTimeMillis() - t0} ms")
                        SpotifyHost.toast("已保存当前分享卡片到 图片/Spotify")
                    }
                    Kind.SHARE -> {
                        SpotifyPlusLog.i(T + "share chooser for $u (${System.currentTimeMillis() - t0} ms)")
                        SpotifyHost.main.post { runCatching { startChooser(ctx, u) }.onFailure { t ->
                            SpotifyPlusLog.w(T + "startChooser failed", t); SpotifyHost.toast("无法打开分享面板（${t.javaClass.simpleName}）") } }
                    }
                }
            } catch (t: StoryPending) {
            } catch (t: Throwable) {
                SpotifyPlusLog.w(T + "${kind.name} failed", t)
                SpotifyHost.toast("SpotifyPlus：${if (kind == Kind.SAVE) "保存" else "分享"}失败（${t.javaClass.simpleName}: ${t.message}）")
            } finally { busy.set(false) }
        }
    }

    private val busy = java.util.concurrent.atomic.AtomicBoolean(false)
    private val storyCache = ConcurrentHashMap<String, File>()
    /** In-flight composes per story key: a render that outlives the wait finishes into [storyCache] (no output of its own). */
    private val renders = ConcurrentHashMap<String, java.util.concurrent.Future<File?>>()

    /**
     * Image of the *selected* card. Story share data (gradient / image / video background + transparent sticker) is
     * flattened like the story apps do it ([StoryCard]): background from Spotify's local image cache when remote
     * (download ≤ 6 s otherwise, then #1F1F1F) — the output always contains the sticker. Anything else (or a story
     * without a drawable sticker): scan the share-data object (depth ≤ 3) for Uris / path strings (old behaviour).
     */
    private fun pickSource(ctx: Context, data: Any?): Uri? {
        val story = runCatching { StoryCard.parse(data) }.onFailure { SpotifyPlusLog.w(T + "story parse failed", it) }.getOrNull()
        SpotifyPlusLog.i(T + "card data class=${data?.javaClass?.name} super=${data?.javaClass?.superclass?.name} story=${story != null}")
        if (story != null) {
            SpotifyPlusLog.i(T + "story card: bg=${story.bg} sticker=${story.sticker}")
            storyCache[story.key]?.takeIf { it.exists() && it.length() > 0 }?.let { SpotifyPlusLog.i(T + "story cache hit ${it.name}"); return Uri.fromFile(it) }
            if (story.sticker != null) {
                val job = renders.computeIfAbsent(story.key) { k ->
                    renderPool.submit(java.util.concurrent.Callable {
                        try { StoryCard.render(ctx, story)?.also { if (!it.name.contains(StoryCard.NO_BG_SUFFIX)) storyCache[k] = it } } finally { renders.remove(k) }
                    })
                }
                val f = try { job.get(20_000, java.util.concurrent.TimeUnit.MILLISECONDS) }
                catch (t: java.util.concurrent.TimeoutException) {
                    // not cancelled: it keeps running and fills the cache, but emits nothing (exactly one output per tap)
                    SpotifyPlusLog.w(T + "story render still running after 20000 ms — nothing exported this time")
                    SpotifyHost.toast("SpotifyPlus：图片仍在生成，请稍后再试"); throw StoryPending()
                } catch (t: Throwable) { SpotifyPlusLog.w(T + "story render failed", t); null }
                if (f != null) {
                    if (f.name.contains(StoryCard.NO_BG_SUFFIX)) SpotifyHost.toast("SpotifyPlus：背景图未能及时加载，已用纯色背景")
                    return Uri.fromFile(f)
                }
                SpotifyPlusLog.w(T + "story render failed (sticker not drawable) — previous behaviour")
            } else {
                // no sticker: the background is the whole card; local copies only, bounded download
                (story.bg as? StoryCard.Bg.Image)?.ref?.let { ref ->
                    val u = if (StoryCard.isRemote(ref)) StoryCard.localize(ctx, ref)?.let { Uri.fromFile(it) } else toUri(ref)
                    if (u != null && readable(ctx, u)) { SpotifyPlusLog.i(T + "story without sticker: background image $u"); return u }
                }
            }
            SpotifyPlusLog.w(T + "story fallback: previous behaviour (scan card data)")
        }
        val found = LinkedHashSet<String>()
        if (data != null) collect(data, 0, found, Collections.newSetFromMap(IdentityHashMap()))
        SpotifyPlusLog.i(T + "candidates from card data: $found")
        for (s in found) {
            if (ShareMenuLogic.looksLikeVideoRef(s)) { SpotifyPlusLog.i(T + "skip video ref $s"); continue }
            if (StoryCard.isRemote(s)) { SpotifyPlusLog.i(T + "skip remote ref $s"); continue }
            val u = toUri(s)
            if (readable(ctx, u)) return u
        }
        // fallback: newest registered card (old behaviour)
        val f = ShareRegistry.latestFile()
        if (f != null) { SpotifyPlusLog.i(T + "fallback to newest registered card ${f.name}"); return Uri.fromFile(f) }
        return null
    }

    /** Render outlived the wait; the user was told — no export, no error toast. */
    private class StoryPending : RuntimeException("pending")

    private val renderPool = java.util.concurrent.Executors.newCachedThreadPool { r -> Thread(r, "SpotifyPlus-card").apply { isDaemon = true } }

    private fun toUri(s: String): Uri = if (s.startsWith("/")) Uri.fromFile(File(s)) else Uri.parse(s)

    private fun readable(ctx: Context, u: Uri): Boolean = runCatching {
        if (u.scheme == "file") File(u.path!!).let { it.exists() && it.length() > 0 }
        else ctx.contentResolver.openInputStream(u)?.use { true } ?: false
    }.getOrDefault(false)

    private fun collect(o: Any, depth: Int, out: MutableSet<String>, seen: MutableSet<Any>) {
        if (depth > 3 || !seen.add(o)) return
        if (o is Uri) { out += o.toString(); return }
        if (o is String) { if (ShareMenuLogic.looksLikeImageRef(o) || ShareMenuLogic.looksLikeVideoRef(o)) out += o; return }
        val c = o.javaClass
        if (!c.name.startsWith("p.") && !c.name.startsWith("com.spotify.")) return
        generateSequence(c) { it.superclass }.takeWhile { it != Any::class.java }.forEach { k ->
            k.declaredFields.filter { !Modifier.isStatic(it.modifiers) && !it.type.isPrimitive }.forEach { f ->
                runCatching { f.isAccessible = true; f.get(o) }.getOrNull()?.let { v ->
                    if (v !is Map<*, *> && v !is Collection<*>) collect(v, depth + 1, out, seen)
                }
            }
        }
    }

    private fun exportToMediaStore(ctx: Context, src: Uri, reuse: Boolean): Uri {
        if (src.authority == "media" ) return src
        if (reuse) exportCache[src.toString()]?.let { c ->
            if (runCatching { ctx.contentResolver.openInputStream(c)?.use { true } ?: false }.getOrDefault(false)) return c
            exportCache.remove(src.toString())
        }
        val mime = runCatching { ctx.contentResolver.getType(src) }.getOrNull()
            ?.takeIf { it.startsWith("image/") } ?: SpotifyUris.mimeForExt(src.path?.substringAfterLast('.', "png") ?: "png")
        val ext = when { mime.contains("jpeg") -> "jpg"; mime.contains("webp") -> "webp"; else -> "png" }
        val input = (if (src.scheme == "file") File(src.path!!).inputStream() else ctx.contentResolver.openInputStream(src))
            ?: error("openInputStream null")
        val out = input.use { MediaSaver.save(ctx, it, "ShareCard_${System.currentTimeMillis()}.$ext", mime, MediaSaver.Kind.IMAGE) }
        exportCache[src.toString()] = out
        return out
    }

    private fun startChooser(appCtx: Context, uri: Uri) {
        val mime = runCatching { appCtx.contentResolver.getType(uri) }.getOrNull()?.takeIf { it.startsWith("image/") } ?: "image/*"
        val send = Intent(Intent.ACTION_SEND).setType(mime)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newRawUri("card", uri)
        val chooser = Intent.createChooser(send, "分享图片").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        chooser.clipData = ClipData.newRawUri("card", uri)
        val act = topActivity.get()?.takeIf { !it.isFinishing && !it.isDestroyed }
        if (act != null) { act.startActivity(chooser); SpotifyPlusLog.i(T + "chooser started from ${act.javaClass.name}") }
        else { chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); appCtx.startActivity(chooser); SpotifyPlusLog.i(T + "chooser started from app context (no activity)") }
    }
}
