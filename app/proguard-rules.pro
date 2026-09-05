# Keep kotlinx.serialization generated serializers
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class com.nanokvm.app.data.api.** {
    *** Companion;
}
-keepclasseswithmembers class com.nanokvm.app.data.api.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.nanokvm.app.data.api.**$$serializer

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**

# WebRTC SDK (reflective native glue)
-keep class org.webrtc.** { *; }
-dontwarn org.webrtc.**
# R8 shrink 会在 arm64 真机导致 WebRTC 库加载崩溃(JNI_OnLoad SIGTRAP)。
# 已验证组合:保留 minify 流程,但关闭 shrink/obfuscate/optimize。
-dontobfuscate
-dontoptimize
-dontshrink
