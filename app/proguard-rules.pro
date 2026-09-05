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