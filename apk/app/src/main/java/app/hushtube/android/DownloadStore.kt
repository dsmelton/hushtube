package app.hushtube.android

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The app's downloads (v1.20), YouTube-app style.
 *
 *  - Files go to their OWN folder: `Download/HushTube/Title (videoId).ext` (MediaStore,
 *    no storage permission needed; visible in the Files app). Pre-1.20 downloads that sit in
 *    the root of Download/ are moved into the folder once.
 *  - Metadata (title, channel, length, size) + the thumbnail are kept app-private
 *    (filesDir/downloads/) so the Downloads screen and the offline player work with the
 *    server unreachable.
 *  - Live progress for running downloads; listeners are called on the main thread (throttled).
 */
object DownloadStore {
    const val FOLDER = "HushTube"
    val REL_PATH = Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER + "/"     // "Download/HushTube/"

    const val DONE = 0
    const val DOWNLOADING = 1

    private val ID_IN_NAME = Regex("""\(([A-Za-z0-9_-]{11})\)(?: ?\(\d+\))?\.([A-Za-z0-9]{2,5})$""")
    private val ID_RE = Regex("^[A-Za-z0-9_-]{11}$")

    class Item(val id: String) {
        var title = ""
        var author = ""
        var authorId = ""
        var lengthSec = 0
        var uri = ""
        var name = ""
        var size = 0L
        var mime = "video/mp4"
        var added = 0L
        @Volatile var state = DONE
        @Volatile var done = 0L
        @Volatile var total = -1L
        @Volatile var cancel = false
        val isAudio get() = mime.startsWith("audio")
        val pct: Int get() = if (total > 0) ((done * 100) / total).toInt().coerceIn(0, 100) else -1

        fun toJson(): JSONObject = JSONObject()
            .put("id", id).put("title", title).put("author", author).put("authorId", authorId)
            .put("len", lengthSec).put("uri", uri).put("name", name).put("size", size)
            .put("mime", mime).put("added", added)

        companion object {
            fun fromJson(o: JSONObject): Item? {
                val id = o.optString("id")
                if (!ID_RE.matches(id)) return null
                return Item(id).apply {
                    title = o.optString("title"); author = o.optString("author")
                    authorId = o.optString("authorId"); lengthSec = o.optInt("len")
                    uri = o.optString("uri"); name = o.optString("name"); size = o.optLong("size")
                    mime = o.optString("mime", "video/mp4"); added = o.optLong("added")
                }
            }
        }
    }

    /** Everything the native downloader needs (comes from the page via the JS bridge). */
    class Request(
        val url: String, val body: String, val cookie: String?, val id: String,
        val title: String, val author: String, val authorId: String, val lengthSec: Int,
        val thumbUrl: String,
    )

    private val lock = Any()
    private val items = LinkedHashMap<String, Item>()
    private var loaded = false
    private var migrated = false
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var notifyQueued = false

