package com.anfaal.gamebrowser

import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

/** Same desktop UA string every tab's WebView is configured with (see TabManager.kt), so sites serve their desktop layout. */
const val DESKTOP_USER_AGENT =
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 " +
        "(KHTML, like Gecko) Version/17.4 Safari/605.1.15"

/**
 * Hosts whichever tab is currently active. [TabManager] owns every tab's own
 * WebView (creation, configuration, navigation) — this composable's only job
 * is to swap the visible WebView into a plain container when the active tab
 * changes, since a WebView can only have one parent at a time.
 */
@Composable
fun GameWebView(viewModel: BrowserViewModel, modifier: Modifier = Modifier) {
    val activeWebView = viewModel.tabManager.activeWebView
    AndroidView(
        factory = { context -> FrameLayout(context) },
        update = { container ->
            val current = activeWebView ?: return@AndroidView
            if (container.childCount == 1 && container.getChildAt(0) === current) return@AndroidView
            container.removeAllViews()
            (current.parent as? ViewGroup)?.removeView(current)
            container.addView(current, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        },
        modifier = modifier.fillMaxSize(),
    )
}
