package com.anfaal.gamebrowser

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.view.ViewGroup
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max

/** Default page for a brand-new tab or the very first launch (mirrors GameWebView.kt's default). */
private const val DEFAULT_HOME_URL = "https://www.google.com"

/** How long to wait after a page finishes loading before restoring its saved scroll offset. */
private const val SCROLL_RESTORE_DELAY_MS = 600L

/** Debounce window for persisting tabs after a URL change, so rapid navigations don't thrash disk. */
private const val SAVE_DEBOUNCE_MS = 1000L

private var cachedBridgeScript: String? = null

/** Reads+caches assets/input_bridge.js, mirroring GameWebView.kt's private bridgeScript(). Kept as
 *  its own copy here because that original is file-private and this file must not edit GameWebView.kt. */
private fun bridgeScript(context: Context): String {
    cachedBridgeScript?.let { return it }
    val text = context.assets.open("input_bridge.js").bufferedReader().use { it.readText() }
    cachedBridgeScript = text
    return text
}

/**
 * One browser tab: its own WebView instance plus the bits needed to show it
 * in the tab switcher and to persist/restore it. Mirrors BrowserViewModel.Tab
 * from the iOS app (webView + snapshot + pendingURL + pendingScroll).
 *
 * Only [TabManager] should construct these (via its internal `createTab()`),
 * so that the WebView is always fully configured (JS bridge, clients, etc.)
 * before it's used.
 */
class Tab(val webView: WebView) {
    companion object {
        private var nextId = 0
    }

    /** Stable identity for Compose list keys (mirrors iOS Tab: Identifiable's UUID). */
    val id: Int = nextId++

    /** Thumbnail shown in the tab switcher grid; refreshed by [TabManager.snapshotActiveTab]. */
    var snapshot: Bitmap? by mutableStateOf<Bitmap?>(null)

    /** Page title, updated as the page loads / via onReceivedTitle. */
    var title: String by mutableStateOf("")

    /** Last known URL for this tab (may lag pendingUrl while a load is in flight). */
    var url: String? by mutableStateOf<String?>(null)

    var isLoading: Boolean by mutableStateOf(false)
    var progress: Float by mutableStateOf(0f)
    var canGoBack: Boolean by mutableStateOf(false)
    var canGoForward: Boolean by mutableStateOf(false)

    /**
     * Last requested URL; used for persistence while the page is still
     * loading (webView.url can lag or briefly report null/about:blank).
     */
    var pendingUrl: String? = null

    /** Scroll offset to restore once this tab's page finishes loading; cleared once consumed. */
    var pendingScrollX: Int? = null
    var pendingScrollY: Int? = null

    /** Title for display in the tab switcher, falling back to the host like ContentView's TabCard. */
    val displayTitle: String
        get() {
            if (title.isNotBlank()) return title
            val host = try {
                url?.let { android.net.Uri.parse(it).host }
            } catch (e: Exception) {
                null
            }
            return host ?: "New Tab"
        }
}

/**
 * Owns every tab's WebView and the active-tab index, and persists tabs
 * (URL + scroll position + thumbnail) across process death. Mirrors the
 * tabs/activeTabIndex/makeWebView/newTab/selectTab/closeTab/saveTabs/
 * restoreTabs surface of BrowserViewModel.swift; KVO observers there become
 * WebViewClient/WebChromeClient callbacks wired per-tab here.
 *
 * [viewModel] supplies the *shared* virtual mouse/keyboard state (cursor
 * position, pressed keys, drag lock, ...) that is global across tabs on iOS
 * too (BrowserViewModel's cursorPosition/pressedKeys aren't per-Tab there
 * either) — only navigation state (url/progress/canGoBack/...) is per-tab
 * and gets pushed into [viewModel] whenever the *active* tab is the one
 * that changed.
 *
 * Not a ViewModel itself (so it has no Android lifecycle of its own) —
 * whoever constructs it owns calling [onAppBackgrounded] and [dispose] at
 * the right times. See the port's integration notes for exact call sites.
 */
