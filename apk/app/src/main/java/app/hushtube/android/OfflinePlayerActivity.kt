package app.hushtube.android

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Rational
import android.view.Gravity
import android.view.MotionEvent
import android.view.OrientationEventListener
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.DefaultTimeBar
import androidx.media3.ui.PlayerView
import app.hushtube.android.DlUi.dp
import java.lang.ref.WeakReference
import kotlin.math.abs

/**
 * Plays downloaded videos offline, like the YouTube app's Downloads playback:
 * player on top (thumbnail until the first frame), title / channel / size, Share · Delete ·
 * Watch online pills, then the rest of your downloads (tap = play; autoplays to the next one).
 * Same gestures as the app's web player: tap = controls (instant), double-tap left/right =
 * -/+10 s (keep tapping = 20 s, 30 s…), press-and-hold = 2x, swipe up = fullscreen, swipe down
 * in fullscreen = exit; rotate = fullscreen. Resumes where you left off; PiP on Home.
 */
@OptIn(UnstableApi::class)
class OfflinePlayerActivity : AppCompatActivity() {

    private lateinit var root: FrameLayout
    private lateinit var col: LinearLayout
    private lateinit var playerBox: FrameLayout
    private lateinit var playerView: PlayerView
    private lateinit var poster: ImageView
    private lateinit var info: ScrollView
    private lateinit var titleView: TextView
    private lateinit var metaView: TextView
    private lateinit var listBox: LinearLayout
    private lateinit var seekL: TextView
    private lateinit var seekR: TextView
    private lateinit var speedPill: TextView

    private var player: ExoPlayer? = null
    private var session: MediaSession? = null
    private var queue: List<DownloadStore.Item> = emptyList()
    private val rows = ArrayList<DlUi.Row>()

