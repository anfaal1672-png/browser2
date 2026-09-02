package com.anfaal.gamebrowser

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.view.ViewGroup
import android.webkit.CookieManager
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
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
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

/** Persisted stand-in URL for a tab showing the local start page (which WebView reports as a blank/no URL). Mirrors BrowserViewModel.swift's `startPageMarker`. */
private const val START_PAGE_MARKER = "gamebrowser://start"

/** Name of the WebView profile private tabs run in; deleted with the last one. */
private const val PRIVATE_PROFILE = "gamebrowser-private"

/** How long to wait after a page finishes loading before restoring its saved scroll offset. */
private const val SCROLL_RESTORE_DELAY_MS = 600L

/** Debounce window for persisting tabs after a URL change, so rapid navigations don't thrash disk. */
private const val SAVE_DEBOUNCE_MS = 1000L

private var cachedBridgeScript: String? = null
private var cachedAutofillScript: String? = null
private var cachedNotificationScript: String? = null

private fun bridgeScript(context: Context): String {
    cachedBridgeScript?.let { return it }
    val text = context.assets.open("input_bridge.js").bufferedReader().use { it.readText() }
    cachedBridgeScript = text
    return text
}

private fun autofillBridgeScript(context: Context): String {
    cachedAutofillScript?.let { return it }
    val text = context.assets.open("autofill_bridge.js").bufferedReader().use { it.readText() }
    cachedAutofillScript = text
    return text
}

