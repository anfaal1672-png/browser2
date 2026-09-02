package com.anfaal.gamebrowser

import android.app.Application
import android.content.Context
import android.net.Uri
import android.webkit.WebView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** A key the virtual keyboard/joystick can send, mirroring InputBridge.Key on iOS. */
data class GbKey(val key: String, val code: String, val keyCode: Int, val label: String) {
    companion object {
        fun letter(c: String) = GbKey(c, "Key${c.uppercase()}", c.uppercase()[0].code, c.uppercase())
        fun digit(d: String) = GbKey(d, "Digit$d", d[0].code, d)

        val space = GbKey(" ", "Space", 32, "SPACE")
        val enter = GbKey("Enter", "Enter", 13, "\u23CE")
        val escape = GbKey("Escape", "Escape", 27, "ESC")
        val shift = GbKey("Shift", "ShiftLeft", 16, "\u21E7")
        val ctrl = GbKey("Control", "ControlLeft", 17, "CTRL")
        val backspace = GbKey("Backspace", "Backspace", 8, "\u232B")
        val tab = GbKey("Tab", "Tab", 9, "TAB")
        val arrowUp = GbKey("ArrowUp", "ArrowUp", 38, "\u25B2")
        val arrowDown = GbKey("ArrowDown", "ArrowDown", 40, "\u25BC")
        val arrowLeft = GbKey("ArrowLeft", "ArrowLeft", 37, "\u25C0")
        val arrowRight = GbKey("ArrowRight", "ArrowRight", 39, "\u25B6")
    }
}

/** A saved page. Mirrors BrowserViewModel.swift's `Bookmark` struct. */
data class Bookmark(val id: String = UUID.randomUUID().toString(), var title: String, var url: String)

/** One visited-page record. Mirrors BrowserViewModel.swift's `HistoryEntry` struct (`date` as epoch millis). */
data class HistoryEntry(val id: String = UUID.randomUUID().toString(), var title: String, var url: String, var date: Long)

/**
 * Central app state: tabs (via [tabManager]), virtual cursor/keyboard state,
 * settings (persisted to SharedPreferences "gamebrowser_settings" — the same
 * file Localization.kt uses), autofill, the built-in romaji IME, and the
 * JS-bridge calls that drive all of it. Mirrors BrowserViewModel.swift.
 */
class BrowserViewModel(application: Application) : AndroidViewModel(application) {
    private val appContext: Context = application.applicationContext
    private val prefs = appContext.getSharedPreferences("gamebrowser_settings", Context.MODE_PRIVATE)

    init {
        // Shared with Localization.kt's own lazily-initialized SharedPreferences
        // read — safe to call from here too (idempotent), so `loc()` works
        // correctly from the very first composition.
        Localization.init(appContext)
    }

    // MARK: - Core virtual mouse/keyboard state

    var urlText by mutableStateOf("")
    var currentUrl by mutableStateOf<String?>(null)
    var isLoading by mutableStateOf(false)
    var progress by mutableStateOf(0f)
    var canGoBack by mutableStateOf(false)
    var canGoForward by mutableStateOf(false)

    var cursorPosition by mutableStateOf(Pair(200f, 200f))
    var cursorStyle by mutableStateOf("auto")
    var mouseButtonDown by mutableStateOf(false)
    var dragLocked by mutableStateOf(false)
    var pointerLocked by mutableStateOf(false)
    var pageHidesCursor by mutableStateOf(false)

    private var cursorModeBacking by mutableStateOf(true)

    /** Whether the virtual mouse/trackpad UI is shown, vs. native touch handling. Mirrors `inputMode != .touch`. */
    var cursorMode: Boolean
        get() = cursorModeBacking
        set(value) {
            cursorModeBacking = value
            applyKeyboardSuppression()
        }
    var keyboardVisible by mutableStateOf(false)
    var joystickVisible by mutableStateOf(false)
    var fullKeyboard by mutableStateOf(false)

    var webViewSize by mutableStateOf(Pair(0f, 0f))

    val pressedKeys = mutableSetOf<GbKey>()

    private var lastClickTimeMs = 0L
    private var lastClickPoint = Pair(0f, 0f)

    // MARK: - Tabs

