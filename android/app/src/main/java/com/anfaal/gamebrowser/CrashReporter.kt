package com.anfaal.gamebrowser

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Shows crashes on screen instead of letting the app silently disappear.
 *
 * Without a USB-debugging setup there's no way to read logcat off the
 * device, so a launch-time crash is indistinguishable from "the app just
 * closes" — which is exactly the report we got and could not diagnose. This
 * installs a process-wide uncaught-exception handler that writes the stack
 * trace to a file and hands off to [CrashActivity], so the failure is
 * visible (and screenshot-able) on the device itself.
 *
 * Debug-build diagnostics: this is deliberately loud rather than graceful.
 */
class GameBrowserApp : Application() {

    override fun onCreate() {
        super.onCreate()
        installCrashHandler(this)
    }

    companion object {
        private const val CRASH_FILE = "last_crash.txt"

        fun crashFile(context: Context) = File(context.filesDir, CRASH_FILE)

        fun installCrashHandler(context: Context) {
            val appContext = context.applicationContext
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, error ->
                try {
                    val writer = StringWriter()
                    error.printStackTrace(PrintWriter(writer))
                    val report = buildString {
                        appendLine("GameBrowser crash report")
                        appendLine("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                        appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
                        appendLine("Thread: ${thread.name}")
                        appendLine()
                        append(writer.toString())
                    }
                    crashFile(appContext).writeText(report)

                    val intent = Intent(appContext, CrashActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                        putExtra(CrashActivity.EXTRA_REPORT, report)
                    }
                    appContext.startActivity(intent)
                } catch (secondary: Throwable) {
                    // Never let the reporter itself mask the original crash.
                }
                previous?.uncaughtException(thread, error)
                    ?: Runtime.getRuntime().exit(1)
            }
        }
    }
}

/** Plain-View (no Compose — Compose itself may be what crashed) crash display. */
class CrashActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val report = intent?.getStringExtra(EXTRA_REPORT)
            ?: runCatching { GameBrowserApp.crashFile(this).readText() }.getOrNull()
            ?: "No crash report recorded."

        val text = TextView(this).apply {
            setPadding(32, 64, 32, 32)
            textSize = 11f
            setTextIsSelectable(true)
            setText(report)
        }
        setContentView(ScrollView(this).apply { addView(text) })
    }

    companion object {
        const val EXTRA_REPORT = "report"
    }
}
