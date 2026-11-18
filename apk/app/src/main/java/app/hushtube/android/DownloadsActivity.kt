package app.hushtube.android

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.StatFs
import android.os.Environment
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import app.hushtube.android.DlUi.dp

/**
 * "Downloads" (You tab → Downloads), like the YouTube app: newest first, running downloads on
 * top with live progress, thumbnail + duration + red resume bar, ⋮ → Delete (with Undo) /
 * Share / Watch online, storage line at the bottom. Fully offline — no server needed.
 */
class DownloadsActivity : AppCompatActivity() {

    private lateinit var root: FrameLayout
    private lateinit var list: LinearLayout
    private lateinit var empty: LinearLayout
    private lateinit var footer: TextView
    private val rows = LinkedHashMap<String, DlUi.Row>()
    private val hidden = HashSet<String>()          // deleted, Undo still possible
    private var navBottom = 0
    private val listener: () -> Unit = { render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = FrameLayout(this).apply { setBackgroundColor(DlUi.BG) }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(col, FrameLayout.LayoutParams(-1, -1))

        // top bar: ← Downloads
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        bar.addView(DlUi.iconButton(this, R.drawable.ic_dl_back, "Back") { finish() }, LinearLayout.LayoutParams(dp(this, 56), dp(this, 56)))
        bar.addView(DlUi.text(this, 20f, DlUi.TEXT, bold = true).apply { text = "Downloads" })
        col.addView(bar, LinearLayout.LayoutParams(-1, dp(this, 56)))

        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false; clipToPadding = false }
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(list, FrameLayout.LayoutParams(-1, -2))
        col.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        footer = DlUi.text(this, 12f, DlUi.TEXT2, lines = 2).apply {
            gravity = Gravity.CENTER; setPadding(dp(this@DownloadsActivity, 16), dp(this@DownloadsActivity, 20), dp(this@DownloadsActivity, 16), dp(this@DownloadsActivity, 24))
        }

        empty = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; visibility = View.GONE
            setPadding(dp(this@DownloadsActivity, 32), 0, dp(this@DownloadsActivity, 32), dp(this@DownloadsActivity, 56))
        }
        empty.addView(View(this).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(DlUi.CHIP) }
        }.let { circle ->
            FrameLayout(this).apply {
                addView(circle, FrameLayout.LayoutParams(-1, -1))
                addView(DlUi.icon(this@DownloadsActivity, R.drawable.ic_dl_download).apply { scaleX = 2f; scaleY = 2f }, FrameLayout.LayoutParams(-1, -1))
            }
        }, LinearLayout.LayoutParams(dp(this@DownloadsActivity, 120), dp(this@DownloadsActivity, 120)).apply { bottomMargin = dp(this@DownloadsActivity, 24) })
        empty.addView(DlUi.text(this, 18f, DlUi.TEXT, bold = true).apply { text = "No downloads"; gravity = Gravity.CENTER })
        empty.addView(DlUi.text(this, 14f, DlUi.TEXT2, lines = 4).apply {
            text = "Videos you download will show up here, ready to watch without a connection. Tap Download under any video."
            gravity = Gravity.CENTER; setPadding(0, dp(this@DownloadsActivity, 8), 0, 0)
        })
        col.addView(empty, LinearLayout.LayoutParams(-1, 0, 0f))

        DlUi.edgeToEdge(this, root) { t, b, l, r ->
            col.setPadding(l, t, r, 0)
            scroll.setPadding(0, 0, 0, b)
            navBottom = b
        }
        setContentView(root)
        render()
    }

    override fun onStart() {
        super.onStart()
        DownloadStore.addListener(listener)
        render()
        val app = applicationContext
        Thread { DownloadStore.refresh(app) }.start()       // picks up files deleted/added elsewhere
    }

    override fun onStop() {
        DlUi.flushSnackbar(root)                             // commit a pending delete
        DownloadStore.removeListener(listener)
        super.onStop()
    }

    private fun render() {
        val items = DownloadStore.list(this).filter { it.id !in hidden }
        val ids = items.map { it.id }
        if (ids != rows.keys.toList()) {
            list.removeAllViews()
            val old = HashMap(rows); rows.clear()
            items.forEach { item ->
                val row = old[item.id] ?: DlUi.Row(this).also { r ->
                    r.view.setOnClickListener { r.item?.let { open(it) } }
                    r.onMore = { r.item?.let { more(it) } }
                }
                rows[item.id] = row
                list.addView(row.view, LinearLayout.LayoutParams(-1, -2))
            }
            list.addView(footer)
        }
        items.forEach { rows[it.id]?.bind(it) }
        val done = items.filter { it.state == DownloadStore.DONE }
        footer.text = storageLine(done.size, done.sumOf { it.size })
        val isEmpty = items.isEmpty()
        empty.visibility = if (isEmpty) View.VISIBLE else View.GONE
        (empty.layoutParams as LinearLayout.LayoutParams).weight = if (isEmpty) 1f else 0f
        (list.parent as View).visibility = if (isEmpty) View.GONE else View.VISIBLE
    }

    private fun storageLine(n: Int, bytes: Long): String {
        val free = try { StatFs(Environment.getExternalStorageDirectory().path).availableBytes } catch (_: Exception) { -1L }
        val a = if (n == 1) "1 video" else "$n videos"
        val b = DlUi.size(this, bytes)
        val c = if (free >= 0) "${DlUi.size(this, free)} free" else ""
        return listOf(a, b).filter { it.isNotEmpty() }.joinToString(" · ") +
            "\nSaved in Download/${DownloadStore.FOLDER}" + if (c.isNotEmpty()) " · $c" else ""
    }

    private fun open(it: DownloadStore.Item) {
        if (it.state == DownloadStore.DOWNLOADING) { more(it); return }
        OfflinePlayerActivity.start(this, it.id)
    }

    private fun more(it: DownloadStore.Item) {
        val rows = if (it.state == DownloadStore.DOWNLOADING) listOf(
            DlUi.SheetRow(R.drawable.ic_dl_close, "Cancel download") { DownloadStore.cancel(it.id) },
        ) else listOf(
            DlUi.SheetRow(R.drawable.ic_dl_play, "Play") { OfflinePlayerActivity.start(this, it.id) },
            DlUi.SheetRow(R.drawable.ic_dl_delete, "Delete from downloads") { delete(it) },
            DlUi.SheetRow(R.drawable.ic_dl_share, "Share") { DlUi.shareVideo(this, it.id, it.title) },
            DlUi.SheetRow(R.drawable.ic_dl_open, "Watch online") {
                DlUi.watchOnline(this, it.id, DownloadStore.pos(this, it.id)?.first ?: 0); finish()
            },
        )
        DlUi.sheet(this, it.title, rows)
    }

    /** YouTube: the row disappears at once, "Undo" for 4 s, then the file is really deleted. */
    private fun delete(it: DownloadStore.Item) {
        hidden.add(it.id)
        render()
        val app = applicationContext
        DlUi.snackbar(root, "Deleted from downloads", "UNDO", navBottom,
            onAction = { hidden.remove(it.id); render() },
            onGone = { if (hidden.remove(it.id)) Thread { DownloadStore.delete(app, it.id) }.start() })
    }

    companion object {
        fun start(ctx: Context) {
            ctx.startActivity(Intent(ctx, DownloadsActivity::class.java).apply {
                if (ctx !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }
    }
}