    /**
     * Declared before [tabManager] on purpose: [TabManager.start] (called
     * right below) can call `newTab()` synchronously on a fresh install (no
     * saved tabs.json yet), and `newTab()` reads [newTabPage] — and, when
     * that's [NewTabPage.START_PAGE], [bookmarks] via [startPageHtml] — to
     * decide what to load. Reading either property before its own initializer
     * has run (i.e. if they were declared after `tabManager` like the rest of
     * the settings block) would hit an unconstructed `PersistedEnum`/
     * `mutableStateOf` delegate and crash on the very first launch — the same
     * class of circular-initialization hazard `tabManager`'s own doc comment
     * below describes for `webView`.
     */
    var newTabPage: NewTabPage by PersistedEnum(prefs, "newTabPage", NewTabPage.entries.toTypedArray(), NewTabPage.HOME)

    private var bookmarksBacking: List<Bookmark> by mutableStateOf(loadBookmarks())
    var bookmarks: List<Bookmark>
        get() = bookmarksBacking
        set(value) {
            bookmarksBacking = value
            saveBookmarksToPrefs(value)
        }

    /**
     * Declared before [tabManager] for the same reason as [newTabPage]:
     * `TabManager.configureWebView` reads it to pick each tab's user agent,
     * and on a fresh install that runs synchronously from `TabManager.start()`
     * inside this class's own construction — reading it after `tabManager`
     * would hit an unconstructed `PersistedBoolean` delegate and crash on the
     * very first launch.
     */
    private var desktopModeBacking: Boolean by PersistedBoolean(prefs, "desktopMode", false)
    var desktopMode: Boolean
        get() = desktopModeBacking
        set(value) {
            desktopModeBacking = value
            // Reloading alone changed nothing: the user agent was pinned to
            // the desktop one for every tab regardless, and the page's own
            // viewport meta kept the layout at device width.
            tabManager.applyContentMode()
            applyViewportMode()
        }

    /**
     * Which viewport override the pages should be running, mirroring
     * BrowserViewModel.swift. `desktop` is what actually produces a PC-shaped
     * layout on a page that pins itself to the device width.
     */
    val viewportMode: String get() = if (desktopMode) "desktop" else "zoom"

    fun applyViewportMode() {
        webView?.evaluateJavascript("window.__gb && __gb.setViewportMode('$viewportMode')", null)
    }

    /**
     * Owns every tab's WebView + persistence; see TabManager.kt.
     *
     * Construction is split into two steps to avoid a circular-initialization
     * crash: [TabManager.start] (which loads/creates tabs and calls
     * `selectTab()`) reads back `viewModel.webView`, whose getter reads
     * `tabManager.activeWebView` — if that ran during this property's own
     * initializer, `this.tabManager` wouldn't be assigned yet and the read
     * would NPE. Calling `start()` as a separate statement below, after the
     * assignment completes, sidesteps that entirely.
     */
    val tabManager = TabManager(appContext, this)

    init {
        tabManager.start()
    }

    /** Delegates to the active tab's WebView (tabs own the WebView lifecycle now). */
    var webView: WebView?
        get() = tabManager.activeWebView
        set(_) { /* no-op: WebView lifecycle is owned by tabManager */ }

    // MARK: - Settings (persisted)

    private var pcModeBacking: Boolean by PersistedBoolean(prefs, "pcMode", false)

