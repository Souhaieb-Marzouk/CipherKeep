# Keep JNA callback/structure metadata referenced by generated UniFFI code.
-keep class com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.** { *; }
