# Keep WebView JS interface methods if ever added (none used by default - intentional)
-keepattributes JavascriptInterface

# General Android hardening - don't keep debug info
-renamesourcefileattribute SourceFile