    // ---------------------------------------------------------------- state / listeners

    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }

    /** Coalesces bursts (progress ticks) into ≤ 1 callback per 250 ms. */
    private fun changed(immediate: Boolean = false) {
        if (immediate) { main.post { notifyQueued = false; listeners.forEach { it() } }; return }
        if (notifyQueued) return
        notifyQueued = true
        main.postDelayed({ notifyQueued = false; listeners.forEach { it() } }, 250)
    }

    private fun dir(ctx: Context) = File(ctx.filesDir, "downloads").apply { mkdirs() }
    fun thumbFile(ctx: Context, id: String) = File(File(dir(ctx), "thumbs").apply { mkdirs() }, "$id.jpg")

    private fun ensureLoaded(ctx: Context) {
        synchronized(lock) {
            if (loaded) return
            loaded = true
            try {
                val f = File(dir(ctx), "index.json")
                if (f.exists()) {
                    val arr = JSONArray(f.readText())
                    for (i in 0 until arr.length()) Item.fromJson(arr.getJSONObject(i))?.let { items[it.id] = it }
                }
            } catch (_: Exception) { }
        }
    }

    private fun save(ctx: Context) {
        val arr = JSONArray()
        synchronized(lock) { items.values.filter { it.state == DONE }.forEach { arr.put(it.toJson()) } }
        try {
            val tmp = File(dir(ctx), "index.json.tmp")
            tmp.writeText(arr.toString())
            tmp.renameTo(File(dir(ctx), "index.json"))
        } catch (_: Exception) { }
    }

    /** Running downloads first, then newest first (YouTube order). */
    fun list(ctx: Context): List<Item> {
        ensureLoaded(ctx)
        synchronized(lock) {
            return items.values.sortedWith(compareBy<Item> { it.state != DOWNLOADING }.thenByDescending { it.added })
        }
    }

    fun get(ctx: Context, id: String): Item? { ensureLoaded(ctx); synchronized(lock) { return items[id] } }

    fun completed(ctx: Context) = list(ctx).filter { it.state == DONE && it.uri.isNotEmpty() }

    // ---------------------------------------------------------------- resume positions

    private fun posPrefs(ctx: Context) = ctx.getSharedPreferences("dlpos", Context.MODE_PRIVATE)

    fun savePos(ctx: Context, id: String, posMs: Long, durMs: Long) {
        if (durMs <= 0) return
        posPrefs(ctx).edit().putString(id, "$posMs,$durMs").apply()
    }

    /** (positionMs, durationMs) or null. */
    fun pos(ctx: Context, id: String): Pair<Long, Long>? {
        val s = posPrefs(ctx).getString(id, null) ?: return null
        val p = s.split(',')
        return try { p[0].toLong() to p[1].toLong() } catch (_: Exception) { null }
    }

    /** Where playback should start: saved position, unless (nearly) finished. */
    fun resumeAt(ctx: Context, id: String): Long {
        val (p, d) = pos(ctx, id) ?: return 0L
        return if (p < 5000 || p > d - 5000) 0L else p
    }

    // ---------------------------------------------------------------- reconcile with storage

    /** Moves pre-1.20 downloads into the folder, picks up files that are in the folder but not
     *  in the index, drops entries whose file was deleted elsewhere. Call off the main thread. */
    fun refresh(ctx: Context) {
        ensureLoaded(ctx)
        if (Build.VERSION.SDK_INT < 29) { pruneMissingFiles(ctx); return }
        val cr = ctx.contentResolver
        val base = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val proj = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.MIME_TYPE, MediaStore.MediaColumns.DATE_ADDED)

        if (!migrated && Build.VERSION.SDK_INT >= 30) {
            migrated = true
            try {
                cr.query(base, proj, "${MediaStore.MediaColumns.RELATIVE_PATH}=? OR ${MediaStore.MediaColumns.RELATIVE_PATH}=?",
                    arrayOf(Environment.DIRECTORY_DOWNLOADS + "/", Environment.DIRECTORY_DOWNLOADS), null)?.use { c ->
                    while (c.moveToNext()) {
                        val name = c.getString(1) ?: continue
                        val mime = c.getString(3) ?: ""
                        if (ID_IN_NAME.find(name) == null || !(mime.startsWith("video") || mime.startsWith("audio"))) continue
                        try {
                            cr.update(Uri.withAppendedPath(base, c.getLong(0).toString()),
                                ContentValues().apply { put(MediaStore.MediaColumns.RELATIVE_PATH, REL_PATH) }, null, null)
                        } catch (_: Exception) { }
                    }
                }
            } catch (_: Exception) { }
        }

        val seen = HashSet<String>()
        var dirty = false
        try {
            cr.query(base, proj, "${MediaStore.MediaColumns.RELATIVE_PATH}=? OR ${MediaStore.MediaColumns.RELATIVE_PATH}=?",
                arrayOf(REL_PATH, REL_PATH.trimEnd('/')), null)?.use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(1) ?: continue
                    val m = ID_IN_NAME.find(name) ?: continue
                    val id = m.groupValues[1]
                    val uri = Uri.withAppendedPath(base, c.getLong(0).toString()).toString()
                    seen.add(uri)
                    synchronized(lock) {
                        val it = items[id]
                        if (it != null && it.state == DOWNLOADING) return@synchronized
                        if (it == null) {
                            items[id] = Item(id).apply {
                                title = name.substring(0, m.range.first).trim().ifBlank { id }
                                this.uri = uri; this.name = name; size = c.getLong(2)
                                mime = c.getString(3) ?: "video/mp4"; added = c.getLong(4) * 1000
                            }
                            dirty = true
                        } else if (it.uri != uri && it.uri.isNotEmpty() && !exists(ctx, it.uri)) {
                            it.uri = uri; it.name = name; it.size = c.getLong(2); dirty = true
                        } else if (it.uri.isEmpty()) { it.uri = uri; dirty = true }
                    }
                }
            }
        } catch (_: Exception) { }
        // entries whose file is gone (deleted in the Files app etc.)
        val gone = synchronized(lock) {
            items.values.filter { it.state == DONE && it.uri !in seen && !exists(ctx, it.uri) }.map { it.id }
        }
        if (gone.isNotEmpty()) { synchronized(lock) { gone.forEach { items.remove(it) } }; dirty = true }
        // fill in what the page couldn't tell us (old downloads): length + a thumbnail frame
        val needMeta = synchronized(lock) {
            items.values.filter { it.state == DONE && it.uri.isNotEmpty() && (it.lengthSec <= 0 || (!it.isAudio && !thumbFile(ctx, it.id).exists())) }
        }
        val server = (ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
            .getString(MainActivity.PREF_SERVER, MainActivity.DEFAULT_SERVER) ?: MainActivity.DEFAULT_SERVER).trimEnd('/')
        needMeta.forEach { if (fillMeta(ctx, it, "$server/vi/${it.id}/mqdefault.jpg")) dirty = true }
        if (dirty) { save(ctx); changed(true) }
    }

    private fun pruneMissingFiles(ctx: Context) {
        val gone = synchronized(lock) { items.values.filter { it.state == DONE && !exists(ctx, it.uri) }.map { it.id } }
        if (gone.isNotEmpty()) { synchronized(lock) { gone.forEach { items.remove(it) } }; save(ctx); changed(true) }
    }

    private fun exists(ctx: Context, uri: String): Boolean {
        if (uri.isEmpty()) return false
        val u = Uri.parse(uri)
        if (u.scheme == "file") return File(u.path ?: return false).exists()
        return try {
            ctx.contentResolver.query(u, arrayOf(MediaStore.MediaColumns._ID), null, null, null)?.use { it.count > 0 } ?: false
        } catch (_: Exception) { false }
    }

    /** Length via MediaMetadataRetriever; thumbnail from the server, else a frame of the file. */
    private fun fillMeta(ctx: Context, it: Item, thumbUrl: String? = null): Boolean {
        var changed = false
        val thumb = thumbFile(ctx, it.id)
        if (!thumb.exists() && thumbUrl != null) { fetchThumb(thumbUrl, thumb); if (thumb.exists()) changed = true }
        if (it.lengthSec <= 0 || !thumb.exists()) {
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(ctx, Uri.parse(it.uri))
                val ms = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                if (it.lengthSec <= 0 && ms > 0) { it.lengthSec = (ms / 1000).toInt(); changed = true }
                if (!thumb.exists() && !it.isAudio) {
                    val bmp = r.getFrameAtTime(((ms / 10).coerceAtLeast(1000)) * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    if (bmp != null) {
                        thumb.outputStream().use { o -> bmp.compress(Bitmap.CompressFormat.JPEG, 85, o) }
                        changed = true
                    }
                }
            } catch (_: Exception) {
            } finally { try { r.release() } catch (_: Exception) { } }
        }
        return changed
    }

    private fun fetchThumb(url: String, out: File) {
        try {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 8000; c.readTimeout = 15000
            try {
                if (c.responseCode == 200) {
                    val tmp = File(out.path + ".part")
                    tmp.outputStream().use { o -> c.inputStream.use { it.copyTo(o) } }
                    if (tmp.length() > 0) tmp.renameTo(out) else tmp.delete()
                }
            } finally { c.disconnect() }
        } catch (_: Exception) { }
    }

    // ---------------------------------------------------------------- download

    sealed class StartResult {
        object Started : StartResult()
        object AlreadyDone : StartResult()
        object AlreadyRunning : StartResult()
    }

    fun start(ctx: Context, req: Request, onDone: (ok: Boolean, msg: String) -> Unit): StartResult {
        ensureLoaded(ctx)
        val app = ctx.applicationContext
        val item: Item
        synchronized(lock) {
            items[req.id]?.let { return if (it.state == DOWNLOADING) StartResult.AlreadyRunning else StartResult.AlreadyDone }
            item = Item(req.id).apply {
                title = req.title; author = req.author; authorId = req.authorId; lengthSec = req.lengthSec
                added = System.currentTimeMillis(); state = DOWNLOADING
            }
            items[req.id] = item
        }
        changed(true)
        Thread { run(app, req, item, onDone) }.start()
        return StartResult.Started
    }

    fun cancel(id: String) { synchronized(lock) { items[id]?.cancel = true } }

    private fun run(ctx: Context, req: Request, item: Item, onDone: (Boolean, String) -> Unit) {
        var open: HttpURLConnection? = null
        var target: Uri? = null
        var file: File? = null
        try {
            // thumbnail first: the Downloads row has its picture while the video downloads
            fetchThumb(req.thumbUrl, thumbFile(ctx, req.id))
            changed()

            var conn = URL(req.url).openConnection() as HttpURLConnection
            open = conn
            conn.instanceFollowRedirects = false
            conn.requestMethod = "POST"
            conn.doInput = true; conn.doOutput = true
            conn.connectTimeout = 15000; conn.readTimeout = 120000
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            req.cookie?.let { conn.setRequestProperty("Cookie", it) }
            conn.outputStream.use { it.write(req.body.toByteArray(Charsets.UTF_8)) }
            var code = conn.responseCode
            var hops = 0
            while (code in 300..399 && hops++ < 5) {                // POST redirects aren't auto-followed
                val loc = conn.getHeaderField("Location") ?: break
                val next = URL(conn.url, loc)                       // companion's Location is RELATIVE
                conn.disconnect()
                conn = next.openConnection() as HttpURLConnection
                open = conn
                conn.requestMethod = "GET"
                conn.connectTimeout = 15000; conn.readTimeout = 120000
                code = conn.responseCode
            }
            if (code !in 200..299) throw Exception("HTTP $code")
            var ctype = conn.contentType ?: "video/mp4"
            if (ctype.contains("json")) {                           // companion may answer with a URL
                val j = JSONObject(conn.inputStream.bufferedReader().readText())
                val u = j.optString("url").ifBlank { j.optString("downloadUrl") }
                if (u.isBlank()) throw Exception("no url in response")
                val next = URL(conn.url, u)
                conn.disconnect()
                conn = next.openConnection() as HttpURLConnection
                open = conn
                conn.connectTimeout = 15000; conn.readTimeout = 120000
                code = conn.responseCode
                if (code !in 200..299) throw Exception("HTTP $code")
                ctype = conn.contentType ?: "video/mp4"
            }
            val mime = ctype.substringBefore(';').trim().ifBlank { "video/mp4" }
            val ext = when {
                mime.contains("webm") -> "webm"
                mime.contains("matroska") || mime.contains("mkv") -> "mkv"
                mime.startsWith("audio/mp4") || mime.contains("m4a") -> "m4a"
                else -> "mp4"
            }
            val safe = req.title.replace(Regex("[\\\\/:*?\"<>|\\x00-\\x1f]"), "_").trim().take(140).ifBlank { "video" }
            val name = "$safe (${req.id}).$ext"
            item.mime = mime
            // the companion streams without Content-Length, but its videoplayback URL carries clen=
            val headerLen = conn.contentLengthLong
            item.total = if (headerLen > 0) headerLen else clenOf(conn.url)
            changed()

            val out = if (Build.VERSION.SDK_INT >= 29) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, mime)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, REL_PATH)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)          // invisible until complete
                }
                target = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: throw Exception("MediaStore refused the write")
                ctx.contentResolver.openOutputStream(target!!) ?: throw Exception("can't open file")
            } else {
                val d = File(ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), FOLDER).apply { mkdirs() }
                file = File(d, name)
                target = Uri.fromFile(file)
                file!!.outputStream()
            }
            out.use { o ->
                conn.inputStream.use { inp ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        if (item.cancel) throw CancelledException()
                        val n = inp.read(buf)
                        if (n < 0) break
                        o.write(buf, 0, n)
                        item.done += n
                        changed()
                    }
                }
            }
            if (headerLen > 0 && item.done < headerLen) throw Exception("connection closed early")
            if (Build.VERSION.SDK_INT >= 29) {
                ctx.contentResolver.update(target!!, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
                // MediaStore may have renamed it (name clash) — read back the final name
                try {
                    ctx.contentResolver.query(target!!, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                        if (c.moveToFirst()) item.name = c.getString(0) ?: name
                    }
                } catch (_: Exception) { }
            }
            if (item.name.isEmpty()) item.name = name
            item.uri = target.toString()
            item.size = item.done
            fillMeta(ctx, item, req.thumbUrl)
            item.state = DONE
            save(ctx)
            changed(true)
            onDone(true, "Downloaded: ${item.title}")
        } catch (e: Exception) {
            try { target?.let { if (it.scheme == "content") ctx.contentResolver.delete(it, null, null) } } catch (_: Exception) { }
            try { file?.delete() } catch (_: Exception) { }
            synchronized(lock) { items.remove(item.id) }
            thumbFile(ctx, item.id).delete()
            changed(true)
            onDone(false, if (e is CancelledException) "Download cancelled" else "Download failed: ${e.message}")
        } finally {
            try { open?.disconnect() } catch (_: Exception) { }
        }
    }

    private fun clenOf(u: URL): Long =
        Regex("(?:^|&)clen=(\\d+)").find(u.query ?: "")?.groupValues?.get(1)?.toLongOrNull() ?: -1L

    private class CancelledException : Exception("cancelled")

    // ---------------------------------------------------------------- delete

    /** Removes the file, its thumbnail, resume position and index entry. Off the main thread. */
    fun delete(ctx: Context, id: String) {
        ensureLoaded(ctx)
        val it = synchronized(lock) { items[id] } ?: return
        if (it.state == DOWNLOADING) { cancel(id); return }
        try {
            val u = Uri.parse(it.uri)
            if (u.scheme == "file") File(u.path!!).delete() else ctx.contentResolver.delete(u, null, null)
        } catch (_: Exception) { }
        thumbFile(ctx, id).delete()
        posPrefs(ctx).edit().remove(id).apply()
        synchronized(lock) { items.remove(id) }
        save(ctx)
        changed(true)
    }

    // ---------------------------------------------------------------- for the page

    /** {"s":"none"|"dl"|"done","p":pct} */
    fun stateJson(ctx: Context, id: String): String {
        val it = get(ctx, id)
        return JSONObject().put("s", when { it == null -> "none"; it.state == DOWNLOADING -> "dl"; else -> "done" })
            .put("p", it?.pct ?: -1).toString()
    }

    /** {"count":n,"bytes":b,"active":k} */
    fun summaryJson(ctx: Context): String {
        val l = list(ctx)
        return JSONObject().put("count", l.count { it.state == DONE })
            .put("bytes", l.filter { it.state == DONE }.sumOf { it.size })
            .put("active", l.count { it.state == DOWNLOADING }).toString()
    }

    /** id -> {"s","p"} for every known download (pushed to the page on change). */
    fun allStatesJson(ctx: Context): String {
        val o = JSONObject()
        list(ctx).forEach { o.put(it.id, JSONObject().put("s", if (it.state == DOWNLOADING) "dl" else "done").put("p", it.pct)) }
        return o.toString()
    }
}
