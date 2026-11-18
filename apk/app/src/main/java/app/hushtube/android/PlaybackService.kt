package app.hushtube.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import androidx.core.app.NotificationCompat
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures

/**
 * Keeps audio alive when the app is minimized or the phone sleeps, and shows a REAL media
 * notification: this is a Media3 MediaSessionService, so the notification — and the lock screen,
 * quick settings, Bluetooth / Android Auto surfaces — are generated from the MediaSession itself
 * (artwork, title/artist, scrubber, transport controls) instead of a hand-built plain one.
 *
 * The ExoPlayer still lives in MiniPlayer (same process while this service runs, so no
 * cross-process plumbing and no gap at hand-off); we only wrap it in a MediaSession and let
 * Media3 own the notification.
 */
@UnstableApi
class PlaybackService : MediaSessionService() {

    companion object {
        /** Custom session command behind the notification's "Close" button. */
        val CMD_CLOSE = SessionCommand("app.hushtube.android.CLOSE", Bundle.EMPTY)

        // Post the contract-satisfying placeholder on Media3's OWN channel + id, so the real
        // media notification replaces it in place instead of appearing next to it.
        private const val CHANNEL = DefaultMediaNotificationProvider.DEFAULT_CHANNEL_ID
        private const val ID = DefaultMediaNotificationProvider.DEFAULT_NOTIFICATION_ID

        /** Set by MainActivity while the mini player owns playback; drives the Close button. */
        @Volatile
        var ui: BgHandler? = null

        // The player to wrap; set by the Activity right before start(), read in onStartCommand.
        @Volatile
        private var running: Player? = null

        /**
         * startForegroundService() is required for the FIRST start: the service must promote
         * itself to foreground within seconds or the OS kills the process (v1.23 died exactly
         * there). It is also how a new player instance gets adopted.
         */
        fun start(ctx: Context, player: Player) {
            running = player
            try {
                val i = Intent(ctx, PlaybackService::class.java)
                if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
            } catch (e: Exception) {
                android.util.Log.w("ivx", "playback service start failed", e)
            }
        }

        /**
         * Stopping must NOT use startForegroundService (that arms the startForeground() contract;
         * see the v1.23 crash). stopService() is a clean no-op when not running and tears the
         * session + notification down via onDestroy() when running.
         */
        fun stop(ctx: Context) {
            running = null
            try { ctx.stopService(Intent(ctx, PlaybackService::class.java)) } catch (_: Exception) { }
        }
    }

    /** What the notification's Close button does (implemented by MainActivity). */
    interface BgHandler {
        fun bgStop()
    }

    private var player: Player? = null
    private var session: MediaSession? = null
    // The contract placeholder is needed exactly once per service instance; re-posting it on
    // later start commands would overwrite Media3's media notification (same id).
    private var placeholderPosted = false

    override fun onCreate() {
        super.onCreate()
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (mgr.getNotificationChannel(CHANNEL) == null) {
            val c = NotificationChannel(CHANNEL, "Playback", NotificationManager.IMPORTANCE_LOW)
            c.description = "Video audio kept playing in the background"
            c.setShowBadge(false)
            mgr.createNotificationChannel(c)
        }
    }

    /**
     * Wrap the current player in a MediaSession. A MediaSession cannot be re-attached to another
     * player, so a new video (new ExoPlayer instance) means a new session; the old one is released.
     */
    private fun ensure() {
        val p = running ?: return
        if (p === player && session != null) return
        session?.let { try { removeSession(it) } catch (_: Exception) { }; it.release() }
        session = null
        player?.removeListener(idleStop)
        player = p
        try {
            // Notification buttons: rewind / play-pause / forward + our Close.
            val prefs = listOf(
                CommandButton.Builder()
                    .setDisplayName("Back 10 seconds")
                    .setIconResId(androidx.media3.session.R.drawable.media3_icon_skip_back_10)
                    .setPlayerCommand(Player.COMMAND_SEEK_BACK)
                    .build(),
                CommandButton.Builder()
                    .setDisplayName("Forward 10 seconds")
                    .setIconResId(androidx.media3.session.R.drawable.media3_icon_skip_forward_10)
                    .setPlayerCommand(Player.COMMAND_SEEK_FORWARD)
                    .build(),
                CommandButton.Builder()
                    .setDisplayName("Close")
                    .setIconResId(R.drawable.ic_dl_close)
                    .setSessionCommand(CMD_CLOSE)
                    .build())
            val s = MediaSession.Builder(this, p)
                .setId("ivx-" + System.identityHashCode(p))
                .setSessionActivity(PendingIntent.getActivity(
                    this, 3, Intent(this, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
                .setMediaButtonPreferences(prefs)
                .setCallback(object : MediaSession.Callback {
                    override fun onCustomCommand(
                        s: MediaSession, c: MediaSession.ControllerInfo,
                        cmd: SessionCommand, args: Bundle
                    ): com.google.common.util.concurrent.ListenableFuture<SessionResult> {
                        if (cmd.customAction == CMD_CLOSE.customAction) ui?.bgStop()
                        return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                    }
                })
                .build()
            session = s
            // MUST go through addSession(): MediaNotificationManager.updateNotification() refuses
            // to build the media notification unless isSessionAdded(session) is true, and only
            // this call registers it — a list of our own is invisible to it.
            addSession(s)
            p.addListener(idleStop)
        } catch (e: Throwable) {
            android.util.Log.w("ivx", "media session refused", e)
        }
    }

    /** Player released / stopped elsewhere -> nothing left to keep alive. */
    private val idleStop = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_IDLE) stop(this@PlaybackService)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Honour the startForegroundService() contract before anything else (v1.23 lesson).
        // Only while Media3 has NOT taken the foreground yet: notifyBg() re-enters this method on
        // every player event, and re-posting the placeholder would clobber the real media
        // notification (title/artwork/scrubber) that Media3 owns.
        if (!placeholderPosted) {
            placeholderPosted = true
            try {
                if (Build.VERSION.SDK_INT >= 29)
                    startForeground(ID, placeholder(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
                else startForeground(ID, placeholder())
            } catch (e: Exception) {
                android.util.Log.w("ivx", "startForeground refused", e)
            }
        }
        ensure()
        return START_STICKY
    }

    private fun placeholder(): Notification = NotificationCompat.Builder(this, CHANNEL)
        .setSmallIcon(R.drawable.ic_notify_play)
        .setContentTitle(getString(R.string.app_name))
        .setContentText("Playing")
        .setOngoing(true)
        .setSilent(true)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .build()

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val s = session
        if (s == null || !s.player.playWhenReady) {
            if (s != null) { try { removeSession(s) } catch (_: Exception) { }; try { s.release() } catch (_: Exception) { } }
            session = null
            stopSelf()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        session?.let { try { removeSession(it) } catch (_: Exception) { }; try { it.release() } catch (_: Exception) { } }
        session = null
        player?.removeListener(idleStop); player = null
        running = null
        super.onDestroy()
    }
}
