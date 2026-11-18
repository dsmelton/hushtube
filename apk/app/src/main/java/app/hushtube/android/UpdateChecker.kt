package app.hushtube.android

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Checks a hosted version.json ({"versionCode":N,"versionName":"x.y","url":"..."})
 * and offers an update download when the hosted versionCode is newer.
 * Any failure (offline, 404, bad JSON) is silently ignored.
 */
object UpdateChecker {
    private const val TAG = "UpdateChecker"

    fun check(context: Context, currentVersionCode: Int, url: String) {
        Thread {
            try {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                if (conn.responseCode != 200) return@Thread
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect()
                val json = JSONObject(body)
                val latest = json.optInt("versionCode", currentVersionCode)
                if (latest <= currentVersionCode) return@Thread
                val name = json.optString("versionName", "$latest")
                val apkUrl = json.optString("url", url)
                val app = context.applicationContext
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    AlertDialog.Builder(app)
                        .setTitle("Update available")
                        .setMessage("Version $name is available.")
                        .setPositiveButton("Download") { _, _ ->
                            try {
                                app.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse(apkUrl))
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            } catch (_: Exception) {
                            }
                        }
                        .setNegativeButton("Later", null)
                        .show()
                }
            } catch (e: Exception) {
                Log.d(TAG, "update check skipped: ${e.message}")
            }
        }.start()
    }
}
