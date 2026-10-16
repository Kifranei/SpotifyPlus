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
 * Static findings, 9.1.90.2270 (all log lines use prefix `share-menu:`):
 *  - `p.xn31` = destination id (Parcelable singletons: `p.vm31`="copy-link", `p.hn31`="native-share-menu"/More,
 *    `p.xm31`="download-copy-link", `p.jn31`="qr-code", `p.tn31`="spotify-on-platform-users-nearby", …).
 *  - `p.uas(int columns, List<xn31>)` = ordered destination-id config of the sheet (built in `p.lg31.B` from `p.km31.a`).
 *  - `p.om31` = destination (label res `e()`, a11y `c()`, icon `d()`, supported kinds `b()`); `p.qm31` = in-app destination with
 *    `f(p.ao31, Continuation)` click handler; `p.aft` = Spotify's own "Download" destination (image/video, string anchors
 *    "Video file path is null" / "Unsupported share data type for download: ").
 *  - `p.tch0.o(xn31)` resolves id → destination from a lazy `LinkedHashMap` (`tch0.c`, built by `p.cwm0` case 0x10).
 *  - `p.ao31.a` (`p.yl31`) is the share data of the **currently selected carousel card** (`p.h660{Uri}` image,
 *    `p.xh81/yh81{tbs}` story-ish image, `p.zh81{ubs}` video); `ao31.b` is the link entity.
 *
 * Mechanism: build two extra `p.aft` instances (same ctor args as the real one → same icon/kinds scaffolding), key them in the
 * resolver map under two otherwise-unused existing id singletons (`jn31`, `tn31`), append those ids to the `uas` list right
 * after `copy-link`, override `a()/c()/e()` for our instances and replace `f()` with our click handler.
 * Labels are fake string-resource ids answered from a `Resources.getText` hook.
 */
object ShareMenuHook {
    private const val T = "share-menu: "
    private const val P_AFT = "p.aft"
    private const val P_MORE = "p.k6k0"
    private const val P_TCH0 = "p.tch0"
    private const val P_UAS = "p.uas"
    private const val P_UNIT = "p.aad1"
    private const val P_KIT = "p.s2j1"
    private const val P_CWM0 = "p.cwm0"
    private const val P_A041 = "p.a041"
    private const val P_FPX = "p.fpx"
    private val OUR_IDS = setOf("qr-code", "spotify-on-platform-users-nearby")
    private val logLookups = java.util.concurrent.atomic.AtomicInteger()
    private val ID_CLASSES = listOf("p.jn31" to "qr-code", "p.tn31" to "spotify-on-platform-users-nearby")
    private const val ID_COPY_LINK = "copy-link"

    private class Item(val kind: Kind, val dest: Any, val id: Any)
    private enum class Kind { SAVE, SHARE }

    @Volatile private var installed = false
    private val items = Collections.synchronizedMap(IdentityHashMap<Any, Item>())
    private val injectedMaps = Collections.synchronizedSet(Collections.newSetFromMap(IdentityHashMap<Any, Boolean>()))
    private val ids = ArrayList<Any>()
    private var unit: Any? = null
    @Volatile private var topActivity = WeakReference<Activity>(null)
    private val exportCache = ConcurrentHashMap<String, Uri>()

