# Keep JS bridge methods reachable (called reflectively by WebView).
-keepclassmembers class app.hushtube.android.** {
    @android.webkit.JavascriptInterface <methods>;
}

# Components the system instantiates by name (belt and braces; AGP keeps these by default).
-keep class app.hushtube.android.PlaybackService { *; }
-keep class app.hushtube.android.PlaybackReceiver { *; }

