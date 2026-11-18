package app.hushtube.android

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.text.format.Formatter
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

/** Shared native UI for the Downloads screen + offline player (YouTube-app dark look). */
object DlUi {
    const val BG = 0xFF0F0F0F.toInt()
    const val SURFACE = 0xFF212121.toInt()
    const val TEXT = 0xFFF1F1F1.toInt()
    const val TEXT2 = 0xFFAAAAAA.toInt()
    const val RED = 0xFFFF0033.toInt()
    const val CHIP = 0xFF272727.toInt()

    fun dp(ctx: Context, v: Number) = (v.toFloat() * ctx.resources.displayMetrics.density + 0.5f).toInt()

    /** Same-frame touch feedback (ripple starts on ACTION_DOWN). */
    fun ripple(bg: Int = Color.TRANSPARENT, radiusPx: Float = 0f): RippleDrawable {
        val content = GradientDrawable().apply { setColor(bg); cornerRadius = radiusPx }
        val mask = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = radiusPx }
        return RippleDrawable(ColorStateList.valueOf(0x40FFFFFF), content, mask)
    }

    fun text(ctx: Context, sizeSp: Float, color: Int, bold: Boolean = false, lines: Int = 1) = TextView(ctx).apply {
        textSize = sizeSp; setTextColor(color)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        maxLines = lines; ellipsize = TextUtils.TruncateAt.END
    }

    fun icon(ctx: Context, res: Int, tint: Int = TEXT) = ImageView(ctx).apply {
        setImageResource(res); imageTintList = ColorStateList.valueOf(tint)
        scaleType = ImageView.ScaleType.CENTER
    }

    fun iconButton(ctx: Context, res: Int, desc: String, onClick: () -> Unit) = icon(ctx, res).apply {
        contentDescription = desc
        background = RippleDrawable(ColorStateList.valueOf(0x40FFFFFF), null, null)   // unbounded circle
        isClickable = true
        setOnClickListener { onClick() }
    }

    fun duration(sec: Int): String {
        if (sec <= 0) return ""
        val h = sec / 3600; val m = (sec % 3600) / 60; val s = sec % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    fun size(ctx: Context, bytes: Long) = if (bytes > 0) Formatter.formatShortFileSize(ctx, bytes) else ""

    // ---- thumbnails (tiny jpgs, decoded once) ----
    private val cache = object : LruCache<String, Bitmap>(12 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    fun thumb(ctx: Context, id: String): Bitmap? {
        val f = DownloadStore.thumbFile(ctx, id)
        if (!f.exists()) return null
        val key = id + ":" + f.lastModified()
        cache.get(key)?.let { return it }
        return try { BitmapFactory.decodeFile(f.path)?.also { cache.put(key, it) } } catch (_: Exception) { null }
    }

    /** Rounded-corner clip for a view. */
    fun round(v: View, radiusPx: Float) {
        v.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) = outline.setRoundRect(0, 0, view.width, view.height, radiusPx)
        }
        v.clipToOutline = true
    }

    /** Edge-to-edge (targetSdk 35): content dodges the system bars via padding. */
    fun edgeToEdge(act: Activity, root: View, apply: (top: Int, bottom: Int, left: Int, right: Int) -> Unit) {
        WindowCompat.setDecorFitsSystemWindows(act.window, false)
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            apply(b.top, b.bottom, b.left, b.right)
            insets
        }
    }

    // ---- one download row: [thumb + duration + resume bar] [title / channel / status] [⋮] ----
    class Row(private val ctx: Context, compact: Boolean = false) {
        val view = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            setPadding(dp(ctx, 12), dp(ctx, 8), dp(ctx, 4), dp(ctx, 8))
            background = ripple()
            isClickable = true
        }
        private val thumbBox = FrameLayout(ctx).apply { setBackgroundColor(SURFACE) }
        private val img = ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        private val audioIcon = icon(ctx, R.drawable.ic_dl_audio, TEXT2)
        private val dur = TextView(ctx).apply {
            textSize = 11f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(ctx, 4), dp(ctx, 1), dp(ctx, 4), dp(ctx, 1))
            background = GradientDrawable().apply { setColor(0xCC000000.toInt()); cornerRadius = dp(ctx, 4).toFloat() }
        }
        private val resume = View(ctx).apply { setBackgroundColor(RED); pivotX = 0f }
        private val dim = View(ctx).apply { setBackgroundColor(0x99000000.toInt()) }
        private val pctText = TextView(ctx).apply { textSize = 13f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER }
        private val title = text(ctx, 14f, TEXT, lines = 2)
        private val author = text(ctx, 12f, TEXT2)
        private val status = text(ctx, 12f, TEXT2)
        private val bar = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; progressTintList = ColorStateList.valueOf(RED)
            progressBackgroundTintList = ColorStateList.valueOf(0x33FFFFFF)
            indeterminateTintList = ColorStateList.valueOf(RED)
        }
        val more = iconButton(ctx, R.drawable.ic_dl_more, "More") { onMore?.invoke() }
        var onMore: (() -> Unit)? = null
        var item: DownloadStore.Item? = null; private set

        init {
            val tw = dp(ctx, if (compact) 140 else 160); val th = tw * 9 / 16
            round(thumbBox, dp(ctx, 8).toFloat())
            thumbBox.addView(img, FrameLayout.LayoutParams(-1, -1))
            thumbBox.addView(audioIcon, FrameLayout.LayoutParams(-1, -1))
            thumbBox.addView(dim, FrameLayout.LayoutParams(-1, -1))
            thumbBox.addView(pctText, FrameLayout.LayoutParams(-1, -1))
            thumbBox.addView(dur, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply {
                setMargins(0, 0, dp(ctx, 4), dp(ctx, 4)) })
            thumbBox.addView(resume, FrameLayout.LayoutParams(-1, dp(ctx, 3), Gravity.BOTTOM))
            view.addView(thumbBox, LinearLayout.LayoutParams(tw, th))
            val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(ctx, 12), 0, 0, 0) }
            col.addView(title)
            col.addView(author, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(ctx, 4) })
            col.addView(status)
            col.addView(bar, LinearLayout.LayoutParams(-1, dp(ctx, 4)).apply { topMargin = dp(ctx, 6) })
            view.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
            view.addView(more, LinearLayout.LayoutParams(dp(ctx, 40), dp(ctx, 40)))
        }

        fun bind(it: DownloadStore.Item, playing: Boolean = false) {
            item = it
            title.text = it.title.ifBlank { it.id }
            author.text = it.author
            author.visibility = if (it.author.isBlank()) View.GONE else View.VISIBLE
            val bmp = thumb(ctx, it.id)
            img.setImageBitmap(bmp)
            audioIcon.visibility = if (bmp == null || it.isAudio && bmp == null) View.VISIBLE else View.GONE
            val running = it.state == DownloadStore.DOWNLOADING
            dim.visibility = if (running) View.VISIBLE else View.GONE
            pctText.visibility = dim.visibility
            pctText.text = if (it.pct >= 0) "${it.pct}%" else ""
            bar.visibility = if (running) View.VISIBLE else View.GONE
            if (running) {
                bar.isIndeterminate = it.pct < 0
                if (it.pct >= 0) bar.progress = it.pct
                status.text = when {
                    it.done == 0L -> "Waiting to download…"
                    it.total > 0 -> "Downloading · ${size(ctx, it.done)} of ${size(ctx, it.total)}"
                    else -> "Downloading · ${size(ctx, it.done)}"
                }
            } else {
                status.text = listOf(if (playing) "Now playing" else "", if (it.isAudio) "Audio" else "", size(ctx, it.size))
                    .filter { s -> s.isNotEmpty() }.joinToString(" · ")
            }
            status.setTextColor(if (playing) TEXT else TEXT2)
            dur.text = duration(it.lengthSec)
            dur.visibility = if (running || it.lengthSec <= 0) View.GONE else View.VISIBLE
            val p = DownloadStore.pos(ctx, it.id)
            if (!running && p != null && p.second > 0 && p.first > 0) {
                resume.visibility = View.VISIBLE
                resume.scaleX = (p.first.toFloat() / p.second).coerceIn(0.03f, 1f)
            } else resume.visibility = View.GONE
            view.setBackgroundColor(Color.TRANSPARENT)
            view.background = ripple(if (playing) CHIP else Color.TRANSPARENT)
        }
    }

    // ---- bottom sheet (⋮ menus), YouTube style ----
    class SheetRow(val icon: Int, val label: String, val action: () -> Unit)

    fun sheet(act: Activity, title: String?, rows: List<SheetRow>): Dialog {
        val d = Dialog(act)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val box = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(act, 8), 0, dp(act, 16))
            background = GradientDrawable().apply {
                setColor(SURFACE)
                val r = dp(act, 12).toFloat(); cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            }
        }
        box.addView(View(act).apply {
            background = GradientDrawable().apply { setColor(0x66FFFFFF); cornerRadius = dp(act, 2).toFloat() }
        }, LinearLayout.LayoutParams(dp(act, 36), dp(act, 4)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(act, 8) })
        if (!title.isNullOrBlank()) box.addView(text(act, 14f, TEXT2, lines = 2).apply {
            text = title; setPadding(dp(act, 16), dp(act, 4), dp(act, 16), dp(act, 8)) })
        rows.forEach { r ->
            val row = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(act, 16), 0, dp(act, 16), 0)
                background = ripple(); isClickable = true
                setOnClickListener { d.dismiss(); Handler(Looper.getMainLooper()).post { r.action() } }
            }
            row.addView(icon(act, r.icon), LinearLayout.LayoutParams(dp(act, 24), dp(act, 24)))
            row.addView(text(act, 16f, TEXT).apply { text = r.label; setPadding(dp(act, 24), 0, 0, 0) })
            box.addView(row, LinearLayout.LayoutParams(-1, dp(act, 52)))
        }
        d.setContentView(box)
        d.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setGravity(Gravity.BOTTOM)
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setWindowAnimations(android.R.style.Animation_InputMethod)       // slides up
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.5f)
            // edge-to-edge: keep the sheet's last row above the gesture bar
            ViewCompat.setOnApplyWindowInsetsListener(box) { v, ins ->
                val b = ins.getInsets(WindowInsetsCompat.Type.navigationBars())
                v.setPadding(0, dp(act, 8), 0, dp(act, 16) + b.bottom); ins
            }
        }
        d.show()
        return d
    }

    // ---- snackbar with an action (delete → Undo) ----
    fun snackbar(parent: FrameLayout, msg: String, action: String?, bottomPx: Int, onAction: (() -> Unit)?, onGone: (() -> Unit)? = null) {
        val ctx = parent.context
        parent.findViewWithTag<View>("ivx-snack")?.let { (it.getTag(R.id.ivx_snack_gone) as? Runnable)?.run(); parent.removeView(it) }
        val bar = LinearLayout(ctx).apply {
            tag = "ivx-snack"
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(ctx, 16), 0, dp(ctx, 8), 0)
            background = GradientDrawable().apply { setColor(0xFFF1F1F1.toInt()); cornerRadius = dp(ctx, 8).toFloat() }
            elevation = dp(ctx, 6).toFloat()
        }
        bar.addView(text(ctx, 14f, 0xFF0F0F0F.toInt()).apply { text = msg }, LinearLayout.LayoutParams(0, -2, 1f))
        val h = Handler(Looper.getMainLooper())
        var done = false
        val finish = Runnable {
            if (done) return@Runnable
            done = true
            bar.animate().alpha(0f).setDuration(150).withEndAction { parent.removeView(bar) }.start()
            onGone?.invoke()
        }
        bar.setTag(R.id.ivx_snack_gone, finish)
        if (action != null && onAction != null) bar.addView(TextView(ctx).apply {
            text = action; textSize = 14f; setTextColor(0xFF065FD4.toInt()); typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(ctx, 12), dp(ctx, 12), dp(ctx, 12), dp(ctx, 12))
            background = ripple(radiusPx = dp(ctx, 18).toFloat()); isClickable = true
            setOnClickListener { if (!done) { done = true; h.removeCallbacks(finish); onAction(); parent.removeView(bar) } }
        })
        parent.addView(bar, FrameLayout.LayoutParams(-1, dp(ctx, 48), Gravity.BOTTOM).apply {
            setMargins(dp(ctx, 8), 0, dp(ctx, 8), dp(ctx, 12) + bottomPx) })
        bar.alpha = 0f; bar.translationY = dp(ctx, 24).toFloat()
        bar.animate().alpha(1f).translationY(0f).setDuration(180).start()
        h.postDelayed(finish, 4000)
    }

    /** Commits a pending snackbar (e.g. the delayed delete) right away. */
    fun flushSnackbar(parent: FrameLayout) {
        parent.findViewWithTag<View>("ivx-snack")?.let { (it.getTag(R.id.ivx_snack_gone) as? Runnable)?.run() }
    }

    fun shareVideo(act: Activity, id: String, title: String) {
        try {
            act.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"; putExtra(Intent.EXTRA_TEXT, "https://youtu.be/$id")
                if (title.isNotBlank()) putExtra(Intent.EXTRA_SUBJECT, title)
            }, null))
        } catch (_: Exception) { }
    }

    /** "Watch online": back to the main WebView on this video (resume position as &t=). */
    fun watchOnline(act: Activity, id: String, posMs: Long = 0) {
        val prefs = act.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val server = (prefs.getString(MainActivity.PREF_SERVER, MainActivity.DEFAULT_SERVER) ?: MainActivity.DEFAULT_SERVER).trimEnd('/')
        act.startActivity(Intent(act, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_URL, "$server/watch?v=$id" + if (posMs > 5000) "&t=${posMs / 1000}" else "")
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        })
    }
}
