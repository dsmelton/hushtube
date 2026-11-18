package app.hushtube.android

import android.content.Context
import android.os.Build
import android.os.Environment
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Last-resort crash reporter.
 *
 * The Fold crashes on launch and there is no adb on the setup, so the trace has to come out of
 * the app itself: any uncaught exception is written to a file and replayed in a dialog the next
 * time the app starts (with a Copy button). File also lands in Android/data/.../files/crash.log
 * so `adb pull` works when a cable is available.
 */
object CrashReport {
    private const val TAG = "ivx-crash"
    private const val FILE = "crash.log"

    fun install(ctx: Context) {
        val app = ctx.applicationContext
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            try {
                val sw = StringWriter()
                val pw = PrintWriter(sw)
                pw.println("=== ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())} ===")
                pw.println("app ${pkgVersion(app)}  android ${Build.VERSION.SDK_INT}  ${Build.MANUFACTURER} ${Build.MODEL}")
                var t: Throwable? = e
                var depth = 0
                while (t != null) {
                    pw.println(if (depth == 0) "FATAL" else "Caused by:")
                    t.printStackTrace(pw)
                    t = t.cause
                    depth++
                }
                val text = sw.toString()
                Log.e(TAG, text)
                write(app, text)
            } catch (_: Throwable) { }
            prev?.uncaughtException(thread, e)
        }
    }

    private fun pkgVersion(ctx: Context): String = try {
        val i = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        "${i.versionName} (${i.longVersionCode})"
    } catch (_: Exception) { "?" }

    private fun candidates(ctx: Context): List<File> {
        val list = mutableListOf<File>()
        try { ctx.getExternalFilesDir(null)?.let { list.add(File(it, FILE)) } } catch (_: Exception) { }
        list.add(File(ctx.filesDir, FILE))
        // last resort: some launches die before either dir is usable
        try {
            Environment.getExternalStorageDirectory()?.let {
                list.add(File(it, "Download/ivx-crash.log"))
            }
        } catch (_: Exception) { }
        return list
    }

    private fun write(ctx: Context, text: String) {
        for (f in candidates(ctx)) {
            try {
                f.parentFile?.mkdirs()
                f.appendText(text + "\n")
                return
            } catch (_: Exception) { }
        }
    }

    /** Newest crash text this process can see, and forgets about it afterwards. */
    fun read(ctx: Context): String? {
        for (f in candidates(ctx)) {
            try {
                if (f.exists() && f.length() > 0) {
                    val s = f.readText()
                    f.delete()
                    return s.take(20000)
                }
            } catch (_: Exception) { }
        }
        return null
    }
}
