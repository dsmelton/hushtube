package app.hushtube.android

import android.annotation.SuppressLint
import androidx.webkit.WebViewFeature
import androidx.webkit.WebSettingsCompat
import android.app.AlertDialog
import android.app.DownloadManager
import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.os.Environment
import android.text.InputType
import android.util.Rational
import android.webkit.URLUtil
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.webkit.WebViewCompat
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : AppCompatActivity(), PlaybackService.BgHandler {

    private lateinit var root: FrameLayout
    private lateinit var webView: WebView
    private lateinit var errorView: LinearLayout
    private lateinit var prefs: SharedPreferences

    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null

    // system-bar insets (top, left, right, bottom) in px, pushed into the page
    // as CSS vars so fixed/sticky UI can dodge the status & gesture bars.
    private var lastInsets = IntArray(4)

    // ---- mini player / picture-in-picture ----
    private lateinit var mini: MiniPlayer
    /** Latest state of the video on the current watch page, reported by ui/ivx.js. */
    private data class PageState(val id: String, val title: String, val author: String,
                                 val t: Double, val playing: Boolean, val at: Long)
    @Volatile private var pageState: PageState? = null
    /** Was anything actually playing when onPause paused the WebView? Screen-off fires
     *  onPause -> the page's pause-report races onStop's hand-off read of pageState and
     *  usually wins, so the hand-off saw "not playing" and killed the audio (v1.26 T6 race). */
    @Volatile private var prePausePlaying = false
    @Volatile private var miniCss = 0          // CSS px the page must keep free at the bottom
    private var pipFromPage = false           // PiP started from the page's own video
    private var webPaused = false
    // Expand hand-back: while set, the mini keeps playing (window hidden) until the page
    // video of this id actually plays — no silent gap while the watch page loads (v1.26).
    @Volatile private var expandingId: String? = null
    private val pipSupported by lazy {
        packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
    }

    private val serverUrl: String
        get() = prefs.getString(PREF_SERVER, DEFAULT_SERVER) ?: DEFAULT_SERVER

    private val serverHost: String?
        get() = Uri.parse(serverUrl).host

    private val castBridgeBase: String
        get() = (prefs.getString(PREF_CAST, DEFAULT_CAST_BRIDGE)
            ?: DEFAULT_CAST_BRIDGE).trimEnd('/')

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashReport.install(this)
        showPendingCrash()
        prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        PlaybackService.ui = this             // the media notification's Close button drives us

        root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)

        webView = WebView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                mediaPlaybackRequiresUserGesture = false
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                useWideViewPort = true
                loadWithOverviewMode = true
                setSupportZoom(true)
                builtInZoomControls = true
                displayZoomControls = false
            }
            setBackgroundColor(Color.parseColor("#0F0F0F"))   // = page background: no black flash between pages
            isVerticalScrollBarEnabled = false
        }
        enableBackForwardCache(webView.settings)

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView, request: WebResourceRequest
            ): Boolean {
                val url = request.url
                val scheme = url.scheme ?: return false
                val host = url.host
                // Stay inside the WebView only for our own server over http(s).
                if (scheme == "http" || scheme == "https") {
                    if (host != null && host == serverHost) return false
                }
                openExternally(url)
                return true
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                pageState = null
                mini.dropShadow()                      // navigated away from the watch page
                updatePipParams()
                // opening any video (incl. expanding the mini player) replaces the mini player —
                // EXCEPT during an expand hand-back: then the mini keeps playing (audio gap-free)
                // until the page video actually plays (see expandingId / report()).
                if (Uri.parse(url ?: "").path?.startsWith("/watch") == true && mini.isActive &&
                    !isInPictureInPictureModeCompat() && expandingId == null) mini.close()
            }

            override fun onPageFinished(view: WebView, url: String?) {
                // Invidious sets SID as a SESSION cookie (no expiry) -> it dies with the
                // WebView process, so logins did not survive app restarts. Re-write it as a
                // persistent cookie (30 days = Invidious' own server-side session lifetime)
                // and flush the store to disk.
                if (url == null || Uri.parse(url).host != serverHost) return
                val cm = CookieManager.getInstance()
                val sid = cm.getCookie(url)
                    .orEmpty().split(";").map { it.trim() }
                    .firstOrNull { it.startsWith("SID=") && it.length > 4 }
                    ?.substringAfter("=") ?: return
                val secure = if (url.startsWith("https")) "; Secure" else ""
                cm.setCookie(url, "SID=$sid; max-age=2592000; path=/$secure")
                cm.flush()
            }

            override fun onReceivedError(
                view: WebView, request: WebResourceRequest, error: WebResourceError
            ) {
                if (request.isForMainFrame) showErrorScreen()
            }

            override fun onReceivedHttpError(
                view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse
            ) {
                if (request.isForMainFrame) showErrorScreen()
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                runOnUiThread { enterFullscreenVideo(view, callback) }
            }

            override fun onHideCustomView() {
                runOnUiThread { exitFullscreenVideo() }
            }
        }

        webView.setDownloadListener(DownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            try {
                val req = DownloadManager.Request(Uri.parse(url)).apply {
                    setMimeType(mimeType)
                    addRequestHeader("User-Agent", userAgent)
                    setNotificationVisibility(
                        DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                    )
                    setDestinationInExternalPublicDir(
                        Environment.DIRECTORY_DOWNLOADS,
                        DownloadStore.FOLDER + "/" + URLUtil.guessFileName(url, contentDisposition, mimeType)
                    )
                }
                (getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
                toast("Download started")
            } catch (e: Exception) {
                toast("Download failed: ${e.message}")
            }
        })

        webView.addJavascriptInterface(CastBridge(), "InvidiousCast")
        webView.addJavascriptInterface(InsetsBridge(), "InvidiousInsets")
        webView.addJavascriptInterface(ScreenBridge(), "InvidiousScreen")
        webView.addJavascriptInterface(PlayerBridge(), "InvidiousPlayer")

        val injectJs = try {
            assets.open("inject.js").bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            ""
        }
        if (injectJs.isNotEmpty()) {
            WebViewCompat.addDocumentStartJavaScript(webView, injectJs, setOf("*"))
        }

        errorView = buildErrorView()

        root.addView(webView)
        root.addView(errorView)
        mini = MiniPlayer(this, root,
            onExpand = { item, posMs ->
                val url = "${serverUrl.trimEnd('/')}/watch?v=${item.id}&t=${posMs / 1000}"
                // window vanishes the same frame (no stale mini over the incoming page), but the
                // player keeps playing until the page video takes over — gap-free expand (v1.26)
                mini.hideNow()
                expandingId = item.id
                root.postDelayed({
                    if (expandingId == item.id) { expandingId = null; if (mini.isActive) mini.close() }
                }, 4000)
                // watch-page skeleton first (same frame), then load
                webView.evaluateJavascript("try{window.ivxGo&&ivxGo(${JSONObject.quote(url)})}catch(e){}") {
                    webView.loadUrl(url)
                }
            },
            onChanged = { onMiniChanged() })
        setContentView(root)
        root.addOnLayoutChangeListener { _, l, _, r, _, ol, _, or_, _ -> if (r - l != or_ - ol) updateMiniInsets() }

        // Push system-bar insets into the page as CSS vars (--iv-sb/-l/-r/-b).
        // targetSdk 35 forces edge-to-edge, so the WebView sits under the
        // status/gesture bars; the page UI must dodge them itself.
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val sb = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                    WindowInsetsCompat.Type.displayCutout()
            )
            // Insets are PHYSICAL pixels; the page works in CSS px (= dp with
            // width=device-width). Convert, rounding up so no sliver is left
            // uncovered. (v1.2–v1.4 passed raw px -> ~2.6x too big on the Fold.)
            val d = resources.displayMetrics.density
            fun css(px: Int) = kotlin.math.ceil(px / d).toInt()
            lastInsets = intArrayOf(css(sb.top), css(sb.left), css(sb.right), css(sb.bottom))
            pushInsetsToPage()
            updateMiniInsets()
            insets
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    customView != null -> exitFullscreenVideo()
                    webView.canGoBack() -> {
                        // paint the previous screen's skeleton right away (ivx.js decides: skips it when
                        // back only closes a sheet, delays it so instant bfcache restores never flash)
                        val list = webView.copyBackForwardList()
                        val prev = list.getItemAtIndex(list.currentIndex - 1)?.url
                        if (prev != null) webView.evaluateJavascript(
                            "try{window.ivxGo&&ivxGo(${JSONObject.quote(prev)},true)}catch(e){}", null)
                        webView.goBack()
                    }
                    else -> finish()
                }
            }
        })

        webView.loadUrl(intent?.getStringExtra(EXTRA_URL)?.takeIf { Uri.parse(it).host == serverHost } ?: serverUrl)
        DownloadStore.addListener(dlListener)
        val app = applicationContext
        Thread { DownloadStore.refresh(app) }.start()     // moves pre-1.20 downloads into Download/HushTube/
        UpdateChecker.check(this, BuildConfig.VERSION_CODE, VERSION_URL)
    }

    // ---- Fullscreen video (WebChromeClient custom view) ----

    private fun enterFullscreenVideo(view: View, callback: WebChromeClient.CustomViewCallback) {
        if (customView != null) {
            callback.onCustomViewHidden()
            return
        }
        customView = view
        customViewCallback = callback
        webView.visibility = View.GONE
        errorView.visibility = View.GONE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        view.setBackgroundColor(Color.BLACK)
        root.addView(
            view,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        hideSystemUi()
    }

    private fun exitFullscreenVideo() {
        val view = customView ?: return
        root.removeView(view)
        customView = null
        customViewCallback?.onCustomViewHidden()
        customViewCallback = null
        webView.visibility = View.VISIBLE
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED   // undo fullscreen landscape
        showSystemUi()
    }

    private fun hideSystemUi() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, root).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun showSystemUi() {
        // stay edge-to-edge (the page dodges the bars via --iv-* insets); `true` here
        // used to flip the app out of edge-to-edge after the first fullscreen exit
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, root).show(WindowInsetsCompat.Type.systemBars())
    }

    /** Fold/unfold = configuration change on the same activity (configChanges). The display
     *  switch can bring the system bars back while a video is fullscreen -> hide them again
     *  (and once more after the switch has settled). The landscape lock stays as requested. */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (customView != null && !isInPictureInPictureModeCompat()) {
            hideSystemUi()
            root.postDelayed({ if (customView != null) hideSystemUi() }, 400)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && customView != null && !isInPictureInPictureModeCompat()) hideSystemUi()
    }

    // ---- Tailscale-down error screen ----

    private fun buildErrorView(): LinearLayout {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.BLACK)
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
            visibility = View.GONE
        }
        layout.addView(TextView(this).apply {
            text = "Can't reach the server\n${Uri.parse(serverUrl).host}"
            setTextColor(Color.WHITE)
            textSize = 18f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 48)
        })
        layout.addView(TextView(this).apply {
            text = "Is Tailscale connected (or the laptop on)?"
            setTextColor(Color.LTGRAY)
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 32)
        })
        layout.addView(makeButton("Go to downloads") { DownloadsActivity.start(this) })   // YouTube: offline → downloads
        layout.addView(makeButton("Open Tailscale") { openTailscale() })
        layout.addView(makeButton("Retry") {
            errorView.visibility = View.GONE
            webView.visibility = View.VISIBLE
            webView.reload()
        })
        return layout
    }

    private fun makeButton(label: String, onClick: () -> Unit) =
        androidx.appcompat.widget.AppCompatButton(this).apply {
            text = label
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 24 }
        }

    private fun showErrorScreen() {
        runOnUiThread {
            errorView.visibility = View.VISIBLE
            webView.visibility = View.GONE
        }
    }

    private fun openTailscale() {
        val launch = packageManager.getLaunchIntentForPackage(TAILSCALE_PKG)
        if (launch != null) {
            startActivity(launch)
        } else {
            openExternally(
                Uri.parse("https://play.google.com/store/apps/details?id=$TAILSCALE_PKG")
            )
        }
    }

    // ---- Menu: server switch + reload ----

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(Menu.NONE, MENU_SERVER, Menu.NONE, "Change server")
        menu.add(Menu.NONE, MENU_CAST, Menu.NONE, "Change cast bridge")
        menu.add(Menu.NONE, MENU_RELOAD, Menu.NONE, "Reload")
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            MENU_SERVER -> { promptServer(); true }
            MENU_CAST -> { promptCast(); true }
            MENU_RELOAD -> { webView.reload(); true }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun promptCast() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(prefs.getString(PREF_CAST, DEFAULT_CAST_BRIDGE))
            setSelection(text.length)
        }
        val pad = (24 * resources.displayMetrics.density).toInt()
        val container = FrameLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle("Cast bridge URL")
            .setView(container)
            .setPositiveButton("Save") { _, _ ->
                var url = input.text.toString().trim()
                if (url.isNotEmpty()) {
                    if (!url.startsWith("http://") && !url.startsWith("https://")) {
                        url = "http://$url"
                    }
                    prefs.edit().putString(PREF_CAST, url.trimEnd('/')).apply()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun promptServer() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(serverUrl)
            setSelection(text.length)
        }
        val pad = (24 * resources.displayMetrics.density).toInt()
        val container = FrameLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle("Server URL")
            .setView(container)
            .setPositiveButton("Save") { _, _ ->
                var url = input.text.toString().trim()
                if (url.isNotEmpty()) {
                    if (!url.startsWith("http://") && !url.startsWith("https://")) {
                        url = "https://$url"
                    }
                    prefs.edit().putString(PREF_SERVER, url).apply()
                    errorView.visibility = View.GONE
                    webView.visibility = View.VISIBLE
                    webView.loadUrl(url)
                }
            }
            .setNeutralButton("ts.net") { _, _ ->
                prefs.edit().putString(PREF_SERVER, DEFAULT_SERVER).apply()
                webView.loadUrl(DEFAULT_SERVER)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * No adb on the phone, so the trace has to reach the user: a crash is shown in a copyable
     * dialog (and stored in files/crash.log) instead of vanishing into logcat.
     */
    private fun showPendingCrash() {
        val txt = CrashReport.read(this) ?: return
        try {
            AlertDialog.Builder(this)
                .setTitle("App crashed — tap Copy, then send this to the maintainer")
                .setMessage(txt.take(3000))
                .setPositiveButton("Copy") { _, _ ->
                    try {
                        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("crash", txt))
                        android.widget.Toast.makeText(this, "Crash text copied", android.widget.Toast.LENGTH_LONG).show()
                    } catch (_: Exception) { }
                }
                .setNegativeButton("Close", null)
                .show()
        } catch (_: Exception) { }
    }

    // ---- Lifecycle ----

    override fun onPause() {
        super.onPause()
        CookieManager.getInstance().flush()   // don't lose a fresh login to a process kill
        // capture BEFORE anything pauses: the page's pause-report races onStop's hand-off,
        // and this is the only trustworthy "was playing" snapshot (v1.26 T6)
        prePausePlaying = (pageState?.playing == true) || mini.isPlaying
        // entering picture-in-picture also pauses the activity: keep the WebView alive then
        // (we still need to pause its page video from JS); onStop pauses it for real
        if (!isInPictureInPictureModeCompat()) pauseWeb()
    }

    override fun onStop() {
        super.onStop()
        if (isInPictureInPictureModeCompat()) return
        pauseWeb()
        // PiP was just dismissed -> the user closed the floating window: stop, don't resume audio
        if (wasInPip) { wasInPip = false; mini.pause(); mini.pauseShadow(); return }
        // home/back already handed off in onUserLeaveHint; this covers leaving via another app
        if (bgAudio && mini.bgAudio) handOffToBackgroundAudio()
        else { mini.pause(); mini.pauseShadow() }
    }

    /**
     * Minimized / screen off with audio wanted: the WebView video can't play here, so the native
     * player takes over (the muted shadow is already buffered -> no gap) and the mediaPlayback
     * foreground service keeps the process and the audio alive.
     */
    private fun handOffToBackgroundAudio() {
        // runs from lifecycle callbacks: nothing in here may be able to kill the app
        try {
            askNotifPermission()
            val ps = pageState
            if (mini.isActive) {
                if (!mini.isPlaying) mini.resumePlayback() else mini.notifyBg()
                return
            }
            // ps.playing may already be the false written by OUR onPause pause (the T6 race);
            // prePausePlaying is the pre-pause truth.
            if (ps != null && (ps.playing || prePausePlaying)) startMini(ps, visibleBar = false, forcePlay = true)
            else { mini.pause(); mini.pauseShadow() }
        } catch (e: Exception) {
            android.util.Log.w("ivx", "background-audio hand-off failed", e)
        }
    }

    /** The media notification's Close button (transport controls act on the player directly). */
    override fun bgStop() {
        if (::mini.isInitialized) mini.close()
        webView.evaluateJavascript("try{document.querySelectorAll('video').forEach(function(v){v.pause()})}catch(e){}", null)
    }

    private var notifAsked = false
    private fun askNotifPermission() {
        if (notifAsked || Build.VERSION.SDK_INT < 33) return
        notifAsked = true
        val granted = checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!granted) {
            try { requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 42) }
            catch (_: Exception) { }
        }
    }

    /** "Play audio when minimized" (default on); the mini player shows the same switch. */
    private var bgAudio: Boolean
        get() = prefs.getBoolean("bgAudio", true)
        set(v) { prefs.edit().putBoolean("bgAudio", v).apply() }

    override fun onResume() {
        super.onResume()
        if (webPaused) { webView.onResume(); webView.resumeTimers(); webPaused = false }
        // back in the foreground: if the background-audio hand-off is still running and the
        // WebView is on the same watch page, give playback BACK to the page player —
        // otherwise the mini floats over the watch page with the page video paused (v1.26 T5).
        if (::mini.isInitialized) {
            if (!handBackToPage()) { mini.revealIfHidden(); mini.notifyBg() }
        }
        dlListener()
    }

    /** Reverse hand-off: mini (or its service) owns playback, the page is showing the same
     *  video → seek the page video to the mini position, play it, close mini + notification.
     *  Returns true when the hand-back happened (mini no longer active). */
    private fun handBackToPage(): Boolean {
        if (!mini.isActive || isInPictureInPictureModeCompat()) return false
        val item = mini.item ?: return false
        val u = webView.url?.let { Uri.parse(it) } ?: return false
        if (u.path?.startsWith("/watch") != true) return false
        if (u.getQueryParameter("v") != item.id) return false
        val pos = mini.positionMs() / 1000.0
        val play = mini.isPlaying
        mini.close()
        webView.evaluateJavascript(
            "(function(){var v=document.querySelector('video');if(v){v.muted=false;v.currentTime=$pos;" +
                (if (play) "v.play();" else "") + "}})()", null)
        return true
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // "Watch online" from the Downloads screen / offline player
        val url = intent.getStringExtra(EXTRA_URL) ?: return
        if (Uri.parse(url).host != serverHost) return
        errorView.visibility = View.GONE
        webView.visibility = View.VISIBLE
        webView.evaluateJavascript("try{window.ivxGo&&ivxGo(${JSONObject.quote(url)})}catch(e){}") { webView.loadUrl(url) }
    }

    /** Download progress/state → the page (Download pill on the watch page, You tab row). */
    private val dlListener: () -> Unit = {
        if (::webView.isInitialized && !webPaused) {
            val js = "try{window.__ivxDlPush&&__ivxDlPush(${JSONObject.quote(DownloadStore.allStatesJson(this))})}catch(e){}"
            webView.evaluateJavascript(js, null)
        }
    }

    /** Opening the native Downloads screen / offline player: stop the page video + mini player
     *  first and make sure launching our own activity can't auto-enter PiP. */
    private fun launchOffline(go: () -> Unit) {
        pageState = null
        if (::mini.isInitialized) { mini.pause(); mini.pauseShadow() }
        webView.evaluateJavascript("try{document.querySelectorAll('video').forEach(function(v){v.pause()})}catch(e){}", null)
        if (pipSupported) try { setPictureInPictureParams(pipParams()) } catch (_: Exception) {}
        go()
    }

    override fun onDestroy() {
        PlaybackService.ui = null
        DownloadStore.removeListener(dlListener)
        if (::mini.isInitialized) mini.close()
        super.onDestroy()
    }

    private fun pauseWeb() {
        if (webPaused) return
        webView.onPause(); webView.pauseTimers(); webPaused = true
    }

    // ---- Mini player + picture-in-picture (YouTube app behaviour) ----

    private fun isInPictureInPictureModeCompat() = isInPictureInPictureMode

    /** PiP dismissal lands in onStop with the window already gone; remember we were there. */
    private var wasInPip = false

    private fun streamUrl(id: String) =
        "${serverUrl.trimEnd('/')}/latest_version?id=$id&itag=18&local=true"

    private fun posMs(ps: PageState): Long {
        val elapsed = if (ps.playing) (SystemClock.elapsedRealtime() - ps.at) / 1000.0 else 0.0
        return ((ps.t + elapsed).coerceAtLeast(0.0) * 1000).toLong()
    }

    private fun startMini(ps: PageState, visibleBar: Boolean, forcePlay: Boolean = false) {
        val item = MiniPlayer.Item(ps.id, ps.title, ps.author)
        val play = ps.playing || forcePlay
        // zero-gap: prefer the shadow player that has been buffering this stream muted
        if (!mini.adoptShadow(item, posMs(ps), play, visibleBar, thumbUrl(ps.id)))
            mini.show(item, streamUrl(ps.id), thumbUrl(ps.id), posMs(ps), play, visibleBar)
    }

    private fun thumbUrl(id: String) =
        "${serverUrl.trimEnd('/')}/vi/$id/mqdefault.jpg"

    /** Back-forward cache: going back restores the previous page instantly (scroll position,
     *  For You grid, loaded comments) instead of re-requesting it. Experimental in androidx.webkit;
     *  only enabled when the installed WebView supports it. */
    @androidx.annotation.OptIn(markerClass = [WebSettingsCompat.ExperimentalBackForwardCache::class])
    @SuppressLint("RequiresFeature")
    private fun enableBackForwardCache(s: WebSettings) {
        try {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.BACK_FORWARD_CACHE))
                WebSettingsCompat.setBackForwardCacheEnabled(s, true)
        } catch (_: Throwable) { }
    }

    /** After minimizing: go back to the last page that isn't a video (YouTube returns to where you were). */
    private fun leaveWatchPage() {
        val list = webView.copyBackForwardList()
        var i = list.currentIndex - 1
        while (i >= 0 && Uri.parse(list.getItemAtIndex(i).url).path?.startsWith("/watch") == true) i--
        if (i >= 0) webView.goBackOrForward(i - list.currentIndex)
        else webView.loadUrl("${serverUrl.trimEnd('/')}/feed/trending")
    }

    private fun onMiniChanged() {
        miniCss = mini.cssHeight()
        webView.evaluateJavascript(
            "try{document.documentElement.style.setProperty('--ivx-mini','${miniCss}px')}catch(e){}", null)
        updateMiniInsets()
        updatePipParams()
    }

    /** Floating area for the mini window: below the page's top bar, above its bottom tab
     *  bar (phones: page < 768 CSS px wide), inside the side insets. */
    private fun updateMiniInsets() {
        if (!::mini.isInitialized) return
        val d = resources.displayMetrics.density
        val cssW = root.width / d
        val tabBar = if (cssW > 0 && cssW < 1000) PAGE_TAB_BAR_CSS else 0   // touch layout up to 999 css px (ivx v15)
        mini.setInsets(((PAGE_TOP_BAR_CSS + lastInsets[0]) * d).toInt(), ((tabBar + lastInsets[3]) * d).toInt(),
            (lastInsets[1] * d).toInt(), (lastInsets[2] * d).toInt())
    }

    /** Leaving the app goes to PiP when something is playing: the mini player, or the
     *  watch page's video (inline or fullscreen).
     *  With background audio ON the hand-off replaces PiP (v1.23 design) — auto-enter must be
     *  DISARMED, otherwise a failed/raced hand-off lets Android auto-enter PiP and scale the
     *  whole activity (page + mini) into the PiP window: the "tiny window inside PiP" bug (v1.26 T4). */
    private fun pipEligible(): Boolean =
        !(bgAudio && ::mini.isInitialized && mini.bgAudio) &&
            ((mini.isActive && mini.isPlaying) || pageState?.playing == true)

    private fun pipParams(): PictureInPictureParams {
        val b = PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9))
        if (Build.VERSION.SDK_INT >= 31) {
            b.setAutoEnterEnabled(pipEligible())
            b.setSeamlessResizeEnabled(false)
        }
        return b.build()
    }

    private fun updatePipParams() {
        if (!pipSupported || !::mini.isInitialized) return
        runOnUiThread { try { setPictureInPictureParams(pipParams()) } catch (_: Exception) {} }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // "keep listening" wins over the floating window: with background audio on we hand the
        // stream to the native player + notification instead of PiP (PiP dies when the screen sleeps).
        // Hand off HERE, not only in onStop: Android 14 restricts starting a mediaPlayback
        // foreground service from the background, and this is the last moment we are guaranteed
        // to still be foreground. It also makes the page->native switch gap-free.
        // NOTE: deliberately NOT gated on pipEligible() — that is false while bg-audio is on
        // (T4 fix), but this hand-off is exactly what bg-audio needs (v1.26.2 regression fix).
        if (bgAudio && ::mini.isInitialized && mini.bgAudio &&
            ((mini.isActive && mini.isPlaying) || pageState?.playing == true)) { handOffToBackgroundAudio(); return }
        // Android 12+ auto-enters via setAutoEnterEnabled; older versions need this
        if (Build.VERSION.SDK_INT < 31 && pipSupported && pipEligible()) {
            try { enterPictureInPictureMode(pipParams()) } catch (_: Exception) {}
        }
    }

    override fun onPictureInPictureModeChanged(isInPip: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPip, newConfig)
        wasInPip = isInPip
        if (isInPip) {
            val ps = pageState
            pipFromPage = !mini.isActive && ps != null
            if (pipFromPage && ps != null) {
                // hand the page's video over to the native player (the WebView can't render in PiP nicely)
                startMini(ps, visibleBar = false)
                webView.evaluateJavascript("document.querySelectorAll('video').forEach(function(v){v.pause()})", null)
            }
            if (mini.isActive) {
                mini.setPipLayout(true)
                webView.visibility = View.INVISIBLE
                customView?.visibility = View.INVISIBLE
            }
        } else {
            val dismissed = lifecycle.currentState == Lifecycle.State.CREATED
            mini.setPipLayout(false)
            webView.visibility = View.VISIBLE
            customView?.visibility = View.VISIBLE
            if (pipFromPage) {
                // back to the page's own player at the PiP position
                val pos = mini.positionMs() / 1000.0
                val play = mini.isPlaying && !dismissed
                mini.close()
                webView.evaluateJavascript(
                    "(function(){var v=document.querySelector('video');if(v){v.currentTime=$pos;" +
                        (if (play) "v.play();" else "") + "}})()", null)
            } else if (dismissed) {
                mini.close()                // closing the PiP window stops playback (YouTube)
            }
            pipFromPage = false
            if (dismissed) pauseWeb()
            onMiniChanged()
        }
    }

    // ---- Helpers ----

    private fun openExternally(url: Uri) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, url))
        } catch (e: Exception) {
            toast("No app for $url")
        }
    }

    private fun toast(msg: String) {
        runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
    }

    private fun pushInsetsToPage() {
        val js = "try{var s=document.documentElement.style;" +
            "s.setProperty('--iv-sb','${lastInsets[0]}px');" +
            "s.setProperty('--iv-l','${lastInsets[1]}px');" +
            "s.setProperty('--iv-r','${lastInsets[2]}px');" +
            "s.setProperty('--iv-b','${lastInsets[3]}px');}catch(e){}"
        webView.evaluateJavascript(js, null)
    }

    /** Exposed to page JS (ui/ivx.js) as window.InvidiousScreen: fullscreen orientation,
     *  like the YouTube app (landscape videos go landscape, back to free rotation on exit). */
    inner class ScreenBridge {
        @JavascriptInterface
        fun setOrientation(mode: String) {
            runOnUiThread {
                requestedOrientation = when (mode) {
                    // page only asks for these while entering fullscreen; exitFullscreenVideo()
                    // resets to UNSPECIFIED natively, so no stale lock can survive
                    "landscape" -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                    "portrait" -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                    else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                }
            }
        }
    }

    /** Exposed to page JS (ui/ivx.js) as window.InvidiousPlayer. */
    inner class PlayerBridge {
        private val idRe = Regex("^[A-Za-z0-9_-]{11}$")

        private fun parse(json: String): PageState? = try {
            val o = JSONObject(json)
            val id = o.optString("id")
            if (!idRe.matches(id)) null else PageState(
                id, o.optString("title").take(300), o.optString("author").take(120),
                o.optDouble("t", 0.0).takeIf { !it.isNaN() } ?: 0.0, o.optBoolean("playing"),
                SystemClock.elapsedRealtime())
        } catch (_: Exception) { null }

        /** Page video state (play/pause/seek, ~every 2 s while playing). Doubles as the
         *  trigger that keeps the muted shadow player warm for a zero-gap swipe-down. */
        @JavascriptInterface
        fun report(json: String) {
            val ps = parse(json)
            pageState = ps
            updatePipParams()
            // expand hand-back done: the page video of the expanding id is playing -> retire the mini
            if (expandingId != null && ps != null && ps.playing && ps.id == expandingId) {
                expandingId = null
                runOnUiThread { if (mini.isActive) mini.close() }
            }
            if (ps != null && !mini.isActive && !isInPictureInPictureModeCompat()) {
                val item = MiniPlayer.Item(ps.id, ps.title, ps.author)
                val src = streamUrl(ps.id)
                val pos = posMs(ps)
                runOnUiThread { mini.warm(item, src, pos, ps.playing, thumbUrl(ps.id)) }
            }
        }

        /** Share button / ⋮ → Share (ivx.js v15): the system share sheet, like the
         *  YouTube app. WebView has no navigator.share. Only http(s) URLs. */
        @JavascriptInterface
        fun share(json: String) {
            val o = try { JSONObject(json) } catch (_: Exception) { return }
            val url = o.optString("url")
            if (!url.startsWith("https://") && !url.startsWith("http://")) return
            val title = o.optString("title").take(300)
            runOnUiThread {
                try {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, url)
                        if (title.isNotBlank()) putExtra(Intent.EXTRA_SUBJECT, title)
                    }
                    startActivity(Intent.createChooser(send, null))
                } catch (_: Exception) { }
            }
        }

        /** "Download as:" widget / quality sheet: ivx.js intercepts the form and hands us the
         *  POST (URL + body + title + id + channel + length). v1.20: saved into the app's own
         *  folder Download/HushTube/ as "Title (videoId).ext" by DownloadStore, with live
         *  progress (pushed back to the page) and offline metadata + thumbnail. */
        @JavascriptInterface
        fun download(json: String) {
            val o = try { JSONObject(json) } catch (_: Exception) { return }
            val url = o.optString("url")
            if (Uri.parse(url).host != serverHost) return
            val id = o.optString("id")
            if (!idRe.matches(id)) return
            val req = DownloadStore.Request(
                url = url, body = o.optString("body"), cookie = CookieManager.getInstance().getCookie(url),
                id = id, title = o.optString("title").ifBlank { "video" }.take(300),
                author = o.optString("author").take(120), authorId = o.optString("authorId").take(40),
                lengthSec = o.optInt("lengthSeconds", 0), thumbUrl = thumbUrl(id))
            when (DownloadStore.start(this@MainActivity, req) { _, msg -> toast(msg) }) {
                DownloadStore.StartResult.Started -> toast("Downloading\u2026")
                DownloadStore.StartResult.AlreadyRunning -> toast("Already downloading")
                DownloadStore.StartResult.AlreadyDone -> toast("Already in Downloads")
            }
        }

        /** {"s":"none"|"dl"|"done","p":pct} — drives the watch page's Download pill. */
        @JavascriptInterface
        fun dlState(id: String): String = DownloadStore.stateJson(this@MainActivity, id)

        /** {"count","bytes","active"} — the You tab's Downloads row. */
        @JavascriptInterface
        fun downloadsInfo(): String = DownloadStore.summaryJson(this@MainActivity)

        @JavascriptInterface
        fun openDownloads() { runOnUiThread { launchOffline { DownloadsActivity.start(this@MainActivity) } } }

        @JavascriptInterface
        fun playDownload(id: String) {
            if (!idRe.matches(id)) return
            runOnUiThread { launchOffline { OfflinePlayerActivity.start(this@MainActivity, id) } }
        }

        @JavascriptInterface
        fun cancelDownload(id: String) { DownloadStore.cancel(id) }

        @JavascriptInterface
        fun deleteDownload(id: String) {
            if (!idRe.matches(id)) return
            val app = applicationContext
            Thread { DownloadStore.delete(app, id) }.start()
            toast("Deleted from downloads")
        }

        /** Swipe down on the page's video: keep playing in the in-app mini player and go back
         *  to the page you came from (e.g. search results) — the app stays in the foreground.
         *  (System PiP only happens when leaving the app.)
         *  v1.14: navigate back IMMEDIATELY (v1.13 waited for the mini to start playing and
         *  that read as a hang on the watch page). The mini covers any buffering with the
         *  video's thumbnail + a spinner; the shadow player usually makes it instant anyway. */
        @JavascriptInterface
        fun minimize(json: String) {
            val ps = parse(json) ?: return
            runOnUiThread {
                pageState = null
                startMini(ps, visibleBar = true)
                leaveWatchPage()
            }
        }

        @JavascriptInterface
        fun miniHeight(): Int = miniCss
    }

    /** Exposed to injected JS as window.InvidiousInsets. */
    inner class InsetsBridge {
        @JavascriptInterface
        fun get(): String = JSONObject()
            .put("t", lastInsets[0])
            .put("l", lastInsets[1])
            .put("r", lastInsets[2])
            .put("b", lastInsets[3])
            .toString()
    }

    /** Exposed to injected JS as window.InvidiousCast.
     *  All calls are async: results come back via window.__ivxCast.on*(...) so the
     *  WebView never blocks. Native HTTP (not page fetch) because the page is
     *  https and the bridge is plain http on the LAN (mixed content + CORS). */
    inner class CastBridge {
        private val idRe = Regex("^[A-Za-z0-9_-]{11}$")

        private fun call(method: String, path: String, body: String?, readTimeoutMs: Int): Pair<Int, String> {
            val conn = URL(castBridgeBase + path).openConnection() as HttpURLConnection
            try {
                conn.requestMethod = method
                conn.connectTimeout = 5000
                conn.readTimeout = readTimeoutMs
                if (body != null) {
                    conn.doOutput = true
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.outputStream.use { it.write(body.toByteArray()) }
                }
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
                return code to text
            } finally {
                conn.disconnect()
            }
        }

        private fun reply(fn: String, payload: JSONObject) {
            val js = "window.__ivxCast&&window.__ivxCast.$fn(${JSONObject.quote(payload.toString())})"
            runOnUiThread { webView.evaluateJavascript(js, null) }
        }

        @JavascriptInterface
        fun devices() {
            Thread {
                val out = JSONObject()
                try {
                    val (code, text) = call("GET", "/cast/devices", null, 15000)
                    if (code in 200..299) {
                        out.put("ok", true).put("devices", JSONObject(text).optJSONArray("devices"))
                    } else {
                        out.put("ok", false).put("error", "Bridge error $code")
                    }
                } catch (e: Exception) {
                    out.put("ok", false).put("error", "Can't reach the cast bridge on the laptop (${e.javaClass.simpleName})")
                }
                reply("onDevices", out)
            }.start()
        }

        @JavascriptInterface
        fun castTo(videoId: String, device: String) = castAt(videoId, device, 0)

        /** v1.21: + start position (seconds). The bridge opens SmartTube (ad-free) when the TV is
         *  set up for it, else the YouTube receiver. */
        @JavascriptInterface
        fun castAt(videoId: String, device: String, t: Int) {
            if (!idRe.matches(videoId)) return
            Thread {
                val out = JSONObject().put("device", device)
                try {
                    val body = JSONObject().put("videoId", videoId).put("mode", "youtube")
                        .put("device", device).put("t", t.coerceAtLeast(0)).toString()
                    val (code, text) = call("POST", "/cast/play", body, 70000)
                    if (code in 200..299) out.put("ok", true)
                    else out.put("ok", false).put("error", text.ifBlank { "Bridge error $code" }.take(160))
                } catch (e: Exception) {
                    out.put("ok", false).put("error", "Can't reach the cast bridge (${e.javaClass.simpleName})")
                }
                reply("onCastResult", out)
            }.start()
        }

        @JavascriptInterface
        fun stop() {
            Thread {
                val out = JSONObject()
                try {
                    val (code, text) = call("POST", "/cast/control", """{"action":"quit"}""", 20000)
                    if (code in 200..299) out.put("ok", true)
                    else out.put("ok", false).put("error", text.take(160))
                } catch (e: Exception) {
                    out.put("ok", false).put("error", e.javaClass.simpleName)
                }
                reply("onStopResult", out)
            }.start()
        }

        /** v1.0–v1.3 API: cast to the default device, toast the result. */
        @JavascriptInterface
        fun cast(videoId: String) {
            if (!idRe.matches(videoId)) return
            Thread {
                try {
                    val (code, _) = call("POST", "/cast/play", """{"videoId":"$videoId","mode":"youtube"}""", 70000)
                    toast(if (code in 200..299) "Casting to TV…" else "Cast bridge error ($code)")
                } catch (e: Exception) {
                    toast("Cast bridge unreachable: ${e.message}")
                }
            }.start()
        }
    }

    companion object {
        const val PREF_SERVER = "server_url"
        const val PREF_CAST = "cast_bridge_url"
        const val EXTRA_URL = "url"
        const val DEFAULT_SERVER = "https://<MACHINE>.<YOUR-TAILNET>.ts.net"
        // LAN IP of the server laptop; phone can't resolve the bare hostname. Editable in menu.
        const val DEFAULT_CAST_BRIDGE = "http://<LAPTOP_LAN_IP>:8300"
        const val TAILSCALE_PKG = "com.tailscale.ipn"
        const val VERSION_URL = "https://<MACHINE>.<YOUR-TAILNET>.ts.net/version.json"
        const val MENU_SERVER = 1
        const val MENU_RELOAD = 2
        const val MENU_CAST = 3
        const val PAGE_TOP_BAR_CSS = 56   // page top bar height (footer CSS --ivx-top)
        const val PAGE_TAB_BAR_CSS = 49   // YouTube-style bottom tab bar: 48 + 1px border (footer CSS v15)
    }
}