    fun install(cl: ClassLoader) {
        if (installed) return
        val aft = cl.tryLoad(P_AFT); val tch0 = cl.tryLoad(P_TCH0); val uas = cl.tryLoad(P_UAS)
        if (aft == null || tch0 == null || uas == null) {
            SpotifyPlusLog.w(T + "classes missing aft=$aft tch0=$tch0 uas=$uas — share-menu entries disabled"); return
        }
        val aMethod = runCatching { aft.getDeclaredMethod("a") }.getOrNull()
        val xn31 = aMethod?.returnType
        val om31 = aft.superclass?.superclass
        val oMethod = xn31?.let { x -> tch0.declaredMethods.firstOrNull { it.name == "o" && it.parameterTypes.toList() == listOf(x) && it.returnType == om31 } }
        val uasCtor = uas.declaredConstructors.firstOrNull { it.parameterTypes.size == 2 && it.parameterTypes[0] == Int::class.javaPrimitiveType && java.util.List::class.java.isAssignableFrom(it.parameterTypes[1]) }
        val fMethod = aft.declaredMethods.firstOrNull { it.name == "f" && it.parameterTypes.size == 2 && it.returnType == Any::class.java }
        val cMethod = runCatching { aft.getDeclaredMethod("c") }.getOrNull()
        val eMethod = runCatching { aft.getDeclaredMethod("e") }.getOrNull()
        if (xn31 == null || om31 == null || oMethod == null || uasCtor == null || fMethod == null || cMethod == null || eMethod == null ||
            aMethod.returnType != xn31) {
            SpotifyPlusLog.w(T + "structure mismatch xn31=$xn31 om31=$om31 o=$oMethod uas=$uasCtor f=$fMethod — share-menu entries disabled"); return
        }
        // our two id singletons, verified by their string value
        for ((cn, expect) in ID_CLASSES) {
            val c = cl.tryLoad(cn) ?: continue
            val inst = runCatching { c.getDeclaredField("d").also { it.isAccessible = true }.get(null) }.getOrNull() ?: continue
            val v = runCatching { xn31.getMethod("getValue").invoke(inst) as? String }.getOrNull()
            if (v == expect) ids += inst else SpotifyPlusLog.w(T + "id $cn value=$v != $expect, skipped")
        }
        if (ids.size < 2) { SpotifyPlusLog.w(T + "need 2 spare ids, got ${ids.size} — disabled"); return }
        unit = runCatching { cl.loadClass(P_UNIT).getField("a").get(null) }.getOrNull()?.takeIf { it.toString() == "kotlin.Unit" }
        installed = true

        // 1. resolver: inject our destinations into the id→destination map the first time it is queried
        ModernHookRuntime.hookMethod(oMethod, object : ModernHookRuntime.MethodHook() {
            override fun beforeHookedMethod(p: ModernHookRuntime.MethodHookParam) {
                runCatching { ensureInjected(cl, p.thisObject ?: return, aft, xn31) }
                    .onFailure { SpotifyPlusLog.w(T + "inject failed", it) }
                val id = p.args.getOrNull(0)?.let { idValue(xn31, it) }
                if (id != null && (id in OUR_IDS || logLookups.incrementAndGet() <= 40)) SpotifyPlusLog.i(T + "tch0.o lookup id=$id")
            }
        })
        // 1b. ROOT-CAUSE FIX (a): the host builds its destination list from `tch0.c` (lazy map).values the first time the sheet
        // opens (cached in yc60.a) — usually BEFORE any tch0.o() call. Inject at lazy creation (cwm0.invoke) instead.
        cl.tryLoad(P_CWM0)?.let { cwm ->
            val inv = runCatching { cwm.getDeclaredMethod("invoke") }.getOrNull()
            val fb = field(cwm, "b")
            if (inv != null && fb != null) ModernHookRuntime.hookMethod(inv, object : ModernHookRuntime.MethodHook() {
                override fun afterHookedMethod(p: ModernHookRuntime.MethodHookParam) {
                    val r = p.result as? MutableMap<*, *> ?: return
                    val owner = runCatching { fb.get(p.thisObject) }.getOrNull() ?: return
                    if (owner.javaClass != tch0) return
                    @Suppress("UNCHECKED_CAST")
                    runCatching { injectMap(cl, r as MutableMap<Any?, Any?>, aft, xn31) }
                        .onFailure { SpotifyPlusLog.w(T + "lazy-map inject failed", it) }
                }
            }) else SpotifyPlusLog.w(T + "cwm0.invoke/b missing — lazy-map injection disabled")
        }
        // 1c. ROOT-CAUSE FIX (b): per-format hidden-destination sets (p.a041.b, from share-format config) are subtracted from the
        // final list in fpx.i. Strip our two borrowed ids (qr-code / users-nearby are hidden for card/image formats).
        cl.tryLoad(P_A041)?.let { a041 ->
            val ctor = a041.declaredConstructors.firstOrNull { it.parameterTypes.size == 4 && java.util.Set::class.java.isAssignableFrom(it.parameterTypes[1]) }
            if (ctor != null) ModernHookRuntime.hookMethod(ctor, object : ModernHookRuntime.MethodHook() {
                override fun beforeHookedMethod(p: ModernHookRuntime.MethodHookParam) {
                    val set = p.args[1] as? Set<*> ?: return
                    val hit = set.filter { it != null && (it in ids || idValue(xn31, it) in OUR_IDS) }
                    if (hit.isEmpty()) return
                    p.args[1] = LinkedHashSet(set.filter { it !in hit })
                    SpotifyPlusLog.i(T + "a041 hidden-set ${set.mapNotNull { it?.let { x -> idValue(xn31, x) } }} -> stripped ${hit.map { idValue(xn31, it!!) }}")
                }
            }) else SpotifyPlusLog.w(T + "a041 ctor not found — hidden-set strip disabled")
        }
        // 1d. final list builder fpx.i(uas, List<rs31>, Continuation): diagnostics + guaranteed fallback insertion
        cl.tryLoad(P_FPX)?.let { fpx ->
            val m = fpx.declaredMethods.firstOrNull { it.name == "i" && it.parameterTypes.size == 3 && it.parameterTypes[0] == uas && java.util.List::class.java.isAssignableFrom(it.parameterTypes[1]) }
            if (m != null) ModernHookRuntime.hookMethod(m, object : ModernHookRuntime.MethodHook() {
                override fun afterHookedMethod(p: ModernHookRuntime.MethodHookParam) {
                    try {
                        val r = p.result as? List<*> ?: return  // suspended: result arrives later via continuation, hq80/lle0 logs below
                        val cfg = runCatching { (field(uas, "a")?.get(p.args[0]) as? List<*>)?.map { idValue(xn31, it!!) } }.getOrNull()
                        val got = r.map { d -> d?.let { runCatching { idValue(xn31, d.javaClass.getMethod("a").invoke(d)!!) }.getOrNull() } }
                        SpotifyPlusLog.i(T + "fpx.i final: config=$cfg rendered=$got")
                        if (got.none { it in OUR_IDS }) {
                            SpotifyPlusLog.w(T + "our entries NOT in final list -> fallback insert")
                            val mine = items.values.map { it.dest }.sortedBy { items[it]!!.kind.ordinal }
                            if (mine.isNotEmpty()) {
                                val out = ShareMenuLogic.insertAfter(r.filterNotNull(), mine) { d ->
                                    runCatching { idValue(xn31, d.javaClass.getMethod("a").invoke(d)!!) }.getOrNull() == ID_COPY_LINK }
                                p.result = out
                            }
                        }
                    } catch (t: Throwable) { SpotifyPlusLog.w(T + "fpx.i after failed", t) }
                }
            }) else SpotifyPlusLog.w(T + "fpx.i not found")
        }
        // 1e. hq80.a / yc21 filter diagnostics: the available-destination list handed to the sorter
        cl.tryLoad("p.lle0")?.let { lle ->
            lle.declaredMethods.firstOrNull { it.name == "h" && it.parameterTypes.size == 2 }?.let { h ->
                ModernHookRuntime.hookMethod(h, object : ModernHookRuntime.MethodHook() {
                    override fun afterHookedMethod(p: ModernHookRuntime.MethodHookParam) {
                        runCatching {
                            val avail = (p.args[0] as List<*>).map { d -> idValue(xn31, d!!.javaClass.getMethod("a").invoke(d)!!) }
                            val cfg = (p.args[1] as List<*>).map { idValue(xn31, it!!) }
                            val res = (p.result as List<*>).map { d -> idValue(xn31, d!!.javaClass.getMethod("a").invoke(d)!!) }
                            if (cfg.any { it in OUR_IDS } || avail.any { it in OUR_IDS })
                                SpotifyPlusLog.i(T + "lle0.h available=$avail config=$cfg -> kept=$res")
                        }
                    }
                })
            }
        }
        // 2. destination id list: put ours right after "copy-link"
        ModernHookRuntime.hookMethod(uasCtor, object : ModernHookRuntime.MethodHook() {
            override fun beforeHookedMethod(p: ModernHookRuntime.MethodHookParam) {
                try {
                    @Suppress("UNCHECKED_CAST") val list = p.args[1] as? List<Any?> ?: return
                    val nn = list.filterNotNull()
                    if (nn.isEmpty() || nn.any { it in ids } || nn.none { idValue(xn31, it) == ID_COPY_LINK }) return
                    val out = ShareMenuLogic.insertAfter(list.filterNotNull(), ids.toList()) { idValue(xn31, it) == ID_COPY_LINK }
                    p.args[1] = out
                    SpotifyPlusLog.i(T + "destination list ${list.size} -> ${out.size} (inserted after copy-link)")
                } catch (t: Throwable) { SpotifyPlusLog.w(T + "uas hook failed", t) }
            }
        })
        // 3. per-instance overrides of the cloned aft destinations
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
        })
        // 4. labels for the fake string-resource ids
        val rh = object : ModernHookRuntime.MethodHook() {
            override fun beforeHookedMethod(p: ModernHookRuntime.MethodHookParam) {
                val id = p.args.getOrNull(0) as? Int ?: return
                ShareMenuLogic.labelFor(id)?.let { p.result = it }
            }
        }
        ModernHookRuntime.hookAllMethods(Resources::class.java, "getText", rh)
        SpotifyHost.whenReady { app -> trackActivities(app) }
        SpotifyPlusLog.i(T + "hooked: tch0.o, cwm0.invoke(lazy map), a041.<init>(hidden ids), fpx.i, lle0.h, uas.<init>, aft.{a,c,e,f}, Resources.getText; ids=${ids.map { idValue(xn31, it) }}; unit=${unit != null}")
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
        val more = map.values.firstOrNull { it != null && it.javaClass.name == P_MORE }
        val ctor = aft.declaredConstructors.firstOrNull { it.parameterTypes.size == 3 && !it.isSynthetic } ?: error("aft ctor")
        ctor.isAccessible = true
        val fa = field(aft, "a")!!; val fb = field(aft, "b")!!; val fc = field(aft, "c")!!
        val fKinds = field(aft, "d")!!; val fPerm = field(aft, "e")!!; val fIcon = field(aft, "f")!!
        for ((n, kind) in Kind.values().withIndex()) {
            val id = ids[n]
            if (map.containsKey(id)) { SpotifyPlusLog.w(T + "id ${idValue(xn31, id)} already mapped — skipping ${kind.name}"); continue }
            val d = ctor.newInstance(fa.get(template), fb.get(template), fc.get(template))
            // widen the supported card kinds (all but link/message) so image + story-image + video cards show the entries
            broadKinds(cl, fKinds.type, fKinds.get(template))?.let { fKinds.set(d, it) }
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
    private fun broadKinds(cl: ClassLoader, fieldType: Class<*>, templateKinds: Any?): Any? = runCatching {
        val enumCls = (templateKinds as Iterable<*>).first()!!.javaClass
        val values = enumCls.enumConstants!!.filter { (it as Enum<*>).name !in setOf("LINK", "MESSAGE") }
        val arr = java.lang.reflect.Array.newInstance(enumCls, values.size)
        values.forEachIndexed { i, v -> java.lang.reflect.Array.set(arr, i, v) }
        val r = cl.loadClass(P_KIT).getMethod("o", Array<Any>::class.java).invoke(null, arr)
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
        SpotifyHost.io.execute {
            try {
                val src = pickSource(ctx, data)
                if (src == null) { SpotifyHost.toast("SpotifyPlus：当前卡片不是可保存的图片"); return@execute }
                when (kind) {
                    Kind.SAVE -> {
                        val u = exportToMediaStore(ctx, src, reuse = false)
                        SpotifyPlusLog.i(T + "saved $src -> $u")
                        SpotifyHost.toast("已保存当前分享卡片到 图片/Spotify")
                    }
                    Kind.SHARE -> {
                        val u = exportToMediaStore(ctx, src, reuse = true)
                        SpotifyPlusLog.i(T + "share chooser for $u")
                        SpotifyHost.main.post { runCatching { startChooser(ctx, u) }.onFailure { t ->
                            SpotifyPlusLog.w(T + "startChooser failed", t); SpotifyHost.toast("无法打开分享面板（${t.javaClass.simpleName}）") } }
                    }
                }
            } catch (t: Throwable) {
                SpotifyPlusLog.w(T + "${kind.name} failed", t)
                SpotifyHost.toast("SpotifyPlus：${if (kind == Kind.SAVE) "保存" else "分享"}失败（${t.javaClass.simpleName}: ${t.message}）")
            }
        }
    }

    /** Local image of the *selected* card: scan the share-data object (depth ≤ 3) for Uris / path strings. */
    private fun pickSource(ctx: Context, data: Any?): Uri? {
        val found = LinkedHashSet<String>()
        if (data != null) collect(data, 0, found, Collections.newSetFromMap(IdentityHashMap()))
        SpotifyPlusLog.i(T + "candidates from card data: $found")
        for (s in found) {
            if (ShareMenuLogic.looksLikeVideoRef(s)) { SpotifyPlusLog.i(T + "skip video ref $s"); continue }
            val u = toUri(s)
            if (readable(ctx, u)) return u
        }
        // fallback: newest registered card (old behaviour)
        val f = ShareRegistry.latestFile()
        if (f != null) { SpotifyPlusLog.i(T + "fallback to newest registered card ${f.name}"); return Uri.fromFile(f) }
        return null
    }

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
