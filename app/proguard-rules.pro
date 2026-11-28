# Tuner – R8 rules.
# Media3, Compose, Coil, WorkManager and TV Provider ship their own consumer rules.

# Keep line numbers so crash reports stay readable.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Background workers and receivers are created by name.
-keep class com.titanejstudios.tuner.tv.GuideRefreshWorker { <init>(...); }
-keep class com.titanejstudios.tuner.recording.** { *; }

# ZXing is only used for QR encoding.
-dontwarn com.google.zxing.**

# jcifs-ng (network drive): loads parts by name and references desktop-Java classes it never uses on Android.
-keep class jcifs.** { *; }
-dontwarn jcifs.**
-dontwarn org.bouncycastle.**
-dontwarn org.slf4j.**
-dontwarn javax.**

# WireGuard (built-in VPN): native code calls back into these by name.
-keep class com.wireguard.android.backend.** { *; }
-keep class com.wireguard.crypto.** { *; }
-keep class com.wireguard.config.** { *; }

# YouTube player: the web page inside calls back into these through a JavaScript bridge.
-keep class com.pierfrancescosoffritti.androidyoutubeplayer.** { *; }
-keepattributes JavascriptInterface
-keepclassmembers class * { @android.webkit.JavascriptInterface <methods>; }

# Live subtitles: JNA calls the downloaded Vosk engine through this interface, by name.
-keep class com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.** { public *; }
-keep interface com.titanejstudios.tuner.subtitles.VoskLib { *; }
-dontwarn java.awt.**

# Readable problem-log reports: keep Flicker's own class and method names, and line numbers.
-keepattributes SourceFile,LineNumberTable
-keepnames class com.titanejstudios.** { *; }
