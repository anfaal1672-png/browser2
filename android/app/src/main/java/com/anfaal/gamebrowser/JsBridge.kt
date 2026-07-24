package com.anfaal.gamebrowser

import android.webkit.JavascriptInterface
import android.os.Handler
import android.os.Looper
import org.json.JSONObject

/**
 * Receives postMessage calls from input_bridge.js (window.AndroidBridge.postMessage).
 * Mirrors the WKScriptMessageHandler side of InputBridge.swift / BrowserViewModel's
 * handleScriptMessage. WebView calls this on a background thread, so callbacks are
 * hopped back onto the main thread before touching Compose state.
 */
class JsBridge(private val viewModel: BrowserViewModel) {
    private val mainHandler = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun postMessage(json: String) {
        val obj = try { JSONObject(json) } catch (e: Exception) { return }
        val type = obj.optString("type")
        mainHandler.post {
            when (type) {
                "cursorstyle" -> viewModel.handleBridgeMessage("cursorstyle", obj.optString("style", "auto"))
                "pointerlock" -> viewModel.pointerLocked = obj.optBoolean("locked", false)
            }
        }
    }
}