class TabManager(
    private val context: Context,
    private val viewModel: BrowserViewModel,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var saveTabsJob: Job? = null

    private val _tabs = mutableStateListOf<Tab>()

    /** Read-only view of the open tabs, in display order. Mutate only via newTab/closeTab. */
    val tabs: List<Tab> get() = _tabs

    /** -1 until the first tab is selected (during [restoreTabs] / the initial [newTab]). */
    var activeIndex: Int by mutableStateOf(-1)
        private set

    val activeTab: Tab? get() = tabs.getOrNull(activeIndex)

    /** What the UI/ViewModel should treat as "the" WebView right now. */
    val activeWebView: WebView? get() = activeTab?.webView

    init {
        restoreTabs()
    }

    // MARK: - Tab creation

    private fun createTab(): Tab {
        val webView = WebView(context)
        val tab = Tab(webView)
        configureWebView(webView, tab)
        return tab
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView(webView: WebView, tab: Tab) {
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
        webView.setBackgroundColor(android.graphics.Color.BLACK)

        webView.addJavascriptInterface(JsBridge(viewModel), "AndroidBridge")

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                // Re-inject at document start on every navigation, same as GameWebView.kt's
                // single-WebView setup (Android has no per-navigation WKUserScript equivalent).
                view.evaluateJavascript(bridgeScript(context), null)
            }

            override fun onPageFinished(view: WebView, url: String?) {
                tab.url = url
                tab.title = view.title ?: ""
                tab.canGoBack = view.canGoBack()
                tab.canGoForward = view.canGoForward()
                tab.pendingUrl = null

                // Only the currently active tab drives the visible toolbar state —
                // a background tab finishing a load must not steal the address bar.
                if (tab === activeTab) {
                    viewModel.currentUrl = url
                    viewModel.urlText = url ?: ""
                    viewModel.canGoBack = tab.canGoBack
                    viewModel.canGoForward = tab.canGoForward
                    viewModel.applyKeyboardSuppression()
                }

                val scrollX = tab.pendingScrollX
                val scrollY = tab.pendingScrollY
                if (scrollX != null || scrollY != null) {
                    tab.pendingScrollX = null
                    tab.pendingScrollY = null
                    val expectedUrl = url
                    // Guard against the tab having navigated elsewhere during the wait —
                    // mirrors the iOS didFinish handler's expectedURL check.
                    scope.launch {
                        delay(SCROLL_RESTORE_DELAY_MS)
                        if (view.url == expectedUrl) {
                            view.scrollTo(scrollX ?: 0, scrollY ?: 0)
                        }
                    }
                }

                saveTabsDebounced()
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                tab.progress = newProgress / 100f
                tab.isLoading = newProgress < 100
                if (tab === activeTab) {
                    viewModel.progress = tab.progress
                    viewModel.isLoading = tab.isLoading
                }
            }

            override fun onReceivedTitle(view: WebView, title: String?) {
                tab.title = title ?: ""
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

    // MARK: - Tabs: create / select / close

    fun newTab(url: String? = null) {
        val target = url ?: DEFAULT_HOME_URL
        val tab = createTab()
        tab.pendingUrl = target
        _tabs.add(tab)
        selectTab(_tabs.size - 1)
        tab.webView.loadUrl(target)
        saveTabs()
    }

    /**
     * Switches the active tab: snapshots the outgoing tab, pauses its
     * WebView, releases any held virtual keys and resets pointer-lock/drag
     * state, then resumes the incoming tab and pushes its nav state into
     * [viewModel]. Ported from BrowserViewModel.swift's selectTab(_:).
     *
     * No-ops if [index] is already the active tab — switching tabs is the
     * only thing that should release held keys / drop drag-lock; merely
     * re-tapping the already-active tab in the grid must not do that.
     */
    fun selectTab(index: Int) {
        if (index !in tabs.indices) return
        if (index == activeIndex) return

        snapshotActiveTab()
        // Suspend the outgoing tab's timers/media (per-instance, unlike the
        // global WebView.pauseTimers()) — mirrors setAllMediaPlaybackSuspended.
        activeTab?.webView?.onPause()

        viewModel.releaseAllKeys()
        if (viewModel.dragLocked) viewModel.mouseUp()

        activeIndex = index
        val tab = tabs[index]
        tab.webView.onResume()
        viewModel.webView = tab.webView

        viewModel.pointerLocked = false
        viewModel.pageHidesCursor = false
        viewModel.cursorStyle = "auto"
        viewModel.dragLocked = false

        viewModel.progress = tab.progress
        viewModel.isLoading = tab.isLoading
        viewModel.canGoBack = tab.canGoBack
        viewModel.canGoForward = tab.canGoForward
        viewModel.currentUrl = tab.url
        viewModel.urlText = tab.url ?: ""
        viewModel.applyKeyboardSuppression()

        saveTabs()
    }

    /**
     * Closes the tab at [index]. Ported from BrowserViewModel.swift's
     * closeTab(_:), including its two recent fixes:
     *  - closing the active tab selects whatever slides into its slot
     *    (`min(index, tabs.size - 1)`), not always `index - 1`.
     *  - closing a *background* tab before the active one only shifts
     *    [activeIndex] — it deliberately does NOT go through [selectTab],
     *    which would otherwise wrongly release held keys / reset drag and
     *    pointer-lock state on the still-active tab.
     */
    fun closeTab(index: Int) {
        if (index !in tabs.indices) return
        val wasActive = index == activeIndex

        val tab = _tabs.removeAt(index)
        tab.webView.stopLoading()
        tab.webView.onPause()
        // Must be detached before destroy() if it's still mounted anywhere.
        (tab.webView.parent as? ViewGroup)?.removeView(tab.webView)
        tab.webView.destroy()

        when {
            tabs.isEmpty() -> {
                activeIndex = -1
                newTab()
            }
            wasActive -> {
                // Force selectTab to actually run even if the post-removal math
                // happens to land on the same numeric slot as the old activeIndex —
                // the *tab* there is a different one now, so the switch is real.
                activeIndex = -1
                selectTab(minOf(index, tabs.size - 1))
            }
            index < activeIndex -> {
                // A tab before the active one closed - the active tab's own
                // webview/state is unaffected, so just shift the index.
                activeIndex -= 1
            }
            // else: a tab after the active one closed - activeIndex unaffected.
        }
        saveTabs()
    }

    // MARK: - Snapshots (tab-switcher thumbnails)

    private fun snapshotsDir(): File {
        val dir = File(context.cacheDir, "tab_snapshots")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /** Capture a thumbnail of the currently displayed tab for the tab switcher. */
    fun snapshotActiveTab() {
        val tab = activeTab ?: return
        val webView = tab.webView
        // Don't capture a WebView that isn't actually on screen yet (e.g.
        // during launch restore) - it would overwrite the persisted
        // thumbnail with a blank image, same guard as the iOS version.
        if (webView.width <= 0 || webView.height <= 0 || webView.parent == null || tab.url == null) return
        val bitmap = try {
            Bitmap.createBitmap(webView.width, webView.height, Bitmap.Config.ARGB_8888).also { bmp ->
                webView.draw(Canvas(bmp))
            }
        } catch (e: Exception) {
            null
        } ?: return
        tab.snapshot = bitmap
        saveSnapshots()
    }

    /** Persist thumbnails so the tab switcher isn't blank after a relaunch. */
    private fun saveSnapshots() {
        val dir = snapshotsDir()
        val images = tabs.map { it.snapshot }
        scope.launch(Dispatchers.IO) {
            images.forEachIndexed { i, bitmap ->
                if (bitmap == null) return@forEachIndexed
                try {
                    FileOutputStream(File(dir, "$i.jpg")).use { out ->
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 50, out)
                    }
                } catch (e: Exception) {
                    // Best-effort; a missing thumbnail just falls back to the placeholder.
                }
            }
            // Drop stale files from closed tabs.
            var i = images.size
            while (true) {
                val file = File(dir, "$i.jpg")
                if (!file.exists()) break
                file.delete()
                i++
            }
        }
    }

    // MARK: - Tab persistence (URL + scroll position)

    private data class TabRecord(val url: String, val scrollX: Int, val scrollY: Int)

    private fun tabsFile(): File = File(context.filesDir, "tabs.json")

    private fun saveTabsDebounced() {
        saveTabsJob?.cancel()
        saveTabsJob = scope.launch {
            delay(SAVE_DEBOUNCE_MS)
            saveTabs()
        }
    }

    /** Persists every tab's URL + scroll offset + the active index, then re-saves thumbnails. */
    fun saveTabs() {
        val array = JSONArray()
        for (tab in tabs) {
            val liveUrl = tab.webView.url
            // A load that hasn't reported its own url yet (or reports
            // about:blank) falls back to the last URL we asked it to load.
            val urlString = if (liveUrl.isNullOrEmpty() || liveUrl == "about:blank") {
                tab.pendingUrl ?: tab.url ?: DEFAULT_HOME_URL
            } else {
                liveUrl
            }
            val record = JSONObject()
            record.put("url", urlString)
            record.put("scrollX", tab.webView.scrollX)
            record.put("scrollY", max(tab.webView.scrollY, 0))
            array.put(record)
        }
        val root = JSONObject()
        root.put("tabs", array)
        root.put("activeIndex", activeIndex)

        scope.launch(Dispatchers.IO) {
            try {
                tabsFile().writeText(root.toString())
            } catch (e: Exception) {
                // Best-effort persistence; a failed write just means the next
                // relaunch falls back to a fresh tab.
            }
        }
        saveSnapshots()
    }

    /** Loads saved tab records (if any) and recreates a WebView per tab, or starts a fresh tab. */
    private fun restoreTabs() {
        var records = listOf<TabRecord>()
        var savedActive = 0
        val file = tabsFile()
        if (file.exists()) {
            try {
                val root = JSONObject(file.readText())
                val array = root.optJSONArray("tabs") ?: JSONArray()
                val list = mutableListOf<TabRecord>()
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val url = obj.optString("url")
                    if (url.isNotEmpty()) {
                        list.add(TabRecord(url, obj.optInt("scrollX", 0), obj.optInt("scrollY", 0)))
                    }
                }
                records = list
                savedActive = root.optInt("activeIndex", 0)
            } catch (e: Exception) {
                records = emptyList()
            }
        }

        if (records.isEmpty()) {
            newTab()
            return
        }

        val snapshotDir = snapshotsDir()
        records.forEachIndexed { i, record ->
            val tab = createTab()
            tab.pendingUrl = record.url
            tab.pendingScrollX = record.scrollX
            tab.pendingScrollY = record.scrollY
            val snapFile = File(snapshotDir, "$i.jpg")
            if (snapFile.exists()) {
                tab.snapshot = BitmapFactory.decodeFile(snapFile.path)
            }
            _tabs.add(tab)
            // Every restored tab starts paused; selectTab() below resumes the right one.
            tab.webView.onPause()
            tab.webView.loadUrl(record.url)
        }

        selectTab(savedActive.coerceIn(0, tabs.size - 1))
    }

    // MARK: - Lifecycle

    /** Call from the hosting Activity when the app is backgrounded (onPause/onStop), so
     *  tabs survive a process kill - mirrors iOS's didEnterBackgroundNotification observer. */
    fun onAppBackgrounded() {
        snapshotActiveTab()
        saveTabs()
    }

    /** Call from the hosting Activity's onDestroy() to release every WebView and stop pending work. */
    fun dispose() {
        saveTabsJob?.cancel()
        for (tab in tabs) {
            (tab.webView.parent as? ViewGroup)?.removeView(tab.webView)
            tab.webView.destroy()
        }
        scope.cancel()
    }
}
