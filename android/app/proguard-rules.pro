# Shell-only build. Add keep rules here when R8 is enabled for release.
# JNA loads its native dispatcher and callback classes reflectively.
-keep class com.sun.jna.** { *; }
-keep interface com.sun.jna.** { *; }
-keep class * extends com.sun.jna.** { *; }
-dontwarn java.awt.**
# Generated UniFFI/JNA bindings are accessed through reflection and JNI.
-keep class dev.cipherkeep.core.** { *; }
