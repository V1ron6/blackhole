# Keep WebView JS interface methods if ever added (none used by default - intentional)
-keepattributes JavascriptInterface

# General Android hardening - don't keep debug info
-renamesourcefileattribute SourceFile

# sshj (ByteBandit-mode SSH terminal) and its bouncycastle/slf4j dependencies
# use reflection and dynamic algorithm lookup that R8 can't trace statically.
-keep class net.schmizz.sshj.** { *; }
-keep class org.bouncycastle.** { *; }
-keep class org.slf4j.** { *; }
-dontwarn net.schmizz.sshj.**
-dontwarn org.bouncycastle.**
-dontwarn org.slf4j.**