    /** App-level mode: phone = plain touch browser without the control bar, PC = gaming browser with virtual mouse tools + desktop UA. Mirrors BrowserViewModel.swift's `pcMode` didSet. */
    var pcMode: Boolean
        get() = pcModeBacking
        set(value) {
            pcModeBacking = value
            if (value) {
                cursorMode = true
            } else {
                cursorMode = false
                keyboardVisible = false
                joystickVisible = false
                imeActive = false
                if (dragLocked) toggleDragLock()
            }
            if (desktopMode != value) desktopMode = value
        }
    var controlScheme: ControlScheme by PersistedEnum(prefs, "controlScheme", ControlScheme.entries.toTypedArray(), ControlScheme.CLASSIC)
    var cursorSensitivity: Float by PersistedFloat(prefs, "cursorSensitivity", 1.4f)
    var scrollSpeed: Float by PersistedFloat(prefs, "scrollSpeed", 700f)
    var hapticsEnabled: Boolean by PersistedBoolean(prefs, "hapticsEnabled", true)
    var joystickUsesArrows: Boolean by PersistedBoolean(prefs, "joystickUsesArrows", false)
    var searchEngine: SearchEngine by PersistedEnum(prefs, "searchEngine", SearchEngine.entries.toTypedArray(), SearchEngine.GOOGLE)
    // newTabPage is declared above, before `tabManager` — see its doc comment.
    var appTheme: Int by PersistedInt(prefs, "appTheme", 0)
    var appLanguage: Int by PersistedInt(prefs, "appLanguage", 0)
    var toolbarOnBottom: Boolean by PersistedBoolean(prefs, "toolbarOnBottom", false)
    var showScrollButtons: Boolean by PersistedBoolean(prefs, "showScrollButtons", true)
    var autofillEnabled: Boolean by PersistedBoolean(prefs, "autofillEnabled", true)
    var appLockEnabled: Boolean by PersistedBoolean(prefs, "appLockEnabled", false)
    var fraudWarning: Boolean by PersistedBoolean(prefs, "fraudWarning", true)
    var httpsOnly: Boolean by PersistedBoolean(prefs, "httpsOnly", true)
    var blockPopups: Boolean by PersistedBoolean(prefs, "blockPopups", false)
    var javaScriptEnabled: Boolean by PersistedBoolean(prefs, "javaScriptEnabled", true)
    var capturePolicy: CapturePolicy by PersistedEnum(prefs, "capturePolicy", CapturePolicy.entries.toTypedArray(), CapturePolicy.ASK)
    var webNotificationsEnabled: Boolean by PersistedBoolean(prefs, "webNotificationsEnabled", true)
    private var highlightsEnabledBacking: Boolean by PersistedBoolean(prefs, "highlightsEnabled", false)
    var highlightsEnabled: Boolean
        get() = highlightsEnabledBacking
        set(value) {
            highlightsEnabledBacking = value
            if (value) HighlightRecorder.enable(appContext) else HighlightRecorder.disable(appContext)
        }

    /** Idle/Saving/Saved/Failed state driving the floating capture button (mirrors iOS BrowserViewModel.HighlightSaveState). */
    var highlightSaveState by mutableStateOf(HighlightSaveState.Idle)

    /** Exports the last ~15s of buffered play. Mirrors HighlightRecorder.swift's `saveHighlight(duration:completion:)`. */
    fun saveHighlight() {
        if (!highlightsEnabled || highlightSaveState == HighlightSaveState.Saving) return
        highlightSaveState = HighlightSaveState.Saving
        hapticMedium()
        HighlightRecorder.saveHighlight(appContext) { success ->
            highlightSaveState = if (success) HighlightSaveState.Saved else HighlightSaveState.Failed
            if (success) hapticMedium()
            viewModelScope.launch {
                delay(2000)
                highlightSaveState = HighlightSaveState.Idle
            }
        }
    }

    private var adBlockEnabledBacking: Boolean by PersistedBoolean(prefs, "adBlockEnabled", true)
    var adBlockEnabled: Boolean
        get() = adBlockEnabledBacking
        set(value) {
            adBlockEnabledBacking = value
            webView?.reload()
        }

    private var useFullAdListBacking: Boolean by PersistedBoolean(prefs, "useFullAdList", false)
    var useFullAdList: Boolean
        get() = useFullAdListBacking
        set(value) {
            useFullAdListBacking = value
            if (value) viewModelScope.launch { AdBlocker.refreshFullListIfNeeded(appContext) }
            webView?.reload()
        }

    var trackingLevel: Int by PersistedInt(prefs, "trackingLevel", TrackerBlocker.BALANCED)

    /** Runtime lock state (not itself persisted — reset to appLockEnabled's
     *  value at process start, same as ContentView's `@State isLocked` init). */
    var isLocked by mutableStateOf(false)

    private var keepAliveBacking: Boolean by PersistedBoolean(prefs, "keepAliveInBackground", false)
    var keepAliveInBackground: Boolean
        get() = keepAliveBacking
        set(value) {
            keepAliveBacking = value
            if (value) KeepAliveService.start(appContext) else KeepAliveService.stop(appContext)
        }

