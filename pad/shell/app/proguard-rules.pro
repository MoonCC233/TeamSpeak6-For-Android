# The Pad shell is a thin WebView host. Keep the JavaScript bridge entry points
# even if the release build is ever minified.
-keepclassmembers class dev.tsdroid.pad.MainActivity$NativeBridge {
    @android.webkit.JavascriptInterface <methods>;
}
