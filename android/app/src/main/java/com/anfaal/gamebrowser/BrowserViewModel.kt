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

    var cursorMode by mutableStateOf(true)
    var keyboardVisible by mutableStateOf(false)
    var joystickVisible by mutableStateOf(false)
    var fullKeyboard by mutableStateOf(false)

    var webViewSize by mutableStateOf(Pair(0f, 0f))

    val pressedKeys = mutableSetOf<GbKey>()

    private var lastClickTimeMs = 0L
    private var lastClickPoint = Pair(0f, 0f)

    // MARK: - Tabs

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

    var pcMode: Boolean by PersistedBoolean(prefs, "pcMode", false)
    var controlScheme: ControlScheme by PersistedEnum(prefs, "controlScheme", ControlScheme.entries.toTypedArray(), ControlScheme.CLASSIC)
    var cursorSensitivity: Float by PersistedFloat(prefs, "cursorSensitivity", 1.4f)
    var scrollSpeed: Float by PersistedFloat(prefs, "scrollSpeed", 700f)
    var hapticsEnabled: Boolean by PersistedBoolean(prefs, "hapticsEnabled", true)
    var joystickUsesArrows: Boolean by PersistedBoolean(prefs, "joystickUsesArrows", false)
    var searchEngine: SearchEngine by PersistedEnum(prefs, "searchEngine", SearchEngine.entries.toTypedArray(), SearchEngine.GOOGLE)
    var newTabPage: NewTabPage by PersistedEnum(prefs, "newTabPage", NewTabPage.entries.toTypedArray(), NewTabPage.HOME)
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
    var highlightsEnabled: Boolean by PersistedBoolean(prefs, "highlightsEnabled", false)

    private var desktopModeBacking: Boolean by PersistedBoolean(prefs, "desktopMode", false)
    var desktopMode: Boolean
        get() = desktopModeBacking
        set(value) {
            desktopModeBacking = value
            webView?.reload()
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
        if (!autofillEnabled || password.isEmpty() || currentHost.isEmpty()) return
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
        // History isn't tracked as a separate persisted list on this port yet
        // (no bookmarks/history screens ported); nothing further to clear.
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