    fun resetJoystickPosition() {
        // Placeholder hook for a future joystick-position setting; the
        // Compose joystick (not yet ported) will read/write its own offset.
    }

    /** True while the tab on screen is a private one. */
    val isPrivateTab: Boolean
        get() = tabManager.activeTab?.isPrivate == true

    /** How many private tabs are open, for the badge in the tab switcher. */
    val privateTabCount: Int
        get() = tabManager.tabs.count { it.isPrivate }

    /** Opens a new private tab and switches to it. */
    fun newPrivateTab() {
        tabManager.newTab(isPrivate = true)
    }

    fun goHome() {
        webView?.loadUrl("https://www.google.com")
    }

    fun requestLocationPermission() {
        // Runtime permission requests need an Activity-hosted launcher;
        // MainActivity already eagerly requests ACCESS_FINE_LOCATION at
        // launch (see its permissionLauncher), so this is a no-op hook kept
        // for API parity with the settings screen / iOS surface.
    }

    fun requestNotificationPermission() {
        // See requestLocationPermission() — POST_NOTIFICATIONS (API 33+) is
        // also requested eagerly by MainActivity at launch.
    }

    // MARK: - Ad block startup

    init {
        if (useFullAdList) {
            viewModelScope.launch { AdBlocker.refreshFullListIfNeeded(appContext) }
        }
        if (keepAliveInBackground) {
            KeepAliveService.start(appContext)
        }
    }

    // MARK: - Autofill (passwords & payment card)

    private var credentialsBacking: List<Credential> by mutableStateOf(AutofillStore.loadCredentials(appContext))
    var credentials: List<Credential>
        get() = credentialsBacking
        set(value) {
            credentialsBacking = value
            AutofillStore.saveCredentials(appContext, value)
        }

    private var paymentCardBacking: PaymentCard by mutableStateOf(AutofillStore.loadCard(appContext))
    var paymentCard: PaymentCard
        get() = paymentCardBacking
        set(value) {
            paymentCardBacking = value
            AutofillStore.saveCard(appContext, value)
        }

    var pendingCredential by mutableStateOf<Credential?>(null)
    var autofillSuggestions by mutableStateOf<List<Credential>>(emptyList())
    var cardSuggestionVisible by mutableStateOf(false)

    private val currentHost: String
        get() = currentUrl?.let {
            try { Uri.parse(it).host } catch (e: Exception) { null }
        } ?: ""

    fun handleAutofillFocus(kind: String) {
        if (!autofillEnabled) return
        if (kind == "password") {
            autofillSuggestions = AutofillStore.credentials(currentHost, credentials)
            cardSuggestionVisible = false
        } else if (kind == "card") {
            cardSuggestionVisible = !paymentCard.isEmpty
            autofillSuggestions = emptyList()
        }
    }

    fun handleCredentialSubmitted(username: String, password: String) {
        // A private tab never offers to save what was typed into it.
        if (!autofillEnabled || isPrivateTab || password.isEmpty() || currentHost.isEmpty()) return
        val alreadySaved = credentials.any {
            it.domain == currentHost && it.username == username && it.password == password
        }
        if (alreadySaved) return
        pendingCredential = Credential(domain = currentHost, username = username, password = password)
    }

    fun savePendingCredential() {
        val pending = pendingCredential ?: return
        val index = credentials.indexOfFirst { it.domain == pending.domain && it.username == pending.username }
        credentials = if (index >= 0) {
            credentials.toMutableList().also { it[index] = it[index].copy(password = pending.password) }
        } else {
            credentials + pending
        }
        pendingCredential = null
    }

    fun fill(credential: Credential) {
        js("window.__gbAutofill && __gbAutofill.fillCredentials('${jsEscape(credential.username)}', '${jsEscape(credential.password)}')")
        autofillSuggestions = emptyList()
    }

    fun fillCard() {
        if (paymentCard.isEmpty) return
        js(
            "window.__gbAutofill && __gbAutofill.fillCard('${jsEscape(paymentCard.number)}', " +
                "'${jsEscape(paymentCard.holder)}', '${jsEscape(paymentCard.expMonth)}', '${jsEscape(paymentCard.expYear)}')"
        )
        cardSuggestionVisible = false
    }

    fun dismissAutofill() {
        autofillSuggestions = emptyList()
        cardSuggestionVisible = false
        pendingCredential = null
    }

