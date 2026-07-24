package com.anfaal.gamebrowser

import android.content.Context
import android.webkit.JavascriptInterface
import android.os.Handler
import android.os.Looper
import org.json.JSONObject

/**
 * Receives postMessage calls from input_bridge.js / autofill_bridge.js /
 * notification_bridge.js (all post via window.AndroidBridge.postMessage).
 * Mirrors the WKScriptMessageHandler side of InputBridge.swift / BrowserViewModel's
 * handleScriptMessage. WebView calls this on a background thread, so callbacks are
 * hopped back onto the main thread before touching Compose state.
 */
class JsBridge(context: Context, private val viewModel: BrowserViewModel) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun postMessage(json: String) {
        val obj = try { JSONObject(json) } catch (e: Exception) { return }
        val type = obj.optString("type")
        mainHandler.post {
            when (type) {
                "cursorstyle" -> viewModel.handleBridgeMessage("cursorstyle", obj.optString("style", "auto"))
                "pointerlock" -> viewModel.pointerLocked = obj.optBoolean("locked", false)
                "autofillFocus" -> viewModel.handleAutofillFocus(obj.optString("kind"))
                "credentialSubmitted" -> viewModel.handleCredentialSubmitted(
                    obj.optString("username"),
                    obj.optString("password"),
                )
                "notification" -> if (viewModel.webNotificationsEnabled) {
                    NotificationBridge.show(
                        context = appContext,
                        title = obj.optString("title", ""),
                        body = obj.optString("body", ""),
                    )
                }
                "notificationPermission" -> {
                    // Runtime POST_NOTIFICATIONS (API 33+) is requested eagerly
                    // by MainActivity at launch, same as camera/mic/location —
                    // nothing further to do on demand here.
                }
            }
        }
    }
}
