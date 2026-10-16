package dev.kifranei.spotifyplus.hook

import android.content.res.Resources
import android.widget.TextView
import dev.kifranei.spotifyplus.util.SpotifyPlusLog

/**
 * Shows Spotify's explicit badge text (zh-CN 「儿童不宜」) as a short "E".
 *
 * Static anchors (9.1.90.2270 resources.arsc): `string/explicit_badge_text_short` (0x7f130fe2, en "E"),
 * `string/explicit_badge_text_long` (0x7f130fe1, en "EXPLICIT") and `string/explicit_badge_content_description`
 * (0x7f130fe0, en "Explicit"); in `zh` all three are "儿童不宜". Ids are resolved by NAME at runtime
 * (getIdentifier), so they survive id renumbering. The accessibility description (fe0) is left untouched.
 * Layers: (1) Resources.getString/getText by id (Compose stringResource + XML), (2) TextView.setText fallback
 * for text exactly equal to the localized badge string. Not verified on device.
 */
object ExplicitLabelHook {
    const val E = "E"
    private val NAMES = listOf("explicit_badge_text_long", "explicit_badge_text_short")
    @Volatile private var ids: Set<Int> = emptySet()
    @Volatile private var texts: Set<String> = emptySet()
    @Volatile private var installed = false

    /** Pure (unit-tested): should this displayed text become "E"? */
    fun shouldReplace(text: CharSequence?, badgeTexts: Set<String>): Boolean {
        val t = text?.toString()?.trim() ?: return false
        return t.isNotEmpty() && t in badgeTexts
    }

    fun install() {
        if (installed) return
        installed = true
        val getStr = object : ModernHookRuntime.MethodHook() {
            override fun beforeHookedMethod(p: ModernHookRuntime.MethodHookParam) {
                val id = p.args.firstOrNull() as? Int ?: return
                if (!ensure(p.thisObject as? Resources)) return
                if (id in ids) p.result = E
            }
        }
        ModernHookRuntime.hookAllMethods(Resources::class.java, "getString", getStr)
        ModernHookRuntime.hookAllMethods(Resources::class.java, "getText", getStr)
        ModernHookRuntime.hookAllMethods(TextView::class.java, "setText", object : ModernHookRuntime.MethodHook() {
            override fun beforeHookedMethod(p: ModernHookRuntime.MethodHookParam) {
                val a = p.args.firstOrNull() as? CharSequence ?: return
                val tv = p.thisObject as? TextView ?: return
                if (texts.isEmpty()) ensure(tv.resources)
                if (shouldReplace(a, texts)) p.args[0] = E
            }
        })
        SpotifyPlusLog.i("ExplicitLabelHook installed")
    }

    private fun ensure(r: Resources?): Boolean {
        if (ids.isNotEmpty()) return true
        r ?: return false
        val found = NAMES.map { r.getIdentifier(it, "string", SpotifyHost.PACKAGE) }.filter { it != 0 }.toSet()
        if (found.isEmpty()) return false
        ids = found
        // Read raw strings via an unhooked path: the hook returns early only for ids, so temporarily compute from config.
        texts = found.mapNotNull { runCatching { rawString(r, it) }.getOrNull() }.filter { it != E }.toSet()
        return true
    }

    private fun rawString(r: Resources, id: Int): String? {
        // getResourceName is unhooked; value lookup goes through TypedValue to bypass getString/getText hooks.
        val tv = android.util.TypedValue()
        r.getValue(id, tv, true)
        return tv.string?.toString()
    }
}
