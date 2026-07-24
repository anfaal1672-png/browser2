package com.anfaal.gamebrowser

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicInteger

/**
 * Android side of the Web Notification API bridge. Mirrors
 * BrowserViewModel.swift's `postWebNotification(title:body:)`, which is fed
 * by InputBridge.swift's `NotificationShim` (`new Notification(title, {body})`)
 * — here the equivalent shim is notification_bridge.js, and this is the
 * receiving end reached via JsBridge.kt's message dispatch (see the
 * "INTEGRATION STEPS" note in the porting report for the exact `"notification"`
 * case JsBridge.kt needs to grow).
 *
 * Two things Android needs that iOS's UNUserNotificationCenter doesn't:
 *  - A [NotificationChannel], required on API 26+ — which, since this app's
 *    minSdk is already 26, means always. It groups these notifications so
 *    the user can mute/configure them independently of anything else the
 *    app might notify about.
 *  - The runtime `POST_NOTIFICATIONS` permission on API 33+ (Android 13).
 *    Below API 33 notification permission is granted at install time (the
 *    user can still disable it in system settings, which
 *    [NotificationManagerCompat.areNotificationsEnabled] reports).
 *
 * This object only *shows* notifications and *checks* whether it's allowed
 * to. It deliberately does not request the POST_NOTIFICATIONS permission
 * itself — like BiometricPrompt in AppLock.kt, requesting a runtime
 * permission needs an Activity-hosted launcher registered ahead of time, so
 * that responsibility belongs to MainActivity (see "INTEGRATION STEPS").
 */
object NotificationBridge {
    private const val CHANNEL_ID = "web_notifications"
    private const val CHANNEL_NAME = "Web notifications"
    private const val CHANNEL_DESCRIPTION =
        "Notifications posted by web pages using the Notification API"

    /** Distinct IDs so multiple notifications stack instead of replacing each other. */
    private val nextNotificationId = AtomicInteger(1000)

    @Volatile
    private var channelEnsured = false

    /**
     * Creates the notification channel if it doesn't already exist. Cheap
     * and idempotent — safe to call before every [show].
     */
    fun ensureChannel(context: Context) {
        if (channelEnsured || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            channelEnsured = true
            return
        }
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = CHANNEL_DESCRIPTION
            }
            manager.createNotificationChannel(channel)
        }
        channelEnsured = true
    }

    /**
     * Mirrors checking `UNUserNotificationCenter`'s settings for
     * `.authorized`/`.provisional` before delivering. On API 33+ this
     * includes the runtime POST_NOTIFICATIONS permission; on every version
     * it also respects the user having disabled notifications for the app
     * in system settings.
     */
    fun areNotificationsPermitted(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return false
        }
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    /**
     * Shows a local notification for a `new Notification(title, {body})` call
     * from a web page. Mirrors `postWebNotification(title:body:)`.
     *
     * The caller (BrowserViewModel, dispatched from JsBridge's `"notification"`
     * message) is responsible for the `webNotificationsEnabled` master-switch
     * gate before calling this — exactly like the iOS
     * `guard webNotificationsEnabled else { return }` guard clause.
     *
     * Silently no-ops if permission isn't granted, matching the iOS behavior
     * of only delivering on `.authorized`/`.provisional` — never blocking or
     * throwing back into the page for a declined/undetermined permission.
     */
    fun show(context: Context, title: String, body: String) {
        if (!areNotificationsPermitted(context)) return
        ensureChannel(context)

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(title.ifBlank { "GameBrowser" })
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()

        try {
            NotificationManagerCompat.from(context)
                .notify(nextNotificationId.getAndIncrement(), notification)
        } catch (e: SecurityException) {
            // Permission revoked between the areNotificationsPermitted() check
            // above and notify() (e.g. flipped in system settings mid-call).
        }
    }
}
