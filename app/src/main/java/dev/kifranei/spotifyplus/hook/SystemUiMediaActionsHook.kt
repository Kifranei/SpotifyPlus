package dev.kifranei.spotifyplus.hook

import android.util.Log
import java.lang.reflect.Modifier

/**
 * HyperOS SystemUI-side unblock for media-notification CustomActions.
 *
 * Xiaomi docs: HyperOS hides "abnormal" PlaybackState CustomActions and only shows them after
 * a per-app whitelist. In practice the denylist lives on
 * `NotificationSettingsManager.mHiddenCustomActionsList` (+ `…Local` on newer builds). App-side
 * [MediaNotifActionsHook] can publish well-formed CustomActions, but SystemUI still strips them
 * for packages on that list (Spotify is commonly included).
 *
 * Cleared before SystemUI builds actions from PlaybackState — same problem HyperCeiler /
 * WOMMO solve. Requires LSPosed scope `com.android.systemui`.
 */
object SystemUiMediaActionsHook {
    const val SYSTEM_UI = "com.android.systemui"

    private const val TAG = "SpotifyPlus"

    private val MANAGER_CLASSES = listOf(
        "com.miui.systemui.notification.NotificationSettingsManager",
        "com.android.systemui.statusbar.notification.NotificationSettingsManager",
    )

    /** Class#method pairs that run while SystemUI materializes CustomActions from PlaybackState. */
    private val ACTION_PIPELINE_HOOKS = listOf(
        "com.android.systemui.media.controls.pipeline.MediaDataManager" to "createActionsFromState",
        "com.android.systemui.media.controls.domain.pipeline.LegacyMediaDataManagerImpl" to "createActionsFromState",
        "com.android.systemui.media.MediaDataManager" to "createActionsFromState",
        // Synthetic lambdas that filter CustomActions against the hidden list (HyperOS 2 / 3).
        "com.android.systemui.media.controls.domain.pipeline.LegacyMediaDataManagerImpl\$createActionsFromState\$customActions\$1" to "invoke",
        "com.android.systemui.media.controls.domain.pipeline.MediaActionsKt\$\$ExternalSyntheticLambda0" to "invoke",
        "com.android.systemui.media.controls.pipeline.MediaDataManager\$createActionsFromState\$customActions\$1" to "invoke",
    )

    private val HIDDEN_LIST_FIELDS = listOf(
        "mHiddenCustomActionsList",
        "mHiddenCustomActionsListLocal",
    )

    @Volatile private var installed = false

    fun install(classLoader: ClassLoader) {
        if (installed) return
        installed = true

        var hooked = 0
        for ((className, methodName) in ACTION_PIPELINE_HOOKS) {
            val cls = ModernHookRuntime.findClassIfExists(className, classLoader) ?: continue
            val n = ModernHookRuntime.hookAllMethods(
                cls,
                methodName,
                object : ModernHookRuntime.MethodHook() {
                    override fun beforeHookedMethod(p: ModernHookRuntime.MethodHookParam) {
                        clearHiddenLists(classLoader)
                    }
                },
            )
            if (n > 0) {
                hooked += n
                Log.i(TAG, "SystemUiMediaActionsHook hooked $className#$methodName ($n)")
            }
        }

        // Eager clear once manager is already constructed (cold path).
        clearHiddenLists(classLoader)

        // Fallback: any method on the manager that mentions "HiddenCustom" / "CustomAction".
        hooked += hookManagerCheckMethods(classLoader)

        Log.i(TAG, "SystemUiMediaActionsHook installed pipelineHooks=$hooked")
        ModernHookRuntime.log("SystemUiMediaActionsHook installed pipelineHooks=$hooked")
    }

    private fun hookManagerCheckMethods(classLoader: ClassLoader): Int {
        var n = 0
        for (name in MANAGER_CLASSES) {
            val cls = ModernHookRuntime.findClassIfExists(name, classLoader) ?: continue
            for (m in cls.declaredMethods) {
                val mn = m.name
                if (!mn.contains("CustomAction", ignoreCase = true) &&
                    !mn.contains("HiddenCustom", ignoreCase = true)
                ) {
                    continue
                }
                // Force "not hidden" / empty result for boolean and List returns.
                runCatching {
                    ModernHookRuntime.hookMethod(
                        m,
                        object : ModernHookRuntime.MethodHook() {
                            override fun beforeHookedMethod(p: ModernHookRuntime.MethodHookParam) {
                                clearHiddenLists(classLoader)
                                when {
                                    m.returnType == java.lang.Boolean.TYPE ||
                                        m.returnType == Boolean::class.java -> p.result = false
                                    List::class.java.isAssignableFrom(m.returnType) ->
                                        p.result = ArrayList<Any>()
                                }
                            }
                        },
                    )
                    n++
                    Log.i(TAG, "SystemUiMediaActionsHook hooked manager method $name#$mn")
                }
            }
        }
        return n
    }

    /** Best-effort: wipe HyperOS CustomAction denylist fields on the singleton manager. */
    fun clearHiddenLists(classLoader: ClassLoader) {
        val manager = resolveManager(classLoader) ?: return
        for (fieldName in HIDDEN_LIST_FIELDS) {
            runCatching {
                var c: Class<*>? = manager.javaClass
                var field: java.lang.reflect.Field? = null
                while (c != null && field == null) {
                    field = runCatching { c!!.getDeclaredField(fieldName) }.getOrNull()
                    c = c.superclass
                }
                if (field == null) return@runCatching
                field.isAccessible = true
                val cur = field.get(manager)
                when (cur) {
                    is MutableList<*> -> cur.clear()
                    is List<*> -> field.set(manager, ArrayList<Any>())
                    null -> field.set(manager, ArrayList<Any>())
                    else -> field.set(manager, ArrayList<Any>())
                }
            }
        }
    }

    private fun resolveManager(classLoader: ClassLoader): Any? {
        for (name in MANAGER_CLASSES) {
            val cls = ModernHookRuntime.findClassIfExists(name, classLoader) ?: continue
            // sINSTANCE static field (MIUI / HyperOS)
            runCatching {
                val f = cls.getDeclaredField("sINSTANCE")
                f.isAccessible = true
                val v = f.get(null)
                if (v != null) return v
            }
            // static getInstance()
            for (m in cls.declaredMethods) {
                if (Modifier.isStatic(m.modifiers) &&
                    m.parameterTypes.isEmpty() &&
                    (m.name == "getInstance" || m.name == "get") &&
                    cls.isAssignableFrom(m.returnType)
                ) {
                    runCatching {
                        m.isAccessible = true
                        val v = m.invoke(null)
                        if (v != null) return v
                    }
                }
            }
        }
        // Dependency.get(NotificationSettingsManager.class)
        val dep = ModernHookRuntime.findClassIfExists("com.android.systemui.Dependency", classLoader)
        if (dep != null) {
            for (name in MANAGER_CLASSES) {
                val mgrCls = ModernHookRuntime.findClassIfExists(name, classLoader) ?: continue
                runCatching {
                    val get = dep.methods.firstOrNull {
                        it.name == "get" && it.parameterTypes.size == 1 &&
                            it.parameterTypes[0] == Class::class.java
                    } ?: return@runCatching
                    get.isAccessible = true
                    val v = get.invoke(null, mgrCls)
                    if (v != null) return v
                }
            }
        }
        return null
    }
}
