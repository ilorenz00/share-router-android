# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class **$Companion {
    *** serializer(...);
}

# AppAuth
-keep class net.openid.appauth.** { *; }

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
