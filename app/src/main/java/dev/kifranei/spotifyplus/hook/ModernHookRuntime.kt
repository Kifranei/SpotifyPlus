package dev.kifranei.spotifyplus.hook

import android.util.Log
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Executable

/**
 * Thin libxposed-102 bridge so existing hooks keep a before/after callback shape
 * without depending on legacy `XposedBridge` / `XC_MethodHook`.
 */
object ModernHookRuntime {
    private const val TAG = "SpotifyPlus"

    @Volatile
    private var module: XposedModule? = null

    fun attach(m: XposedModule) {
        module = m
    }

    fun active(): XposedModule? = module

    fun log(message: String, error: Throwable? = null) {
        val m = module
        if (m != null) {
            if (error == null) m.log(Log.INFO, TAG, message)
            else m.log(Log.ERROR, TAG, message, error)
        } else if (error == null) {
            Log.i(TAG, message)
        } else {
            Log.e(TAG, message, error)
        }
    }

    abstract class MethodHook {
        open fun beforeHookedMethod(param: MethodHookParam) = Unit
        open fun afterHookedMethod(param: MethodHookParam) = Unit
    }

    class MethodHookParam(
        val method: Executable,
        val thisObject: Any?,
        val args: Array<Any?>,
    ) {
        private var resultValue: Any? = null
        private var early = false

        var result: Any?
            get() = resultValue
            set(value) {
                resultValue = value
                throwable = null
                early = true
            }

        var throwable: Throwable? = null
            set(value) {
                field = value
                if (value != null) early = true
            }

        fun shouldReturnEarly(): Boolean = early

        internal fun setInvocationResult(value: Any?) {
            resultValue = value
        }
    }

    fun hookMethod(executable: Executable, callback: MethodHook): Boolean {
        val m = module ?: error("ModernHookRuntime not attached")
        m.hook(executable).intercept { chain ->
            val args = chain.args.toTypedArray()
            val param = MethodHookParam(executable, chain.thisObject, args)
            callback.beforeHookedMethod(param)
            if (!param.shouldReturnEarly()) {
                try {
                    param.setInvocationResult(chain.proceed(param.args))
                } catch (t: Throwable) {
                    param.throwable = t
                }
            }
            callback.afterHookedMethod(param)
            param.throwable?.let { throw it }
            param.result
        }
        return true
    }

    fun hookAllMethods(type: Class<*>, name: String, callback: MethodHook): Int {
        val methods = (type.declaredMethods.toList() + type.methods.toList())
            .filter { it.name == name }
            .distinctBy { "${it.declaringClass.name}#${it.name}:${it.parameterTypes.joinToString { p -> p.name }}" }
        methods.forEach {
            runCatching { it.isAccessible = true }
            hookMethod(it, callback)
        }
        return methods.size
    }

    fun hookAllConstructors(type: Class<*>, callback: MethodHook): Int {
        val ctors = type.declaredConstructors.toList()
        ctors.forEach {
            runCatching { it.isAccessible = true }
            hookMethod(it, callback)
        }
        return ctors.size
    }

    fun findClass(name: String, cl: ClassLoader): Class<*> =
        Class.forName(name, false, cl)

    fun findClassIfExists(name: String, cl: ClassLoader): Class<*>? =
        runCatching { findClass(name, cl) }.getOrNull()
}
