# Modern libxposed entry (API 102)
-dontwarn io.github.libxposed.annotation.**
-dontwarn io.github.libxposed.api.**
-adaptresourcefilecontents META-INF/xposed/java_init.list
-keep,allowoptimization,allowobfuscation public class * extends io.github.libxposed.api.XposedModule {
    public <init>();
}
-keep class dev.kifranei.spotifyplus.hook.HookEntry { *; }
-keep class dev.kifranei.spotifyplus.hook.** { *; }
-keep class dev.kifranei.spotifyplus.** { *; }
-keep class org.luckypray.dexkit.** { *; }
-keep class io.github.proify.lyricon.** { *; }
-keep class io.github.libxposed.service.** { *; }
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod,RuntimeVisibleAnnotations
-keep class kotlin.Unit { *; }
-keep class kotlin.Metadata { *; }
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers class * { @kotlinx.serialization.Serializable <fields>; }
-dontwarn de.robv.android.xposed.**
-dontwarn okhttp3.internal.platform.**
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
