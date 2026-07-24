package com.anfaal.gamebrowser

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * Android port of BrowserViewModel.swift's background keep-alive
 * (`keepAliveInBackground` / `startSilence()` / `stopSilence()`).
 *
 * iOS trick: play a looping, silent `AVAudioEngine` buffer under the
 * `.playback` audio session category. iOS's "audio" background mode then
 * keeps the whole app process — and with it the WKWebView, its JS timers,
 * sockets, and this app's Notification bridge — alive and running while
 * backgrounded, at the cost of battery (hence it's opt-in).
 *
 * Android has no equivalent "produce audio -> get to keep running" loophole,
 * and no "keep a WebView's timers alive" background mode at all. A plain
 * background [Service] with no foreground notification is throttled and
 * then killed by App Standby / Doze within seconds to a few minutes of the
 * app leaving the foreground on modern (8.0+) Android — there is no way
 * around that short of a foreground service. So this is a minimal foreground
 * service: it does no work itself, it just holds a persistent, low-priority
 * notification so the OS treats the process as user-facing and leaves the
 * WebView's process running (and its JS timers/sockets with it) while the
 * user has this feature turned on.
 *
 * Honesty note on foreground service *type*: Android's foreground-service
 * type taxonomy (`dataSync`, `mediaPlayback`, `location`, `camera`,
 * `microphone`, ...) describes concrete OS resources a service is actively
 * using. There is no type for "keep an unrelated WebView's JS timers
 * running" because that isn't a resource Android tracks — this service
 * doesn't play media, sync data, or use the camera/mic/location itself.
 * `FOREGROUND_SERVICE_TYPE_SPECIAL_USE` (API 34+/Android 14) is the closest
 * available fit for a use case that doesn't map to any of the concrete
 * types, but it:
 *  - requires an explicit `<property android:name=
 *    "android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE" android:value="...">`
 *    describing the use case in the manifest's `<service>` entry, and the
 *    `FOREGROUND_SERVICE_SPECIAL_USE` permission (see "INTEGRATION STEPS"),
 *  - is explicitly policed by Play Store review — "keep a webpage/process
 *    alive in the background" is close to the exact pattern Google designed
 *    special-use review to catch, so approval is a product/policy risk, not
 *    something this code can guarantee,
 *  - doesn't exist at all before API 34; on older OS versions a foreground
 *    service can still be started (with no declared type, or type `0`) but
 *    gets none of the special-use framing either.
 * This file does not invent a friendlier-sounding type that doesn't exist —
 * `SPECIAL_USE` is used as-is, gated to API 34+, with `0` (no specific type)
 * below that.
 */
class KeepAliveService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat()
        // START_STICKY: best-effort "keep trying" — if the OS still kills the
        // process under memory pressure, ask it to recreate this service
        // (with a null intent) rather than dropping it for good. This
        // matches keepAliveInBackground being an opt-in, battery-costing,
        // best-effort feature on iOS too (the OS can still suspend an app
        // playing silence if it's aggressive enough about memory).
        return START_STICKY
    }

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    private fun startForegroundCompat() {
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("GameBrowser is running")
            .setContentText("Keeping your session active in the background")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Background activity",
                NotificationManager.IMPORTANCE_LOW, // low importance: silent, no heads-up
            ).apply {
                description = "Keeps GameBrowser running while backgrounded"
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }
    }

    companion object {
        private const val CHANNEL_ID = "keep_alive"
        private const val NOTIFICATION_ID = 42

        /** Start (or no-op if already running) the keep-alive foreground service. */
        fun start(context: Context) {
            val intent = Intent(context, KeepAliveService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        /** Stop the keep-alive service, dropping its foreground notification. */
        fun stop(context: Context) {
            context.stopService(Intent(context, KeepAliveService::class.java))
        }
    }
}