private fun notificationBridgeScript(context: Context): String {
    cachedNotificationScript?.let { return it }
    val text = context.assets.open("notification_bridge.js").bufferedReader().use { it.readText() }
    cachedNotificationScript = text
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
 *
 * [isPrivate] tabs leave nothing behind locally: no history, no saved
 * passwords, no session restore, no thumbnail on disk, and - where the
 * installed WebView supports profiles - their own cookie jar and storage,
 * thrown away with the last private tab.
 */
class Tab(val webView: WebView, val isPrivate: Boolean = false) {
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

    /** True while this tab is showing the local bookmarks start page (loaded via loadDataWithBaseURL, so WebView itself reports no real URL for it). */
    var isStartPage: Boolean = false

    /**
     * Last requested URL; used for persistence while the page is still
     * loading (webView.url can lag or briefly report null/about:blank).
     */
    var pendingUrl: String? = null

    /**
     * Live and page-initial zoom, reported by WebViewClient.onScaleChanged.
     * WebView has no "reset zoom" call and its default scale is
     * density-dependent, so the only reliable way back is to remember what the
     * page opened at and divide by whatever it is now.
     */
    var pageScale: Float = 0f
    var initialScale: Float = 0f

    /** Scroll offset to restore once this tab's page finishes loading; cleared once consumed. */
    var pendingScrollX: Int? = null
    var pendingScrollY: Int? = null

    /**
     * Set when the desktop/mobile presentation changed while this tab was in
     * the background: it reloads the next time it is shown, rather than every
     * tab reloading at once behind the user's back.
     */
    var needsContentModeReload: Boolean = false

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

    /** True once a private tab has taken the private profile, so it is only deleted if it exists. */
    private var privateProfileInUse = false
        private set

    val activeTab: Tab? get() = tabs.getOrNull(activeIndex)

    /** What the UI/ViewModel should treat as "the" WebView right now. */
    val activeWebView: WebView? get() = activeTab?.webView

    /**
     * Loads saved tabs (or starts a fresh one). Deliberately NOT called from
     * an `init {}` block: `restoreTabs()` -> `selectTab()` calls back into
     * `viewModel.applyKeyboardSuppression()`, which reads `viewModel.webView`
     * — whose getter reads `this` TabManager back off `viewModel.tabManager`.
     * If that ran while `BrowserViewModel` was still in the middle of
     * assigning its own `tabManager` property, the read would see `null` and
     * crash. The caller (BrowserViewModel) calls this as a separate statement
     * once its `tabManager` property has actually been assigned.
     */
    fun start() {
        restoreTabs()
    }

    // MARK: - Tab creation

    private fun createTab(isPrivate: Boolean = false): Tab {
        val webView = WebView(context)
        // The profile has to be set before the WebView loads anything, so this
        // comes ahead of configureWebView and any loadUrl.
        if (isPrivate) attachPrivateProfile(webView)
        val tab = Tab(webView, isPrivate = isPrivate)
        configureWebView(webView, tab)
        return tab
    }

    /**
     * Cookies, storage and cache for private tabs, kept out of the normal
     * profile. WebView's multi-profile support is the Android counterpart of
     * iOS's `WKWebsiteDataStore.nonPersistent()`: a named profile with its own
     * cookie jar and storage, deleted outright once the last private tab
     * closes.
     *
     * Older WebView builds have no profiles. There, private tabs still keep
     * nothing locally - no history, no saved passwords, no session restore, no
     * thumbnail on disk, and nothing cached - but they do share the normal
     * cookie jar, which is a platform limit rather than a choice.
     */
    private fun attachPrivateProfile(webView: WebView) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) return
        try {
            ProfileStore.getInstance().getOrCreateProfile(PRIVATE_PROFILE)
            WebViewCompat.setProfile(webView, PRIVATE_PROFILE)
            privateProfileInUse = true
        } catch (e: Exception) {
            // A WebView that will not take a profile just runs in the default
            // one; the local-record guarantees above still hold.
        }
    }

    /** Throws the private profile away once no private tab is left. */
    private fun endPrivateSessionIfEmpty() {
        if (tabs.any { it.isPrivate }) return
        if (!privateProfileInUse) return
        privateProfileInUse = false
        try {
            ProfileStore.getInstance().deleteProfile(PRIVATE_PROFILE)
        } catch (e: Exception) {
            // Deleting fails while any WebView still holds the profile; the
            // next close tries again, and the data never reaches the normal
            // profile either way.
        }
    }

    /**
     * Switch every tab between the desktop and mobile presentation: the user
     * agent goes out with the next request, and the page picks up the
     * matching viewport override when it finishes loading.
     */
    fun applyContentMode() {
        for (tab in tabs) {
            tab.webView.settings.userAgentString =
                if (viewModel.desktopMode) DESKTOP_USER_AGENT else null
        }
        for ((index, tab) in tabs.withIndex()) {
            if (index == activeIndex) {
                tab.webView.reload()
            } else {
                tab.needsContentModeReload = true
            }
        }
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
        // Follow the mode instead of pinning every tab to the desktop agent:
        // phone mode was asking sites for their desktop build and then laying
        // it out at device width, and toggling PC mode changed nothing at all.
        settings.userAgentString = if (viewModel.desktopMode) DESKTOP_USER_AGENT else null
        // A desktop-width layout on a phone is unreadable without zooming, so
        // the page has to be zoomable (controls hidden -- pinch only).
        settings.setSupportZoom(true)
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        if (tab.isPrivate) {
            // Nothing from a private tab should survive it on disk. The profile
            // above covers cookies and storage where it is available; this
            // covers the HTTP cache either way.
            settings.cacheMode = WebSettings.LOAD_NO_CACHE
            CookieManager.getInstance().setAcceptThirdPartyCookies(webView, false)
        }
        webView.setBackgroundColor(android.graphics.Color.BLACK)

        webView.addJavascriptInterface(JsBridge(context, viewModel), "AndroidBridge")

        // Anything the engine will not display - a game mod, a save file, an
        // archive - arrives here instead of navigating nowhere.
        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, contentLength ->
            viewModel.startDownload(
                url = url,
                userAgent = userAgent,
                contentDisposition = contentDisposition,
                mimeType = mimeType,
                contentLength = contentLength,
            )
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                // The focused element belonged to the page being replaced.
                if (tab === activeTab) viewModel.clearGameFocus()
                // A new page opens at its own scale; the old one's is no
                // longer what "reset zoom" should go back to.
                tab.initialScale = 0f
                // Re-inject at document start on every navigation (Android has no
                // per-navigation WKUserScript equivalent).
                view.evaluateJavascript(bridgeScript(context), null)
                view.evaluateJavascript(autofillBridgeScript(context), null)
                view.evaluateJavascript(notificationBridgeScript(context), null)
                if (viewModel.adBlockEnabled) {
                    view.evaluateJavascript(AdBlocker.cosmeticHidingScript, null)
                }
            }

            /**
             * A failed main-frame load, shown as a real page instead of the
             * blank screen it used to leave behind. Sub-resource failures (an
             * ad, an image) are none of the user's business and must not
             * replace a page that rendered fine.
             */
            override fun onReceivedError(
                view: WebView,
                request: android.webkit.WebResourceRequest,
                error: android.webkit.WebResourceError,
            ) {
                if (!request.isForMainFrame) return
                val failing = request.url?.toString() ?: return
                // A load the user replaced (a new link tapped mid-load) is not
                // a failure worth a page of its own.
                if (error.errorCode == android.webkit.WebViewClient.ERROR_UNKNOWN &&
                    error.description.isNullOrEmpty()
                ) {
                    return
                }
                val offline = when (error.errorCode) {
                    android.webkit.WebViewClient.ERROR_HOST_LOOKUP,
                    android.webkit.WebViewClient.ERROR_CONNECT,
                    android.webkit.WebViewClient.ERROR_TIMEOUT,
                    android.webkit.WebViewClient.ERROR_IO,
                    -> true
                    else -> false
                }
                viewModel.showErrorPage(
                    view = view,
                    failingUrl = failing,
                    offline = offline,
                    description = error.description?.toString().orEmpty(),
                )
            }

            override fun onScaleChanged(view: WebView, oldScale: Float, newScale: Float) {
                tab.pageScale = newScale
                if (tab.initialScale <= 0f) tab.initialScale = newScale
            }

            override fun shouldInterceptRequest(
                view: WebView,
                request: android.webkit.WebResourceRequest,
            ): android.webkit.WebResourceResponse? {
                val requestHost = request.url.host
                val pageHost = view.url?.let { android.net.Uri.parse(it).host }
                val isThirdParty = AdBlocker.isThirdPartyHost(requestHost, pageHost)
                return if (AdBlocker.shouldBlock(
                        host = requestHost,
                        isThirdParty = isThirdParty,
                        adBlockEnabled = viewModel.adBlockEnabled,
                        useFullAdList = viewModel.useFullAdList,
                        trackingLevel = viewModel.trackingLevel,
                    )
                ) {
                    AdBlocker.blockedResponse()
                } else {
                    null
                }
            }

            override fun onPageFinished(view: WebView, url: String?) {
                // loadDataWithBaseURL(null, ...) (the local start page) reports back a
                // blank/no URL here, same as iOS's `url?.scheme == "about"` check —
                // recomputed on every finish so navigating away from the start page
                // (e.g. tapping a bookmark tile) correctly clears the flag again.
                tab.isStartPage = url.isNullOrEmpty() || url == "about:blank"
                tab.url = url
                tab.title = view.title ?: ""
                tab.canGoBack = view.canGoBack()
                tab.canGoForward = view.canGoForward()
                tab.pendingUrl = null

                if (!tab.isStartPage && url != null && !tab.isPrivate) {
                    viewModel.recordHistory(url, tab.title)
                }

                // Only the currently active tab drives the visible toolbar state —
                // a background tab finishing a load must not steal the address bar.
                // Every tab needs the viewport override, active or not: it is
                // what actually produces a PC-shaped layout on a page that
                // pins itself to the device width.
                view.evaluateJavascript(
                    "window.__gb && __gb.setViewportMode('${viewModel.viewportMode}')", null)

                if (tab === activeTab) viewModel.applyFpsMeter()

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

    fun newTab(url: String? = null, isPrivate: Boolean = false) {
        val tab = createTab(isPrivate = isPrivate)
        // A private tab skips the bookmark start page: those tiles are built
        // from saved bookmarks and recently visited sites, which is the one
        // thing a private tab should not open with.
        val useStartPage = url == null && viewModel.newTabPage == NewTabPage.START_PAGE && !isPrivate
        if (useStartPage) {
            tab.isStartPage = true
            _tabs.add(tab)
            selectTab(_tabs.size - 1)
            tab.webView.loadDataWithBaseURL(null, viewModel.startPageHtml(), "text/html", "UTF-8", null)
        } else {
            val target = url ?: DEFAULT_HOME_URL
            tab.pendingUrl = target
            _tabs.add(tab)
            selectTab(_tabs.size - 1)
            tab.webView.loadUrl(target)
        }
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
        if (tab.needsContentModeReload) {
            tab.needsContentModeReload = false
            tab.webView.reload()
        }
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
        endPrivateSessionIfEmpty()
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
        val images = persistedTabs().map { it.snapshot }
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

    /**
     * The tabs that go to disk. Private tabs are deliberately absent: a
     * relaunch must not bring one back, so neither tabs.json nor the thumbnail
     * files know about them. Both writers index the same filtered list, which
     * is what keeps `<i>.jpg` lined up with entry `i` on restore.
     */
    private fun persistedTabs(): List<Tab> = tabs.filter { !it.isPrivate }

    /**
     * [activeIndex] renumbered against [persistedTabs]. A private tab has no
     * saved slot of its own, so the last ordinary tab before it is restored
     * instead of some unrelated page.
     */
    private fun persistedActiveIndex(): Int {
        if (activeIndex < 0) return 0
        var mapped = -1
        for ((index, tab) in tabs.withIndex()) {
            if (!tab.isPrivate) mapped += 1
            if (index == activeIndex) break
        }
        return max(mapped, 0)
    }

    /** Persists every tab's URL + scroll offset + the active index, then re-saves thumbnails. */
    fun saveTabs() {
        val array = JSONArray()
        for (tab in persistedTabs()) {
            val liveUrl = tab.webView.url
            val urlString = if (tab.isStartPage) {
                START_PAGE_MARKER
            } else if (liveUrl.isNullOrEmpty() || liveUrl == "about:blank") {
                // A load that hasn't reported its own url yet (or reports
                // about:blank) falls back to the last URL we asked it to load.
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
        root.put("activeIndex", persistedActiveIndex())

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
            val isStartPage = record.url == START_PAGE_MARKER
            if (isStartPage) {
                tab.isStartPage = true
            } else {
                tab.pendingUrl = record.url
                tab.pendingScrollX = record.scrollX
                tab.pendingScrollY = record.scrollY
            }
            val snapFile = File(snapshotDir, "$i.jpg")
            if (snapFile.exists()) {
                tab.snapshot = BitmapFactory.decodeFile(snapFile.path)
            }
            _tabs.add(tab)
            // Every restored tab starts paused; selectTab() below resumes the right one.
            tab.webView.onPause()
            if (isStartPage) {
                tab.webView.loadDataWithBaseURL(null, viewModel.startPageHtml(), "text/html", "UTF-8", null)
            } else {
                tab.webView.loadUrl(record.url)
            }
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