    private var full = false
    private var fullFromRotation = false
    private var insetTop = 0
    private var lastWin = IntArray(2)        // window size (px) at the last config change: fold vs rotation
    private var insetL = 0
    private var insetR = 0
    private var orientationLock: OrientationEventListener? = null
    private val handler = Handler(Looper.getMainLooper())
    private val pipSupported by lazy { packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE) }

    private val saveTick = object : Runnable {
        override fun run() { savePos(); handler.postDelayed(this, 5000) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        current?.get()?.takeIf { it !== this }?.finish()     // one offline player at a time (PiP + new tap)
        current = WeakReference(this)
        buildViews()
        lastWin = windowSize()
        DlUi.edgeToEdge(this, root) { t, b, l, r ->
            insetTop = t; insetL = l; insetR = r
            info.setPadding(0, 0, 0, b)
            layoutPlayer()
        }
        setContentView(root)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { if (full) setFull(false) else finish() }
        })
        startQueue(intent.getStringExtra(EXTRA_ID))
        handler.postDelayed(saveTick, 5000)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val id = intent.getStringExtra(EXTRA_ID) ?: return
        val i = queue.indexOfFirst { it.id == id }
        if (i >= 0) playIndex(i) else startQueue(id)
    }

    // ------------------------------------------------------------------ views

    @SuppressLint("ClickableViewAccessibility")
    private fun buildViews() {
        root = FrameLayout(this).apply { setBackgroundColor(DlUi.BG) }
        col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(col, FrameLayout.LayoutParams(-1, -1))

        playerBox = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        playerView = PlayerView(this).apply {
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            setShutterBackgroundColor(Color.BLACK)
            setShowRewindButton(false)
            setShowFastForwardButton(false)
            setShowShuffleButton(false)
            setShowSubtitleButton(false)
            controllerShowTimeoutMs = 3000
            controllerAutoShow = true
            artworkDisplayMode = PlayerView.ARTWORK_DISPLAY_MODE_FIT     // audio-only downloads: thumbnail
            setFullscreenButtonClickListener { enter -> setFull(enter) }
        }
        playerView.findViewById<DefaultTimeBar>(androidx.media3.ui.R.id.exo_progress)?.apply {
            setPlayedColor(DlUi.RED); setScrubberColor(DlUi.RED)
            setBufferedColor(0x66FFFFFF); setUnplayedColor(0x33FFFFFF)
        }
        poster = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER; setBackgroundColor(Color.BLACK) }
        seekL = seekLabel(); seekR = seekLabel()
        speedPill = TextView(this).apply {
            text = "2x  \u25B6\u25B6"; textSize = 13f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(this@OfflinePlayerActivity, 12), dp(this@OfflinePlayerActivity, 6), dp(this@OfflinePlayerActivity, 12), dp(this@OfflinePlayerActivity, 6))
            background = GradientDrawable().apply { setColor(0x99000000.toInt()); cornerRadius = dp(this@OfflinePlayerActivity, 16).toFloat() }
            visibility = View.GONE
        }
        playerBox.addView(playerView, FrameLayout.LayoutParams(-1, -1))
        playerBox.addView(poster, FrameLayout.LayoutParams(-1, -1))
        playerBox.addView(seekL, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_VERTICAL or Gravity.START).apply { marginStart = dp(this@OfflinePlayerActivity, 40) })
        playerBox.addView(seekR, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_VERTICAL or Gravity.END).apply { marginEnd = dp(this@OfflinePlayerActivity, 40) })
        playerBox.addView(speedPill, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(this@OfflinePlayerActivity, 12) })
        poster.isClickable = false
        playerView.setOnTouchListener { _, e -> onPlayerTouch(e) }
        col.addView(playerBox, LinearLayout.LayoutParams(-1, -2))

        info = ScrollView(this).apply { isVerticalScrollBarEnabled = false; clipToPadding = false }
        val inner = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        info.addView(inner, FrameLayout.LayoutParams(-1, -2))
        titleView = DlUi.text(this, 18f, DlUi.TEXT, bold = true, lines = 3).apply {
            setPadding(dp(this@OfflinePlayerActivity, 12), dp(this@OfflinePlayerActivity, 12), dp(this@OfflinePlayerActivity, 12), 0) }
        metaView = DlUi.text(this, 12f, DlUi.TEXT2, lines = 2).apply {
            setPadding(dp(this@OfflinePlayerActivity, 12), dp(this@OfflinePlayerActivity, 6), dp(this@OfflinePlayerActivity, 12), 0) }
        inner.addView(titleView); inner.addView(metaView)
        val pills = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(this@OfflinePlayerActivity, 12), dp(this@OfflinePlayerActivity, 12), dp(this@OfflinePlayerActivity, 12), dp(this@OfflinePlayerActivity, 8))
        }
        pills.addView(pill(R.drawable.ic_dl_done, "Downloaded") { downloadedSheet() })
        pills.addView(pill(R.drawable.ic_dl_share, "Share") { cur()?.let { DlUi.shareVideo(this, it.id, it.title) } })
        pills.addView(pill(R.drawable.ic_dl_open, "Watch online") {
            cur()?.let { savePos(); DlUi.watchOnline(this, it.id, player?.currentPosition ?: 0); finish() } })
        inner.addView(android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(pills) })
        inner.addView(View(this).apply { setBackgroundColor(0x1FFFFFFF) }, LinearLayout.LayoutParams(-1, 1).apply { topMargin = dp(this@OfflinePlayerActivity, 4) })
        inner.addView(DlUi.text(this, 16f, DlUi.TEXT, bold = true).apply {
            text = "Downloads"; setPadding(dp(this@OfflinePlayerActivity, 12), dp(this@OfflinePlayerActivity, 14), dp(this@OfflinePlayerActivity, 12), dp(this@OfflinePlayerActivity, 4)) })
        listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        inner.addView(listBox)
        col.addView(info, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    private fun seekLabel() = TextView(this).apply {
        textSize = 13f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
        setPadding(dp(this@OfflinePlayerActivity, 18), dp(this@OfflinePlayerActivity, 18), dp(this@OfflinePlayerActivity, 18), dp(this@OfflinePlayerActivity, 18))
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x40FFFFFF) }
        visibility = View.GONE
    }

    private fun pill(icon: Int, label: String, onClick: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(this@OfflinePlayerActivity, 12), 0, dp(this@OfflinePlayerActivity, 14), 0)
        background = DlUi.ripple(DlUi.CHIP, dp(this@OfflinePlayerActivity, 18).toFloat())
        isClickable = true
        setOnClickListener { onClick() }
        addView(DlUi.icon(this@OfflinePlayerActivity, icon), LinearLayout.LayoutParams(dp(this@OfflinePlayerActivity, 20), dp(this@OfflinePlayerActivity, 20)))
        addView(DlUi.text(this@OfflinePlayerActivity, 14f, DlUi.TEXT, bold = true).apply { text = label; setPadding(dp(this@OfflinePlayerActivity, 6), 0, 0, 0) })
        layoutParams = LinearLayout.LayoutParams(-2, dp(this@OfflinePlayerActivity, 36)).apply { marginEnd = dp(this@OfflinePlayerActivity, 8) }
    }

    // ------------------------------------------------------------------ playback

    private fun cur(): DownloadStore.Item? = player?.currentMediaItemIndex?.let { queue.getOrNull(it) }

    private fun startQueue(id: String?) {
        queue = DownloadStore.completed(this)
        val start = queue.indexOfFirst { it.id == id }.coerceAtLeast(0)
        if (queue.isEmpty()) { finish(); return }
        val p = player ?: ExoPlayer.Builder(this).build().also { p ->
            p.setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
            p.setHandleAudioBecomingNoisy(true)
            p.addListener(listener)
            player = p
            playerView.player = p
            session = try { MediaSession.Builder(this, p).setId("ivx-offline-" + SystemClock.elapsedRealtime()).build() } catch (_: Exception) { null }
        }
        val items = queue.map { mediaItem(it) }
        val q = queue[start]
        p.setMediaItems(items, start, DownloadStore.resumeAt(this, q.id))
        p.prepare()
        p.playWhenReady = true
        showItem(start)
        buildList()
    }

    private fun mediaItem(it: DownloadStore.Item): MediaItem {
        val meta = MediaMetadata.Builder().setTitle(it.title).setArtist(it.author)
        try {
            val f = DownloadStore.thumbFile(this, it.id)
            if (f.exists()) meta.setArtworkData(f.readBytes(), MediaMetadata.PICTURE_TYPE_FRONT_COVER)
        } catch (_: Exception) { }
        return MediaItem.Builder().setMediaId(it.id).setUri(Uri.parse(it.uri)).setMediaMetadata(meta.build()).build()
    }

    private fun playIndex(i: Int) {
        val p = player ?: return
        savePos()
        if (i == p.currentMediaItemIndex) { p.play(); return }
        p.seekTo(i, DownloadStore.resumeAt(this, queue[i].id))
        p.play()
        info.smoothScrollTo(0, 0)
    }

    /** Title/meta + poster (thumbnail until the first frame renders — never a dead black box). */
    private fun showItem(i: Int) {
        val it = queue.getOrNull(i) ?: return
        titleView.text = it.title.ifBlank { it.id }
        metaView.text = listOf(it.author, DlUi.duration(it.lengthSec), if (it.isAudio) "Audio" else "", DlUi.size(this, it.size))
            .filter { s -> s.isNotEmpty() }.joinToString("  ·  ")
        val bmp = DlUi.thumb(this, it.id)
        poster.setImageBitmap(bmp)
        poster.visibility = if (bmp != null && !it.isAudio) View.VISIBLE else View.GONE
        rows.forEachIndexed { k, r -> queue.getOrNull(k)?.let { q -> r.bind(q, playing = k == i) } }
    }

    private fun buildList() {
        listBox.removeAllViews(); rows.clear()
        queue.forEachIndexed { i, it ->
            val r = DlUi.Row(this, compact = true)
            r.bind(it, playing = i == (player?.currentMediaItemIndex ?: -1))
            r.view.setOnClickListener { playIndex(i) }
            r.onMore = { DlUi.sheet(this, it.title, listOf(
                DlUi.SheetRow(R.drawable.ic_dl_play, "Play") { playIndex(i) },
                DlUi.SheetRow(R.drawable.ic_dl_delete, "Delete from downloads") { confirmDelete(it) },
                DlUi.SheetRow(R.drawable.ic_dl_share, "Share") { DlUi.shareVideo(this, it.id, it.title) },
            )) }
            rows.add(r)
            listBox.addView(r.view, LinearLayout.LayoutParams(-1, -2))
        }
    }

    private fun downloadedSheet() {
        val it = cur() ?: return
        DlUi.sheet(this, it.title, listOf(
            DlUi.SheetRow(R.drawable.ic_dl_delete, "Delete from downloads") { confirmDelete(it) },
            DlUi.SheetRow(R.drawable.ic_dl_download, "All downloads") { DownloadsActivity.start(this); finish() },
        ))
    }

    private fun confirmDelete(it: DownloadStore.Item) {
        AlertDialog.Builder(this)
            .setMessage("Delete \u201C${it.title}\u201D from downloads?")
            .setPositiveButton("Delete") { _, _ ->
                val p = player ?: return@setPositiveButton
                val i = queue.indexOfFirst { q -> q.id == it.id }
                if (i < 0) return@setPositiveButton
                queue = queue.toMutableList().also { l -> l.removeAt(i) }
                p.removeMediaItem(i)
                val app = applicationContext
                Thread { DownloadStore.delete(app, it.id) }.start()
                if (queue.isEmpty()) { finish(); return@setPositiveButton }
                buildList(); showItem(p.currentMediaItemIndex)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun savePos() {
        val p = player ?: return
        val it = cur() ?: return
        val d = p.duration
        if (d != C.TIME_UNSET && d > 0) DownloadStore.savePos(this, it.id, p.currentPosition, d)
    }

    private val listener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            showItem(player?.currentMediaItemIndex ?: 0)
        }
        override fun onRenderedFirstFrame() { poster.animate().alpha(0f).setDuration(120).withEndAction {
            poster.visibility = View.GONE; poster.alpha = 1f }.start() }
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); savePos() }
            updatePip()
        }
        override fun onPositionDiscontinuity(old: Player.PositionInfo, new: Player.PositionInfo, reason: Int) {
            // finished one → mark it watched (full red bar in the list)
            if (reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION) queue.getOrNull(old.mediaItemIndex)?.let { q ->
                val d = player?.let { if (old.mediaItemIndex == it.currentMediaItemIndex) it.duration else q.lengthSec * 1000L } ?: 0L
                if (d > 0) DownloadStore.savePos(this@OfflinePlayerActivity, q.id, d, d)
            }
        }
        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_ENDED) cur()?.let { q ->
                val d = player?.duration ?: 0L
                if (d > 0) DownloadStore.savePos(this@OfflinePlayerActivity, q.id, d, d)
            }
        }
        override fun onVideoSizeChanged(videoSize: VideoSize) { updatePip(); layoutPlayer() }
        override fun onPlayerError(error: PlaybackException) {
            android.widget.Toast.makeText(this@OfflinePlayerActivity, "Can't play this file (${error.errorCodeName})", android.widget.Toast.LENGTH_LONG).show()
        }
    }

    // ------------------------------------------------------------------ gestures

    private var downX = 0f; private var downY = 0f
    private var moved = false; private var holding = false
    private var lastTapAt = 0L; private var lastTapSide = 0
    private var streakSide = 0; private var streakN = 0
    private var ctrlBeforeTap = false
    private val touchSlop by lazy { ViewConfiguration.get(this).scaledTouchSlop }
    private val hold = Runnable {
        if (!moved) { holding = true; player?.setPlaybackSpeed(2f); speedPill.visibility = View.VISIBLE; playerView.hideController() }
    }
    private val endStreak = Runnable { streakN = 0; streakSide = 0; seekL.visibility = View.GONE; seekR.visibility = View.GONE }

    private fun onPlayerTouch(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y; moved = false; holding = false
                handler.postDelayed(hold, 450)
            }
            MotionEvent.ACTION_MOVE -> {
                if (!moved && (abs(e.x - downX) > touchSlop || abs(e.y - downY) > touchSlop)) {
                    moved = true; handler.removeCallbacks(hold)
                }
            }
            MotionEvent.ACTION_UP -> {
                handler.removeCallbacks(hold)
                if (holding) { holding = false; player?.setPlaybackSpeed(1f); speedPill.visibility = View.GONE; return true }
                if (moved) {
                    val dx = e.x - downX; val dy = e.y - downY
                    if (abs(dy) > abs(dx) * 1.3f && abs(dy) > dp(this, 60)) {
                        if (dy < 0 && !full) setFull(true) else if (dy > 0 && full) setFull(false)
                    }
                    return true
                }
                onTap(e.x)
            }
            MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(hold)
                if (holding) { holding = false; player?.setPlaybackSpeed(1f); speedPill.visibility = View.GONE }
            }
        }
        return true
    }

    /** Instant controls toggle on the first tap; a second tap on the same side within 300 ms
     *  turns it into a seek (controls go back to how they were), and every further tap on that
     *  side while the bubble is up adds another 10 s — the YouTube double-tap streak. */
    private fun onTap(x: Float) {
        val w = playerView.width.toFloat()
        val side = when { x < w * 0.4f -> -1; x > w * 0.6f -> 1; else -> 0 }
        val now = SystemClock.uptimeMillis()
        val streaking = streakN > 0 && side == streakSide
        val dbl = side != 0 && side == lastTapSide && now - lastTapAt < 300
        if (streaking || dbl) {
            if (!streaking) { streakN = 0; streakSide = side; if (!ctrlBeforeTap) playerView.hideController() else playerView.showController() }
            streakN++
            player?.let { p ->
                val d = p.duration.takeIf { it != C.TIME_UNSET } ?: Long.MAX_VALUE
                p.seekTo((p.currentPosition + side * 10_000L).coerceIn(0L, d))
            }
            val lbl = if (side < 0) seekL else seekR
            (if (side < 0) seekR else seekL).visibility = View.GONE
            lbl.text = (if (side < 0) "\u25C0\u25C0\n" else "\u25B6\u25B6\n") + "${streakN * 10} seconds"
            lbl.visibility = View.VISIBLE
            lbl.scaleX = 0.85f; lbl.scaleY = 0.85f
            lbl.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
            handler.removeCallbacks(endStreak); handler.postDelayed(endStreak, 700)
            lastTapAt = now; lastTapSide = side
            return
        }
        ctrlBeforeTap = playerView.isControllerFullyVisible
        if (ctrlBeforeTap) playerView.hideController() else playerView.showController()
        lastTapAt = now; lastTapSide = side
    }

    // ------------------------------------------------------------------ fullscreen / layout

    private fun videoIsLandscape(): Boolean {
        val v = player?.videoSize ?: return true
        return v.width == 0 || v.width * v.pixelWidthHeightRatio >= v.height
    }

    private fun setFull(on: Boolean, fromRotation: Boolean = false) {
        if (full == on) return
        full = on
        fullFromRotation = on && fromRotation
        playerView.setFullscreenButtonState(on)
        val ctl = WindowInsetsControllerCompat(window, root)
        if (on) {
            hideBars()
            if (!fromRotation && videoIsLandscape() && resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE)
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            ctl.show(WindowInsetsCompat.Type.systemBars())
            if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE && !fromRotation) {
                // back to portrait now; give rotation back once the phone is actually held upright
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                releaseRotationWhenUpright()
            } else if (requestedOrientation != ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) {
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        }
        layoutPlayer()
    }

    private fun releaseRotationWhenUpright() {
        orientationLock?.disable()
        orientationLock = object : OrientationEventListener(this) {
            override fun onOrientationChanged(deg: Int) {
                if (deg == ORIENTATION_UNKNOWN) return
                if (deg < 25 || deg > 335) {
                    disable(); orientationLock = null
                    if (!full) requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                }
            }
        }.also { if (it.canDetectOrientation()) it.enable() else { requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED } }
    }

    private fun layoutPlayer() {
        if (!::playerBox.isInitialized) return
        val pip = isInPictureInPictureMode
        info.visibility = if (full || pip) View.GONE else View.VISIBLE
        playerView.useController = !pip
        col.setPadding(if (full || pip) 0 else insetL, 0, if (full || pip) 0 else insetR, 0)
        val lp = playerBox.layoutParams as LinearLayout.LayoutParams
        if (full || pip) {
            lp.height = LinearLayout.LayoutParams.MATCH_PARENT; lp.weight = 1f
            playerBox.setPadding(0, 0, 0, 0)
        } else {
            val w = root.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
            // 16:9 box under a black status-bar strip; never more than ~60 % of the screen
            val h = minOf(w * 9 / 16, (resources.displayMetrics.heightPixels * 0.6f).toInt())
            lp.height = h + insetTop; lp.weight = 0f
            playerBox.setPadding(0, insetTop, 0, 0)
        }
        playerBox.layoutParams = lp
    }

    private fun windowSize(): IntArray =
        if (Build.VERSION.SDK_INT >= 30) windowManager.currentWindowMetrics.bounds.let { intArrayOf(it.width(), it.height()) }
        else resources.displayMetrics.let { intArrayOf(it.widthPixels, it.heightPixels) }

    private fun hideBars() {
        WindowInsetsControllerCompat(window, root).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && full && !isInPictureInPictureMode) hideBars()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (isInPictureInPictureMode) return
        // Folding/unfolding the Fold is a display switch, not a rotation: width/height don't just
        // swap. Keep fullscreen exactly as it is (bars may have come back), only re-layout.
        val now = windowSize(); val was = lastWin; lastWin = now
        fun near(a: Int, b: Int) = abs(a - b) <= maxOf(a, b) / 20
        val rotated = near(now[0], was[1]) && near(now[1], was[0]) && !near(now[0], was[0])
        val sameSize = near(now[0], was[0]) && near(now[1], was[1])
        if (!rotated && !sameSize) {
            if (full) { hideBars(); root.postDelayed({ if (full) hideBars() }, 400) }
            root.post { layoutPlayer() }
            return
        }
        // rotate to landscape = fullscreen; back to portrait = leave it (YouTube)
        if (newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE && !full) setFull(true, fromRotation = true)
        else if (newConfig.orientation == Configuration.ORIENTATION_PORTRAIT && full && (fullFromRotation ||
                requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED)) setFull(false, fromRotation = true)
        root.post { layoutPlayer() }
    }

    // ------------------------------------------------------------------ PiP / lifecycle

    private fun pipParams(): PictureInPictureParams {
        val v = player?.videoSize
        var r = if (v != null && v.width > 0 && v.height > 0) Rational(v.width, v.height) else Rational(16, 9)
        val f = r.toFloat()
        if (f > 2.39f) r = Rational(239, 100) else if (f < 1 / 2.39f) r = Rational(100, 239)
        val b = PictureInPictureParams.Builder().setAspectRatio(r)
        if (Build.VERSION.SDK_INT >= 31) b.setAutoEnterEnabled(player?.isPlaying == true).setSeamlessResizeEnabled(false)
        return b.build()
    }

    private fun updatePip() {
        if (!pipSupported) return
        try { setPictureInPictureParams(pipParams()) } catch (_: Exception) { }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT < 31 && pipSupported && player?.isPlaying == true)
            try { enterPictureInPictureMode(pipParams()) } catch (_: Exception) { }
    }

    override fun onPictureInPictureModeChanged(isInPip: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPip, newConfig)
        if (isInPip) playerView.hideController()
        layoutPlayer()
    }

    override fun onStop() {
        super.onStop()
        savePos()
        // closing the PiP window or leaving without PiP stops playback (YouTube)
        player?.pause()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        orientationLock?.disable()
        savePos()
        session?.release(); session = null
        player?.removeListener(listener)
        player?.release(); player = null
        if (current?.get() === this) current = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_ID = "id"
        private var current: WeakReference<OfflinePlayerActivity>? = null

        fun start(ctx: Context, id: String) {
            ctx.startActivity(Intent(ctx, OfflinePlayerActivity::class.java).apply {
                putExtra(EXTRA_ID, id)
                if (ctx !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }
    }
}