    // MARK: - Granular browsing-data deletion

    fun clearData(cookies: Boolean, cache: Boolean, history: Boolean) {
        if (cookies) {
            android.webkit.CookieManager.getInstance().removeAllCookies(null)
            android.webkit.WebStorage.getInstance().deleteAllData()
        }
        if (cache) {
            for (tab in tabManager.tabs) tab.webView.clearCache(true)
        }
        if (history) clearHistory()
    }

    // MARK: - Bookmarks

    private fun loadBookmarks(): List<Bookmark> {
        val json = prefs.getString("bookmarks", null)
        if (json == null) {
            // Starter bookmarks: popular PC browser game portals (mirrors BrowserViewModel.swift's default set).
            return listOf(
                Bookmark(title = "CrazyGames", url = "https://www.crazygames.com"),
                Bookmark(title = "Poki", url = "https://poki.com"),
                Bookmark(title = "itch.io", url = "https://itch.io/games/platform-web"),
                Bookmark(title = "Miniclip", url = "https://www.miniclip.com"),
            )
        }
        return try {
            val array = JSONArray(json)
            (0 until array.length()).map { i ->
                val obj = array.getJSONObject(i)
                Bookmark(
                    id = obj.optString("id", UUID.randomUUID().toString()),
                    title = obj.optString("title", ""),
                    url = obj.optString("url", ""),
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun saveBookmarksToPrefs(list: List<Bookmark>) {
        val array = JSONArray()
        for (b in list) {
            val obj = JSONObject()
            obj.put("id", b.id)
            obj.put("title", b.title)
            obj.put("url", b.url)
            array.put(obj)
        }
        prefs.edit().putString("bookmarks", array.toString()).apply()
    }

    // bookmarksBacking/bookmarks are declared above, before `tabManager` — see the doc comment there.

    val isCurrentPageBookmarked: Boolean
        get() {
            val url = currentUrl ?: return false
            return bookmarks.any { it.url == url }
        }

    fun toggleBookmark() {
        val url = currentUrl ?: return
        val index = bookmarks.indexOfFirst { it.url == url }
        bookmarks = if (index >= 0) {
            bookmarks.toMutableList().also { it.removeAt(index) }
        } else {
            val title = tabManager.activeTab?.title?.takeIf { it.isNotBlank() } ?: url
            bookmarks + Bookmark(title = title, url = url)
        }
    }

    fun open(bookmark: Bookmark) {
        webView?.loadUrl(bookmark.url)
    }

    /** Dark start page with bookmark tiles, shown in new tabs when [newTabPage] is [NewTabPage.START_PAGE]. */
    fun startPageHtml(): String {
        val tiles = bookmarks.joinToString("") { bookmark ->
            val host = try { Uri.parse(bookmark.url).host } catch (e: Exception) { null } ?: ""
            val initial = bookmark.title.take(1).uppercase()
            """
            <a class="tile" href="${htmlEscape(bookmark.url)}">
              <div class="icon"><img src="https://www.google.com/s2/favicons?domain=${htmlEscape(host)}&sz=64" onerror="this.remove()" alt=""><span>${htmlEscape(initial)}</span></div>
              <div class="name">${htmlEscape(bookmark.title)}</div>
            </a>
            """.trimIndent()
        }
        return """
        <!DOCTYPE html><html><head>
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <style>
          * { margin:0; padding:0; box-sizing:border-box; -webkit-tap-highlight-color:transparent; }
          body { background:#0b0f14; color:#e8edf2; font-family:sans-serif;
                 min-height:100vh; display:flex; flex-direction:column; align-items:center;
                 padding:52px 20px 40px; }
          h1 { font-size:26px; font-weight:700; letter-spacing:.5px;
               background:linear-gradient(90deg,#39d3f5,#3b82f6);
               -webkit-background-clip:text; -webkit-text-fill-color:transparent; }
          p.sub { color:#7b8794; font-size:13px; margin:6px 0 34px; }
          .grid { display:grid; grid-template-columns:repeat(auto-fill,minmax(96px,1fr));
                  gap:14px; width:100%; max-width:560px; }
          .tile { display:flex; flex-direction:column; align-items:center; gap:8px;
                  padding:14px 6px; border-radius:16px; background:#141b23;
                  border:1px solid #1f2937; text-decoration:none; color:#e8edf2; }
          .tile:active { background:#1d2733; }
          .icon { width:44px; height:44px; border-radius:12px; background:#22303f;
                  display:flex; align-items:center; justify-content:center;
                  font-size:19px; font-weight:700; color:#39d3f5; position:relative; }
          .icon img { width:28px; height:28px; position:absolute; }
          .name { font-size:12px; max-width:100%; overflow:hidden;
                  text-overflow:ellipsis; white-space:nowrap; }
        </style></head><body>
        <h1>GameBrowser</h1>
        <p class="sub">${loc("ブックマークから開く、または上のバーで検索", "Open a bookmark, or search using the bar above")}</p>
        <div class="grid">$tiles</div>
        </body></html>
        """.trimIndent()
    }

    private fun htmlEscape(text: String) =
        text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    // MARK: - History

    private fun loadHistory(): List<HistoryEntry> {
        val json = prefs.getString("history", null) ?: return emptyList()
        return try {
            val array = JSONArray(json)
            (0 until array.length()).map { i ->
                val obj = array.getJSONObject(i)
                HistoryEntry(
                    id = obj.optString("id", UUID.randomUUID().toString()),
                    title = obj.optString("title", ""),
                    url = obj.optString("url", ""),
                    date = obj.optLong("date", System.currentTimeMillis()),
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun saveHistoryToPrefs(list: List<HistoryEntry>) {
        val array = JSONArray()
        for (h in list) {
            val obj = JSONObject()
            obj.put("id", h.id)
            obj.put("title", h.title)
            obj.put("url", h.url)
            obj.put("date", h.date)
            array.put(obj)
        }
        prefs.edit().putString("history", array.toString()).apply()
    }

    private var historyBacking: List<HistoryEntry> by mutableStateOf(loadHistory())
    var history: List<HistoryEntry>
        get() = historyBacking
        set(value) {
            historyBacking = value
            saveHistoryToPrefs(value)
        }

    /** Records a finished page load into [history], capped at the most recent 300 entries. Called by every tab (not just the active one), mirroring the iOS navigation delegate. */
    fun recordHistory(url: String, title: String) {
        if (url.isEmpty() || history.lastOrNull()?.url == url) return
        val updated = history + HistoryEntry(title = title, url = url, date = System.currentTimeMillis())
        history = if (updated.size > 300) updated.takeLast(300) else updated
    }

    fun clearHistory() {
        history = emptyList()
    }

    // MARK: - Page translation, find-in-page, immersive mode, smooth scroll

    /** Translates the current page via Google Translate's proxy (host.translate.goog) — no API key needed. */
    fun translatePage() {
        val urlString = currentUrl ?: return
        try {
            val uri = Uri.parse(urlString)
            val host = uri.host ?: return
            if (host.endsWith(".translate.goog")) return
            val target = Localization.translationTarget
            val translatedHost = host.replace("-", "--").replace(".", "-") + ".translate.goog"
            val builder = uri.buildUpon().scheme("https").authority(translatedHost)
            builder.appendQueryParameter("_x_tr_sl", "auto")
            builder.appendQueryParameter("_x_tr_tl", target)
            builder.appendQueryParameter("_x_tr_hl", target)
            webView?.loadUrl(builder.build().toString())
        } catch (e: Exception) {
            // Malformed URL; nothing sensible to translate.
        }
    }

    private var lastFindQuery: String? = null

    /** Finds [query] in the page via WebView's native find-in-page (highlights all matches, then jumps between them). */
    fun findInPage(query: String, forward: Boolean = true) {
        if (query.isEmpty()) return
        val view = webView ?: return
        if (query != lastFindQuery) {
            lastFindQuery = query
            view.findAllAsync(query)
        } else {
            view.findNext(forward)
        }
    }

    fun clearFindSelection() {
        lastFindQuery = null
        webView?.clearMatches()
    }

    /** Fullscreen/immersive presentation: hides the toolbar and system bars (wired in MainActivity). */
    var immersive by mutableStateOf(false)

    /**
     * True while a physical game controller is attached (maintained by
     * [GamepadInput]). Controller input never touches the screen, so without
     * this the display dims and locks in the middle of a game -- MainActivity
     * holds FLAG_KEEP_SCREEN_ON while this or [immersive] is set.
     */
    var gamepadConnected by mutableStateOf(false)

    private var scrollJob: Job? = null
    private var scrollDirection: Float = 0f

    /** Starts a smooth repeating scroll in [direction] (-1 up, 1 down) at [scrollSpeed] px/s, for the scroll-button strip. */
    fun startSmoothScroll(direction: Float) {
        scrollDirection = direction
        if (scrollJob != null) return
        scrollJob = viewModelScope.launch {
            while (true) {
                scroll(0f, scrollDirection * scrollSpeed / 60f)
                delay(16)
            }
        }
    }

    fun endSmoothScroll() {
        scrollJob?.cancel()
        scrollJob = null
    }

    // MARK: - Built-in romaji IME

    var imeActive by mutableStateOf(false)
    var imeKana by mutableStateOf("")
    var imePending by mutableStateOf("")
    var imeCandidates by mutableStateOf<List<String>>(emptyList())
    private var romajiBuffer: String = ""
    private var candidateJob: Job? = null

    val imeComposition: String get() = imeKana + imePending

    fun imeType(ch: String) {
        romajiBuffer += ch.lowercase()
        recompose()
    }

    fun imeBackspace() {
        if (romajiBuffer.isEmpty()) {
            tapKey(GbKey.backspace)
            return
        }
        romajiBuffer = romajiBuffer.dropLast(1)
        recompose()
    }

    fun imeSpace() {
        if (imeComposition.isEmpty()) {
            tapKey(GbKey.space)
        } else {
            fetchCandidates()
        }
    }

    fun imeConfirm() {
        if (imeComposition.isEmpty()) {
            tapKey(GbKey.enter)
        } else {
            setComposition(imeComposition, commit = true)
            imeClear()
        }
    }

    fun imeSelectCandidate(candidate: String) {
        setComposition(candidate, commit = true)
        imeClear()
        hapticLight()
    }

    private fun setComposition(text: String, commit: Boolean = false) {
        js("window.__gb && __gb.setComposition('${jsEscape(text)}', $commit)")
    }

    private fun imeClear() {
        romajiBuffer = ""
        imeKana = ""
        imePending = ""
        imeCandidates = emptyList()
        candidateJob?.cancel()
    }

    private fun recompose() {
        val (kana, pending) = RomajiConverter.convert(romajiBuffer)
        imeKana = kana
        imePending = pending
        setComposition(imeComposition)
        fetchCandidates(debounced = true)
    }

    private fun fetchCandidates(debounced: Boolean = false) {
        candidateJob?.cancel()
        val kana = imeKana
        if (kana.isEmpty()) {
            imeCandidates = emptyList()
            return
        }
        candidateJob = viewModelScope.launch {
            if (debounced) delay(250)
            val results = KanjiConverter.candidates(kana)
            imeCandidates = results
        }
    }

    /** Insert committed text (e.g. from the native Japanese IME) into the page's focused editable element. */
    fun insertText(text: String) {
        if (text.isEmpty()) return
        js("window.__gb && __gb.insertText('${jsEscape(text)}')")
    }

    // MARK: - Virtual mouse

    private fun js(script: String) {
        webView?.evaluateJavascript(script, null)
    }

    private fun clamp(p: Pair<Float, Float>): Pair<Float, Float> {
        val (w, h) = webViewSize
        return Pair(
            min(max(p.first, 0f), max(w - 1f, 0f)),
            min(max(p.second, 0f), max(h - 1f, 0f)),
        )
    }

    private fun f(v: Float) = "%.1f".format(v)

    fun moveCursor(dxRaw: Float, dyRaw: Float) {
        val speed = hypot(dxRaw.toDouble(), dyRaw.toDouble()).toFloat()
        val accel = min(2.2f, 0.7f + speed / 9f)
        val dx = dxRaw * cursorSensitivity * accel
        val dy = dyRaw * cursorSensitivity * accel
        cursorPosition = clamp(Pair(cursorPosition.first + dx, cursorPosition.second + dy))
        js("window.__gb && __gb.move(${f(cursorPosition.first)}, ${f(cursorPosition.second)}, ${f(dx)}, ${f(dy)})")
    }

    fun mouseDown(button: Int = 0) {
        if (button == 0) mouseButtonDown = true
        js("window.__gb && __gb.down(${f(cursorPosition.first)}, ${f(cursorPosition.second)}, $button)")
    }

    fun mouseUp(button: Int = 0) {
        if (button == 0) mouseButtonDown = false
        val now = System.currentTimeMillis()
        val isDouble = button == 0 && (now - lastClickTimeMs) < 350 &&
            hypot(
                (cursorPosition.first - lastClickPoint.first).toDouble(),
                (cursorPosition.second - lastClickPoint.second).toDouble(),
            ) < 12
        if (button == 0) {
            lastClickTimeMs = now
            lastClickPoint = cursorPosition
        }
        js("window.__gb && __gb.up(${f(cursorPosition.first)}, ${f(cursorPosition.second)}, $button, ${if (isDouble) 2 else 1})")
    }

    fun click(button: Int = 0) {
        mouseDown(button)
        mouseUp(button)
    }

    fun toggleDragLock() {
        dragLocked = !dragLocked
        if (dragLocked) mouseDown() else mouseUp()
    }

    fun scroll(dx: Float, dy: Float) {
        js("window.__gb && __gb.wheel(${f(cursorPosition.first)}, ${f(cursorPosition.second)}, ${f(dx)}, ${f(dy)})")
    }

    fun keyDown(key: GbKey) {
        pressedKeys.add(key)
        sendKey("keydown", key)
    }

    fun keyUp(key: GbKey) {
        pressedKeys.remove(key)
        sendKey("keyup", key)
    }

    fun tapKey(key: GbKey) {
        keyDown(key)
        keyUp(key)
    }

    fun repeatKey(key: GbKey) {
        sendKey("keydown", key, repeating = true)
    }

    fun releaseAllKeys() {
        for (key in pressedKeys.toList()) sendKey("keyup", key)
        pressedKeys.clear()
    }

    private fun jsEscape(text: String) =
        text.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n")

    private fun sendKey(type: String, key: GbKey, repeating: Boolean = false) {
        val shift = pressedKeys.contains(GbKey.shift)
        val ctrl = pressedKeys.contains(GbKey.ctrl)
        var keyValue = key.key
        if (shift && keyValue.length == 1 && keyValue[0].isLetter()) {
            keyValue = keyValue.uppercase()
        }
        js(
            "window.__gb && __gb.key('$type', '${jsEscape(keyValue)}', '${key.code}', ${key.keyCode}, " +
                "{shift:$shift, ctrl:$ctrl, alt:false, repeat:$repeating})"
        )
    }

    fun applyKeyboardSuppression() {
        js("window.__gb && __gb.setSuppressKeyboard($cursorMode)")
    }

    fun submitUrl() {
        val text = urlText.trim()
        if (text.isEmpty()) return
        val url = when {
            text.contains("://") -> text
            text.contains(".") && !text.contains(" ") -> "https://$text"
            else -> "https://www.google.com/search?q=" + java.net.URLEncoder.encode(text, "UTF-8")
        }
        webView?.loadUrl(url)
    }

    fun goBack() = webView?.goBack()
    fun goForward() = webView?.goForward()
    fun reload() = webView?.reload()

    fun handleBridgeMessage(type: String, style: String?) {
        when (type) {
            "cursorstyle" -> {
                val s = style ?: "auto"
                if (cursorStyle != s) cursorStyle = s
                val hidden = s == "none"
                if (pageHidesCursor != hidden) pageHidesCursor = hidden
            }
            "pointerlock" -> {
                // handled by caller passing the locked flag separately
            }
        }
    }

    // Centralized haptics so the toggle applies everywhere (mirrors BrowserViewModel.swift).
    private fun vibrate(durationMs: Long) {
        if (!hapticsEnabled) return
        val vibrator = appContext.getSystemService(android.os.Vibrator::class.java) ?: return
        vibrator.vibrate(android.os.VibrationEffect.createOneShot(durationMs, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
    }

    fun hapticLight() = vibrate(10)
    fun hapticMedium() = vibrate(20)
    fun hapticSelection() = vibrate(8)

    override fun onCleared() {
        tabManager.dispose()
        super.onCleared()
    }
}
