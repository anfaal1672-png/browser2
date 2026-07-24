package com.anfaal.gamebrowser

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat

/** Same desktop UA string the iOS build spoofs, so sites serve their desktop layout. */
const val DESKTOP_USER_AGENT =
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 " +
        "(KHTML, like Gecko) Version/17.4 Safari/605.1.15"

private var cachedBridgeScript: String? = null

private fun bridgeScript(context: Context): String {
    cachedBridgeScript?.let { return it }
    val text = context.assets.open("input_bridge.js").bufferedReader().use { it.readText() }
    cachedBridgeScript = text
    return text
}

@SuppressLint("SetJavaScriptEnabled")
private fun configureWebView(webView: WebView, viewModel: BrowserViewModel) {
    val context = webView.context
    val settings: WebSettings = webView.settings
    settings.javaScriptEnabled = true
    settings.domStorageEnabled = true
    settings.useWideViewPort = true
    settings.loadWithOverviewMode = true
    settings.mediaPlaybackRequiresUserGesture = false
    settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
    settings.userAgentString = DESKTOP_USER_AGENT
    settings.setSupportZoom(false)
    settings.builtInZoomControls = false

    webView.addJavascriptInterface(JsBridge(viewModel), "AndroidBridge")

    webView.webViewClient = object : WebViewClient() {
        override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
            // Re-inject at document start on every navigation — Android WebView has
            // no per-navigation user-script hook like WKUserScript, so this is the
            // closest equivalent (main-frame only; see input_bridge.js header comment
            // about the cross-origin-iframe gap vs. the iOS version).
            view.evaluateJavascript(bridgeScript(context), null)
        }

        override fun onPageFinished(view: WebView, url: String?) {
            viewModel.currentUrl = url
            viewModel.urlText = url ?: ""
            viewModel.canGoBack = view.canGoBack()
            viewModel.canGoForward = view.canGoForward()
            viewModel.applyKeyboardSuppression()
        }
    }

    webView.webChromeClient = object : WebChromeClient() {
        override fun onProgressChanged(view: WebView, newProgress: Int) {
            viewModel.progress = newProgress / 100f
            viewModel.isLoading = newProgress < 100
        }

        override fun onPermissionRequest(request: PermissionRequest) {
            val granted = request.resources.filter { resource ->
                val permission = when (resource) {
                    PermissionRequest.RESOURCE_VIDEO_CAPTURE -> android.Manifest.permission.CAMERA
                    PermissionRequest.RESOURCE_AUDIO_CAPTURE -> android.Manifest.permission.RECORD_AUDIO
                    else -> null
                }
                permission != null &&
                    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
            }.toTypedArray()
            if (granted.isNotEmpty()) request.grant(granted) else request.deny()
        }
    }
}

/** Hosts the app's single WebView (tabs aren't ported yet — phase 1 is one page). */
@Composable
fun GameWebView(viewModel: BrowserViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val webView = remember {
        WebView(context).apply {
            configureWebView(this, viewModel)
            loadUrl("https://www.google.com")
        }.also { viewModel.webView = it }
    }
    AndroidView(
        factory = { webView },
        modifier = modifier.fillMaxSize(),
    )
}
