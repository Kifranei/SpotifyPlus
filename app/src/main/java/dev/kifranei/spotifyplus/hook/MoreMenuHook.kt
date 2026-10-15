package dev.kifranei.spotifyplus.hook

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import dev.kifranei.spotifyplus.util.ModulePrefs
import dev.kifranei.spotifyplus.util.SpotifyPlusLog
import java.lang.reflect.Constructor
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy

/**
 * Injects "下载 Canvas / 下载艺人封面 / 保存分享卡片" rows into the host's context ("more") menu.
 *
 * Mechanism (static analysis, 9.1.90.2270): the Compose context menu is driven by an immutable view-model
 * `ContextMenuViewModel(header, List<ContextMenuItem>, boolean)` (obfuscated `p.yal`). Its constructor is
 * found by the unique string "ContextMenuViewModel cannot contain items with duplicate itemResId". We hook the
 * constructor, and append **clones of an existing item** (same icon/action scaffolding) with our own
 * itemResId, title and click callback. Item class `p.v8l` fields (in order): itemResId:String, icon:ydw?,
 * icon2:s8l?, titleRes:Integer?, titleText:String?, accessibilityRes:Integer?, enabled:Boolean, badge:l251?,
 * action:u8l(ah40, int, t8l, e520 onClick). Exactly one of titleRes/titleText and one of icon/icon2 must be set.
 *
 * Track menus are recognised by the stable itemResId strings the host uses ("artist_browse", "album_browse",
 * "add_to_playlist", "list_add_to_queue"). **Untested on device** — the cloning relies on declared-field order.
 */
object MoreMenuHook {
    private const val ID_CANVAS = "spotifyplus_canvas"
    private const val ID_ARTIST = "spotifyplus_artist_cover"
    private const val ID_SHARE = "spotifyplus_share_card"
    private val TRACK_MENU_MARKERS = setOf("artist_browse", "album_browse", "add_to_playlist", "list_add_to_queue")

    @Volatile private var hooked = false

    fun install(cl: ClassLoader, menuVmClass: String?, prefs: ModulePrefs) {
        if (hooked) return
        // NB: p.yal has TWO 3-arg ctors: (v7l, List, boolean) and the synthetic (v7l, List, int). Pick by type.
        val ctor = DexResolver.loadCtor(cl, menuVmClass) {
            it.size == 3 && java.util.List::class.java.isAssignableFrom(it[1]) && it[2] == Boolean::class.javaPrimitiveType
        }
        if (ctor == null) {
            SpotifyPlusLog.w("MoreMenuHook: no (X, List, boolean) ctor on $menuVmClass — menu injection disabled")
            return
        }
        hooked = true
        XposedBridge.hookMethod(ctor, object : XC_MethodHook() {
            override fun beforeHookedMethod(p: MethodHookParam) {
                try {
                    @Suppress("UNCHECKED_CAST")
                    val items = p.args[1] as? List<Any?> ?: return
                    p.args[1] = inject(items, prefs) ?: return
                } catch (t: Throwable) {
                    SpotifyPlusLog.w("menu inject failed (menu left untouched)", t)
                }
            }
        })
        SpotifyPlusLog.i("MoreMenuHook hooked ${ctor.declaringClass.name}")
    }

    private data class Layout(val cls: Class<*>, val idF: java.lang.reflect.Field, val fields: List<java.lang.reflect.Field>, val ctor: Constructor<*>)

    private fun layoutOf(sample: Any): Layout? {
        val cls = sample.javaClass
        val fields = cls.declaredFields.filter { !java.lang.reflect.Modifier.isStatic(it.modifiers) }
        // p.v8l also has a synthetic 9-arg ctor (…, int); require !synthetic and ctor types == field types in order
        val ctor = cls.declaredConstructors.firstOrNull {
            !it.isSynthetic && it.parameterCount == 9 && it.parameterTypes.toList() == fields.map { f -> f.type }
        } ?: return null
        if (fields.size != 9) return null
        if (fields[0].type != String::class.java || fields[3].type != Integer::class.java || fields[4].type != String::class.java) return null
        fields.forEach { it.isAccessible = true }; ctor.isAccessible = true
        return Layout(cls, fields[0], fields, ctor)
    }

    private fun inject(items: List<Any?>, prefs: ModulePrefs): List<Any?>? {
        val nonNull = items.filterNotNull()
        if (nonNull.isEmpty()) return null
        val layout = layoutOf(nonNull[0]) ?: run { SpotifyPlusLog.w("menu item layout not recognised"); return null }
        val ids = nonNull.mapNotNull { runCatching { layout.idF.get(it) as? String }.getOrNull() }
        if (ids.none { it in TRACK_MENU_MARKERS } || ids.any { it.startsWith("spotifyplus_") }) return null

        // template: prefer a plain navigation-ish row whose icon is set
        val template = nonNull.firstOrNull { runCatching { layout.idF.get(it) as? String }.getOrNull() == "list_add_to_queue" }
            ?: nonNull.first()
        val extra = ArrayList<Any>()
        if (prefs.canvasDownload) clone(layout, template, ID_CANVAS, "下载 Canvas") { Actions.downloadCanvas() }?.let(extra::add)
        if (prefs.artistCoverDownload) clone(layout, template, ID_ARTIST, "下载艺人封面") { Actions.downloadArtistCover() }?.let(extra::add)
        if (prefs.shareCardEnhance) clone(layout, template, ID_SHARE, "保存最近的分享卡片") { Actions.saveLatestShareCard() }?.let(extra::add)
        if (extra.isEmpty()) return null
        return items + extra
    }

    private fun clone(l: Layout, t: Any, id: String, title: String, onClick: () -> Unit): Any? = try {
        val v = l.fields.map { it.get(t) }.toMutableList()
        v[0] = id
        v[3] = null           // titleRes
        v[4] = title          // titleText
        v[7] = null           // badge / indicator
        // action object: copy with replaced onClick (last ctor param is the e520 callback interface)
        val action = v[8] ?: return null
        val ac = action.javaClass
        val af = ac.declaredFields.filter { !java.lang.reflect.Modifier.isStatic(it.modifiers) }.onEach { it.isAccessible = true }
        val actor = ac.declaredConstructors.firstOrNull { !it.isSynthetic && it.parameterCount == af.size && it.parameterTypes.toList() == af.map { f -> f.type } } ?: return null
        actor.isAccessible = true
        val av = af.map { it.get(action) }.toMutableList()
        val cbIdx = af.indexOfLast { it.type.isInterface }
        if (cbIdx < 0) return null
        val iface = af[cbIdx].type
        val unit = runCatching { Class.forName("kotlin.Unit").getField("INSTANCE").get(null) }.getOrNull()
        av[cbIdx] = Proxy.newProxyInstance(iface.classLoader, arrayOf(iface), InvocationHandler { proxy, m, args ->
            when (m.name) {
                "equals" -> proxy === args?.getOrNull(0)
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "SpotifyPlus-$id"
                else -> { runCatching(onClick).onFailure { SpotifyPlusLog.w("menu action failed", it) }; if (m.returnType == Void.TYPE) null else unit }
            }
        })
        v[8] = actor.newInstance(*av.toTypedArray())
        l.ctor.newInstance(*v.toTypedArray())
    } catch (t: Throwable) {
        SpotifyPlusLog.w("clone menu item failed for $id", t); null
    }
}
