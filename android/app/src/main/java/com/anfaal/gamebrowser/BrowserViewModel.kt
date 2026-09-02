package com.anfaal.gamebrowser

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
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

/** Which glyph a toast shows, mirroring the SF Symbol names iOS passes to `toast(_:icon:)`. */
enum class ToastIcon { SUCCESS, DOWNLOAD, COPY, TAB, WARNING }

/** How long a burst of pad-layout edits is coalesced before it reaches disk. */
private const val PROFILE_SAVE_DEBOUNCE_MS = 400L

/** Turbo re-press interval, matching the iOS timer. */
private const val TURBO_INTERVAL_MS = 90L

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

    // MARK: - Error page

    /**
     * A failed load used to leave the tab on a blank black screen with no
     * explanation and nothing to tap - indistinguishable from the app hanging.
     * Show what went wrong, on the URL that failed, with a retry.
     *
     * Loading it with the failing URL as the base URL is the Android
     * counterpart of iOS's `loadSimulatedRequest`: the URL bar still shows
     * where the user was going and the retry link is a plain navigation to it.
     */
    fun showErrorPage(view: WebView, failingUrl: String, offline: Boolean, description: String) {
        if (!failingUrl.startsWith("http")) return
        view.loadDataWithBaseURL(
            failingUrl,
            errorPageHtml(failingUrl, offline, description),
            "text/html",
            "UTF-8",
            failingUrl,
        )
    }

    /** Dark error page styled like the start page - and like the iOS one. */
    private fun errorPageHtml(url: String, offline: Boolean, description: String): String {
        val title = if (offline) {
            loc("インターネットに接続されていません", "You're offline")
        } else {
            loc("ページを開けませんでした", "This page didn't load")
        }
        val message = if (offline) {
            loc(
                "Wi-Fi またはモバイル通信を確認してから、もう一度お試しください。",
                "Check your Wi-Fi or mobile connection, then try again.",
            )
        } else {
            htmlEscape(description)
        }
        val href = htmlEscape(url)
        val icon = if (offline) "\uD83D\uDCE1" else "\u26A0\uFE0F"
        return """
            <!DOCTYPE html><html><head>
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <style>
              * { margin:0; padding:0; box-sizing:border-box; -webkit-tap-highlight-color:transparent; }
              body { background:#0b0f14; color:#e8edf2; font-family:sans-serif;
                     min-height:100vh; display:flex; flex-direction:column; align-items:center;
                     justify-content:center; text-align:center; padding:32px 24px; }
              .icon { font-size:42px; margin-bottom:16px; }
              h1 { font-size:20px; font-weight:700; }
              .msg { color:#9aa7b4; font-size:14px; line-height:1.55; margin-top:10px; max-width:420px; }
              .host { color:#5c6773; font-size:12px; margin-top:16px; max-width:420px;
                      word-break:break-all; }
              .retry { margin-top:26px; display:inline-block; text-decoration:none;
                       color:#0b0f14; background:#39d3f5; font-size:14px; font-weight:600;
                       padding:11px 26px; border-radius:11px; }
              .retry:active { background:#2bb9d8; }
            </style></head><body>
            <div class="icon">$icon</div>
            <h1>$title</h1>
            <p class="msg">$message</p>
            <p class="host">$href</p>
            <a class="retry" href="$href">${loc("再試行", "Try again")}</a>
            </body></html>
        """.trimIndent()
    }

    // MARK: - Custom control pads

    /**
     * Saved layouts. Every game binds different keys, and the fixed on-screen
     * keyboard can't reach most of them - so the user builds their own pad.
     *
     * Dragging a button rewrites this on every touch sample, and serialising
     * every profile at 120Hz is not free, so writes are coalesced.
     */
    var profiles by mutableStateOf(ControlProfileStore.loadProfiles(prefs))
        private set

    private var saveProfilesJob: Job? = null

    private fun applyProfiles(next: List<ControlProfile>) {
        profiles = next
        saveProfilesJob?.cancel()
        saveProfilesJob = viewModelScope.launch {
            delay(PROFILE_SAVE_DEBOUNCE_MS)
            ControlProfileStore.saveProfiles(prefs, next)
        }
    }

    /** Write immediately - the app may not get another chance. */
    fun saveProfilesNow() {
        saveProfilesJob?.cancel()
        ControlProfileStore.saveProfiles(prefs, profiles)
    }

    var activeProfileId by mutableStateOf(ControlProfileStore.loadActive(prefs))
        private set

    /** Draw the active profile's buttons over the page. */
    private var padVisibleBacking: Boolean by PersistedBoolean(prefs, "padVisible", false)
    var padVisible: Boolean
        get() = padVisibleBacking
        set(value) {
            padVisibleBacking = value
            if (!value) {
                releasePadButtons()
                padEditing = false
            }
        }

    /** Arrange mode: buttons are draggable and tapping one opens its settings. */
    var padEditing by mutableStateOf(false)
        private set

    fun setPadEditMode(editing: Boolean) {
        padEditing = editing
        if (editing) releasePadButtons()
    }

    /** Buttons currently latched down (sticky). */
    var padLatched by mutableStateOf<Set<String>>(emptySet())
        private set

    var selectedPadButton by mutableStateOf<String?>(null)
    var showPadInspector by mutableStateOf(false)
    var showProfiles by mutableStateOf(false)

    /** host -> profile id, so a game's controls come back on their own. */
    private var siteProfiles: Map<String, String> = ControlProfileStore.loadAssignments(prefs)

    private val turboJobs = mutableMapOf<String, Job>()

    val activeProfile: ControlProfile?
        get() = profiles.firstOrNull { it.id == activeProfileId }

    private fun updateProfile(mutate: (ControlProfile) -> ControlProfile) {
        val id = activeProfileId ?: return
        applyProfiles(profiles.map { if (it.id == id) mutate(it) else it })
    }

    fun activateProfile(id: String?) {
        releasePadButtons()
        activeProfileId = id
        ControlProfileStore.saveActive(prefs, id)
        selectedPadButton = null
        activeProfile?.let { profile ->
            joystickUsesArrows = profile.joystickArrows
            if (profile.showJoystick) joystickVisible = true
            padVisible = true
        }
        hapticSelection()
    }

    fun createProfile() {
        val profile = ControlProfile(name = loc("新しいプロファイル", "New profile"))
        applyProfiles(profiles + profile)
        activateProfile(profile.id)
    }

    fun duplicateProfile(id: String) {
        val source = profiles.firstOrNull { it.id == id } ?: return
        val copy = source.copy(
            id = UUID.randomUUID().toString(),
            name = source.name + loc(" のコピー", " copy"),
            // Fresh button ids, or the two profiles would share latch/turbo state.
            buttons = source.buttons.map { it.copy(id = UUID.randomUUID().toString()) },
        )
        applyProfiles(profiles + copy)
        activateProfile(copy.id)
    }

    fun deleteProfile(id: String) {
        applyProfiles(profiles.filterNot { it.id == id })
        siteProfiles = siteProfiles.filterValues { it != id }
        ControlProfileStore.saveAssignments(prefs, siteProfiles)
        if (activeProfileId == id) activateProfile(profiles.firstOrNull()?.id)
    }

    fun renameProfile(id: String, name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        applyProfiles(profiles.map { if (it.id == id) it.copy(name = trimmed) else it })
    }

    fun addPresetProfiles() {
        applyProfiles(profiles + ControlProfile.presets())
        hapticMedium()
    }

    /** Per-profile tuning, from the profiles screen. */
    fun updateActiveProfile(mutate: (ControlProfile) -> ControlProfile) = updateProfile(mutate)

    // MARK: Buttons

    fun addPadButton() {
        if (activeProfileId == null) return
        val new = PadButton(keys = listOf("Space"), x = 0.5f, y = 0.5f)
        updateProfile { it.copy(buttons = it.buttons + new) }
        selectedPadButton = new.id
        showPadInspector = true
        hapticLight()
    }

    fun movePadButton(id: String, x: Float, y: Float) {
        updatePadButton(id) {
            it.copy(x = x.coerceIn(0.04f, 0.96f), y = y.coerceIn(0.04f, 0.96f))
        }
    }

    fun updatePadButton(id: String, mutate: (PadButton) -> PadButton) {
        updateProfile { profile ->
            profile.copy(buttons = profile.buttons.map { if (it.id == id) mutate(it) else it })
        }
    }

    fun deletePadButton(id: String) {
        releasePadButton(id)
        updateProfile { profile -> profile.copy(buttons = profile.buttons.filterNot { it.id == id }) }
        if (selectedPadButton == id) selectedPadButton = null
    }

    /** Several keys on one button are held together - Shift+W is sprint. */
    fun addBinding(name: String, to: String) {
        updatePadButton(to) { button ->
            if (name in PadKeyName.mouseNames) {
                button.copy(
                    mouseButton = if (name == PadKeyName.RIGHT_CLICK) 2 else 0,
                    keys = emptyList(),
                )
            } else {
                button.copy(
                    mouseButton = null,
                    keys = if (name in button.keys) button.keys else button.keys + name,
                )
            }
        }
        hapticLight()
    }

    fun removeBinding(name: String, from: String) {
        updatePadButton(from) { button ->
            if (name in PadKeyName.mouseNames) {
                button.copy(mouseButton = null)
            } else {
                button.copy(keys = button.keys.filterNot { it == name })
            }
        }
    }

    // MARK: Per-site assignment

    fun siteProfileId(host: String): String? = siteProfiles[host]

    fun siteProfileName(host: String): String? {
        val id = siteProfileId(host) ?: return null
        return profiles.firstOrNull { it.id == id }?.name
    }

    fun assignCurrentProfileToSite(pinned: Boolean) {
        val host = try {
            currentUrl?.let { Uri.parse(it).host }
        } catch (e: Exception) {
            null
        } ?: return
        val id = activeProfileId
        siteProfiles = if (pinned && id != null) {
            siteProfiles + (host to id)
        } else {
            siteProfiles - host
        }
        ControlProfileStore.saveAssignments(prefs, siteProfiles)
        hapticLight()
    }

    /** Bring back the profile pinned to this site, if it isn't already on. */
    fun applySiteProfile(url: String?) {
        val host = try {
            url?.let { Uri.parse(it).host }
        } catch (e: Exception) {
            null
        } ?: return
        val id = siteProfileId(host) ?: return
        if (id == activeProfileId) return
        if (profiles.none { it.id == id }) return
        activateProfile(id)
    }

    // MARK: Physical controller mapping

    fun gamepadBinding(slot: GamepadSlot): String =
        activeProfile?.gamepadKey(slot) ?: slot.defaultKey

    fun setGamepadBinding(slot: GamepadSlot, name: String) {
        updateProfile { it.copy(gamepadMap = it.gamepadMap + (slot.name to name)) }
        hapticLight()
    }

    fun resetGamepadMapping() {
        updateProfile { it.copy(gamepadMap = emptyMap()) }
        hapticLight()
    }

    /** The profile's own cursor speed, or the global one when it has none. */
    val effectiveSensitivity: Float
        get() = activeProfile?.cursorSensitivity ?: cursorSensitivity

    // MARK: Per-profile tuning

    fun setProfileSensitivity(value: Float?) {
        updateProfile { it.copy(cursorSensitivity = value) }
    }

    fun setPadOpacity(value: Float) {
        updateProfile { it.copy(padOpacity = value) }
    }

    fun setAutoFocusGame(on: Boolean) {
        updateProfile { it.copy(autoFocusGame = on) }
        hapticLight()
    }

    /**
     * Back to the values a new profile starts with - the buttons themselves and
     * the controller mapping are left alone.
     */
    fun resetProfileTuning() {
        val fresh = ControlProfile(name = "")
        updateProfile {
            it.copy(
                padOpacity = fresh.padOpacity,
                cursorSensitivity = fresh.cursorSensitivity,
                autoFocusGame = fresh.autoFocusGame,
            )
        }
        hapticMedium()
    }

    /** A layout as JSON, so it can be shared or kept somewhere that survives a reinstall. */
    fun copyActiveProfile(): Boolean {
        val profile = activeProfile ?: return false
        val clipboard = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return false
        clipboard.setPrimaryClip(ClipData.newPlainText("GameBrowser profile", profile.toJson().toString()))
        hapticMedium()
        return true
    }

    fun pasteProfile(): Boolean {
        val clipboard = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return false
        val text = clipboard.primaryClip?.getItemAt(0)?.coerceToText(appContext)?.toString()
            ?: return false
        val parsed = try {
            ControlProfile.fromJson(org.json.JSONObject(text))
        } catch (e: Exception) {
            return false
        }
        if (parsed.buttons.isEmpty() && parsed.gamepadMap.isEmpty()) return false
        // Fresh ids so pasting the same layout twice gives two profiles rather
        // than two things claiming to be the same one.
        val profile = parsed.copy(
            id = UUID.randomUUID().toString(),
            buttons = parsed.buttons.map { it.copy(id = UUID.randomUUID().toString()) },
        )
        applyProfiles(profiles + profile)
        activateProfile(profile.id)
        hapticMedium()
        return true
    }

    // MARK: Sending

    fun padPress(button: PadButton) {
        hapticLight()
        if (button.sticky) {
            if (button.id in padLatched) {
                releasePadButton(button.id)
            } else {
                padLatched = padLatched + button.id
                engage(button)
                if (button.turbo) startTurbo(button)
            }
            return
        }
        engage(button)
        if (button.turbo) startTurbo(button)
    }

    fun padRelease(button: PadButton) {
        if (button.sticky) return   // a latched button waits for the next tap
        stopTurbo(button.id)
        disengage(button)
    }

    private fun engage(button: PadButton) {
        val mouse = button.mouseButton
        if (mouse != null) {
            mouseDown(mouse)
            return
        }
        for (name in button.keys) KeyCatalog.key(name)?.let { keyDown(it) }
    }

    private fun disengage(button: PadButton) {
        val mouse = button.mouseButton
        if (mouse != null) {
            mouseUp(mouse)
            return
        }
        // Reverse order so a modifier in a combo is released last.
        for (name in button.keys.reversed()) KeyCatalog.key(name)?.let { keyUp(it) }
    }

    /** Turbo: re-press on an interval for games that expect mashing. */
    private fun startTurbo(button: PadButton) {
        stopTurbo(button.id)
        turboJobs[button.id] = viewModelScope.launch {
            while (true) {
                delay(TURBO_INTERVAL_MS)
                disengage(button)
                engage(button)
            }
        }
    }

    private fun stopTurbo(id: String) {
        turboJobs.remove(id)?.cancel()
    }

    private fun releasePadButton(id: String) {
        stopTurbo(id)
        padLatched = padLatched - id
        activeProfile?.buttons?.firstOrNull { it.id == id }?.let { disengage(it) }
    }

    /**
     * Drop everything the pads are holding - hiding them, editing, switching
     * tabs or profiles must never leave a key or mouse button stuck down.
     */
    fun releasePadButtons() {
        turboJobs.values.forEach { it.cancel() }
        turboJobs.clear()
        val latched = padLatched
        padLatched = emptySet()
        val profile = activeProfile ?: return
        profile.buttons.filter { it.id in latched }.forEach { disengage(it) }
    }

    // MARK: - Resetting settings

    /** One settings card's worth of options. Mirrors iOS's SettingsSection. */
    enum class SettingsSection {
        BROWSER_MODE, CONTROLS, SEARCH_TABS, APPEARANCE, AUTOFILL,
        SECURITY, PERMISSIONS, HIGHLIGHTS, BACKGROUND,
    }

    /**
     * Put one section back to its factory values, with feedback.
     *
     * Browsing data is deliberately left alone - bookmarks, history, saved
     * passwords and cards, open tabs and downloads all survive. This resets
     * settings, not the user's own stuff.
     */
    fun resetSettings(section: SettingsSection) {
        applyDefaults(section)
        hapticMedium()
    }

    fun resetAllSettings() {
        for (section in SettingsSection.entries) applyDefaults(section)
        hapticMedium()
    }

    private fun applyDefaults(section: SettingsSection) {
        when (section) {
            SettingsSection.BROWSER_MODE -> {
                pcMode = Default.pcMode
                desktopMode = Default.desktopMode
            }
            SettingsSection.CONTROLS -> {
                controlScheme = Default.controlScheme
                cursorSensitivity = Default.cursorSensitivity
                scrollSpeed = Default.scrollSpeed
                hapticsEnabled = Default.hapticsEnabled
                showFps = Default.showFps
                joystickUsesArrows = Default.joystickUsesArrows
                resetJoystickPosition()
            }
            SettingsSection.SEARCH_TABS -> {
                searchEngine = Default.searchEngine
                newTabPage = Default.newTabPage
            }
            SettingsSection.APPEARANCE -> {
                appTheme = Default.appTheme
                appLanguage = Default.appLanguage
                toolbarOnBottom = Default.toolbarOnBottom
                showScrollButtons = Default.showScrollButtons
                desktopMode = pcMode   // its factory value is "match the mode"
            }
            SettingsSection.AUTOFILL -> {
                autofillEnabled = Default.autofillEnabled
            }
            SettingsSection.SECURITY -> {
                adBlockEnabled = Default.adBlockEnabled
                useFullAdList = Default.useFullAdList
                trackingLevel = Default.trackingLevel
                appLockEnabled = Default.appLockEnabled
                fraudWarning = Default.fraudWarning
                httpsOnly = Default.httpsOnly
                blockPopups = Default.blockPopups
                javaScriptEnabled = Default.javaScriptEnabled
            }
            SettingsSection.PERMISSIONS -> {
                capturePolicy = Default.capturePolicy
                webNotificationsEnabled = Default.webNotificationsEnabled
            }
            SettingsSection.HIGHLIGHTS -> {
                highlightsEnabled = Default.highlightsEnabled
            }
            SettingsSection.BACKGROUND -> {
                keepAliveInBackground = Default.keepAliveInBackground
            }
        }
    }

    // MARK: - Downloads

    /**
     * Downloads live here rather than in the tab, so they survive the tab that
     * started them - same as the iOS view model's `downloads`.
     */
    val downloads = DownloadManager(appContext).also { manager ->
        manager.onFinished = { name ->
            toast(loc("ダウンロード完了: $name", "Downloaded: $name"), ToastIcon.DOWNLOAD)
        }
    }

    var showDownloads by mutableStateOf(false)

    /** How many downloads are running, for the badge on the toolbar menu. */
    val activeDownloads: Int get() = downloads.activeCount

    /**
     * Download a URL directly - from the link menu, or because the WebView
     * handed us something it cannot display.
     */
    fun startDownload(
        url: String,
        userAgent: String? = null,
        contentDisposition: String? = null,
        mimeType: String? = null,
        contentLength: Long = 0,
    ) {
        val started = downloads.start(
            url = url,
            userAgent = userAgent,
            contentDisposition = contentDisposition,
            mimeType = mimeType,
            contentLength = contentLength,
            referer = currentUrl,
            cookie = tabManager.cookieHeader(url),
        )
        if (started) {
            toast(loc("ダウンロードを開始しました", "Download started"), ToastIcon.DOWNLOAD)
        } else {
            // blob:/data: URLs and the like: the download service cannot fetch
            // them, and saying so beats appearing to do nothing.
            toast(loc("このリンクはダウンロードできません", "This link can't be downloaded"),
                  ToastIcon.WARNING)
        }
    }

    // MARK: - Toasts

    /**
     * Brief confirmation for things that otherwise happen invisibly - a
     * finished download, a copied link, a profile switching itself on.
     * Mirrors BrowserViewModel.swift's `toast(_:icon:)`.
     */
    var toastText by mutableStateOf<String?>(null)
        private set
    var toastIcon by mutableStateOf(ToastIcon.SUCCESS)
        private set
    private var toastJob: Job? = null

    fun toast(text: String, icon: ToastIcon = ToastIcon.SUCCESS) {
        toastText = text
        toastIcon = icon
        toastJob?.cancel()
        toastJob = viewModelScope.launch {
            delay(2400)
            toastText = null
        }
    }

    // MARK: - Link under the cursor (right-click menu)

    /**
     * Link the page reported under the pointer on a right-click, so the app can
     * offer a real menu - WebView's own long-press menu never appears in cursor
     * mode, because the trackpad overlay takes the touches.
     */
    data class LinkTarget(val url: String, val text: String)

    var linkTarget by mutableStateOf<LinkTarget?>(null)

    fun openLinkInNewTab() {
        val target = linkTarget ?: return
        // A link opened out of a private tab stays private.
        tabManager.newTab(target.url, isPrivate = isPrivateTab)
        toast(loc("新しいタブで開きました", "Opened in a new tab"), ToastIcon.TAB)
        linkTarget = null
    }

    fun downloadLink() {
        val target = linkTarget ?: return
        startDownload(target.url)
        linkTarget = null
    }

    fun copyLink() {
        val target = linkTarget ?: return
        val clipboard = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboard?.setPrimaryClip(ClipData.newPlainText("URL", target.url))
        toast(loc("リンクをコピーしました", "Link copied"), ToastIcon.COPY)
        linkTarget = null
    }

    // MARK: - Focus the game / FPS meter

    /** True while the page's game element is blown up to fill the screen. */
    var gameFocused by mutableStateOf(false)
        private set

    /** Frames per second reported by the page's own animation loop. */
    var fps by mutableStateOf(0)
        private set

    private var showFpsBacking: Boolean by PersistedBoolean(prefs, "showFPS", Default.showFps)
    var showFps: Boolean
        get() = showFpsBacking
        set(value) {
            showFpsBacking = value
            if (!value) fps = 0
            applyFpsMeter()
        }

    fun applyFpsMeter() {
        webView?.evaluateJavascript("window.__gb && __gb.setFpsMeter($showFps)", null)
    }

    fun handleFpsReport(value: Int) {
        if (showFps) fps = value
    }

    /**
     * Fill the screen with the game itself. Most browser-game pages wrap a
     * small canvas or iframe in ads and site chrome; this promotes that one
     * element and hides everything around it. Pressing again restores it.
     */
    fun toggleGameFocus() {
        val view = webView ?: return
        val focusing = !gameFocused
        val js = if (focusing) "window.__gb && __gb.focusGame()" else "window.__gb && __gb.unfocusGame()"
        view.evaluateJavascript(js) { result ->
            val succeeded = result == "true"
            if (focusing) {
                // Nothing game-shaped on the page - say so by staying put
                // rather than silently pretending it worked.
                gameFocused = succeeded
                if (succeeded) {
                    immersive = true
                    hapticMedium()
                } else {
                    toast(loc("全画面にできる要素が見つかりません", "Nothing to fullscreen on this page"),
                          ToastIcon.WARNING)
                }
            } else {
                gameFocused = false
                hapticLight()
            }
        }
    }

    /**
     * Pinch-to-zoom in cursor mode. The trackpad overlay takes every touch
     * there, so the WebView never sees the pinch itself and pages simply could
     * not be zoomed; [factor] is the frame's change in finger separation.
     */
    fun pinchZoom(factor: Float) {
        val view = webView ?: return
        if (factor.isNaN() || factor <= 0f) return
        view.zoomBy(factor.coerceIn(0.01f, 100f))
    }

    /** Back to the zoom the page opened at, after a pinch. */
    fun resetZoom() {
        val tab = tabManager.activeTab ?: return
        if (tab.pageScale <= 0f || tab.initialScale <= 0f) return
        tab.webView.zoomBy((tab.initialScale / tab.pageScale).coerceIn(0.01f, 100f))
    }

    /** A navigation drops whatever the previous page had focused. */
    fun clearGameFocus() {
        gameFocused = false
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
    /**
     * Factory values for every setting. Launch and reset both read these, so
     * the two cannot drift - previously every default was a literal spelled
     * out at its point of use, and a reset written against those would have
     * been a second copy to keep in sync. Mirrors BrowserViewModel.Default on
     * iOS, value for value.
     */
    object Default {
        const val pcMode = false            // phone mode on a fresh install
        const val desktopMode = false
        const val cursorSensitivity = 1.4f
        const val scrollSpeed = 700f
        val controlScheme = ControlScheme.CLASSIC
        const val hapticsEnabled = true
        const val showFps = false
        const val joystickUsesArrows = false
        const val showScrollButtons = true
        const val appLanguage = 0           // follow the system
        const val appTheme = 0              // dark
        const val toolbarOnBottom = false
        val searchEngine = SearchEngine.GOOGLE
        val newTabPage = NewTabPage.HOME
        const val autofillEnabled = true
        const val adBlockEnabled = true
        const val useFullAdList = false
        val trackingLevel = TrackerBlocker.BALANCED
        const val appLockEnabled = false
        const val fraudWarning = true
        const val httpsOnly = true
        const val blockPopups = false
        const val javaScriptEnabled = true
        val capturePolicy = CapturePolicy.ASK
        const val webNotificationsEnabled = true
        const val highlightsEnabled = false
        const val keepAliveInBackground = false
    }

    var newTabPage: NewTabPage by PersistedEnum(prefs, "newTabPage", NewTabPage.entries.toTypedArray(), Default.newTabPage)

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
    private var desktopModeBacking: Boolean by PersistedBoolean(prefs, "desktopMode", Default.desktopMode)
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

    private var pcModeBacking: Boolean by PersistedBoolean(prefs, "pcMode", Default.pcMode)

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
    var controlScheme: ControlScheme by PersistedEnum(prefs, "controlScheme", ControlScheme.entries.toTypedArray(), Default.controlScheme)
    var cursorSensitivity: Float by PersistedFloat(prefs, "cursorSensitivity", Default.cursorSensitivity)
    var scrollSpeed: Float by PersistedFloat(prefs, "scrollSpeed", Default.scrollSpeed)
    var hapticsEnabled: Boolean by PersistedBoolean(prefs, "hapticsEnabled", Default.hapticsEnabled)
    var joystickUsesArrows: Boolean by PersistedBoolean(prefs, "joystickUsesArrows", Default.joystickUsesArrows)
    var searchEngine: SearchEngine by PersistedEnum(prefs, "searchEngine", SearchEngine.entries.toTypedArray(), Default.searchEngine)
    // newTabPage is declared above, before `tabManager` — see its doc comment.
    var appTheme: Int by PersistedInt(prefs, "appTheme", Default.appTheme)
    var appLanguage: Int by PersistedInt(prefs, "appLanguage", Default.appLanguage)
    var toolbarOnBottom: Boolean by PersistedBoolean(prefs, "toolbarOnBottom", Default.toolbarOnBottom)
    var showScrollButtons: Boolean by PersistedBoolean(prefs, "showScrollButtons", Default.showScrollButtons)
    var autofillEnabled: Boolean by PersistedBoolean(prefs, "autofillEnabled", Default.autofillEnabled)
    var appLockEnabled: Boolean by PersistedBoolean(prefs, "appLockEnabled", Default.appLockEnabled)
    var fraudWarning: Boolean by PersistedBoolean(prefs, "fraudWarning", Default.fraudWarning)
    var httpsOnly: Boolean by PersistedBoolean(prefs, "httpsOnly", Default.httpsOnly)
    var blockPopups: Boolean by PersistedBoolean(prefs, "blockPopups", Default.blockPopups)
    var javaScriptEnabled: Boolean by PersistedBoolean(prefs, "javaScriptEnabled", Default.javaScriptEnabled)
    var capturePolicy: CapturePolicy by PersistedEnum(prefs, "capturePolicy", CapturePolicy.entries.toTypedArray(), Default.capturePolicy)
    var webNotificationsEnabled: Boolean by PersistedBoolean(prefs, "webNotificationsEnabled", Default.webNotificationsEnabled)
    private var highlightsEnabledBacking: Boolean by PersistedBoolean(prefs, "highlightsEnabled", Default.highlightsEnabled)
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

    private var adBlockEnabledBacking: Boolean by PersistedBoolean(prefs, "adBlockEnabled", Default.adBlockEnabled)
    var adBlockEnabled: Boolean
        get() = adBlockEnabledBacking
        set(value) {
            adBlockEnabledBacking = value
            webView?.reload()
        }

    private var useFullAdListBacking: Boolean by PersistedBoolean(prefs, "useFullAdList", Default.useFullAdList)
    var useFullAdList: Boolean
        get() = useFullAdListBacking
        set(value) {
            useFullAdListBacking = value
            if (value) viewModelScope.launch { AdBlocker.refreshFullListIfNeeded(appContext) }
            webView?.reload()
        }

    var trackingLevel: Int by PersistedInt(prefs, "trackingLevel", Default.trackingLevel)

    /** Runtime lock state (not itself persisted — reset to appLockEnabled's
     *  value at process start, same as ContentView's `@State isLocked` init). */
    var isLocked by mutableStateOf(false)

    private var keepAliveBacking: Boolean by PersistedBoolean(prefs, "keepAliveInBackground", Default.keepAliveInBackground)
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
        // Recently visited sites, newest first and one entry per host, so the
        // game you were playing yesterday is one tap away.
        val seenHosts = mutableSetOf<String>()
        val recents = history.asReversed().mapNotNull { entry ->
            val host = try { Uri.parse(entry.url).host } catch (e: Exception) { null }
            if (host.isNullOrEmpty() || host in seenHosts) return@mapNotNull null
            if (bookmarks.any { it.url == entry.url }) return@mapNotNull null
            seenHosts.add(host)
            """
            <a class="chip" href="${htmlEscape(entry.url)}">
              <img src="https://www.google.com/s2/favicons?domain=${htmlEscape(host)}&sz=32" onerror="this.remove()" alt="">
              <span>${htmlEscape(host)}</span>
            </a>
            """.trimIndent()
        }.take(8).joinToString("")

        val recentSection = if (recents.isEmpty()) {
            ""
        } else {
            """
            <div class="section">${loc("最近開いたサイト", "Recently visited")}</div>
            <div class="chips">$recents</div>
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
          .section { width:100%; max-width:560px; margin:30px 0 12px; font-size:12px;
                     font-weight:600; letter-spacing:.4px; color:#7b8794; }
          .chips { display:flex; flex-wrap:wrap; gap:8px; width:100%; max-width:560px; }
          .chip { display:flex; align-items:center; gap:7px; padding:8px 12px;
                  border-radius:999px; background:#141b23; border:1px solid #1f2937;
                  text-decoration:none; color:#c7d2dd; font-size:12px; max-width:100%; }
          .chip:active { background:#1d2733; }
          .chip img { width:16px; height:16px; border-radius:4px; }
          .chip span { overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
        </style></head><body>
        <h1>GameBrowser</h1>
        <p class="sub">${loc("ブックマークから開く、または上のバーで検索", "Open a bookmark, or search using the bar above")}</p>
        <div class="grid">$tiles</div>
        $recentSection
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
        val dx = dxRaw * effectiveSensitivity * accel
        val dy = dyRaw * effectiveSensitivity * accel
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
        downloads.dispose()
        super.onCleared()
    }
}
