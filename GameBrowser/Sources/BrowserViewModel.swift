import SwiftUI
import WebKit
import Combine
import CoreLocation
import UserNotifications
import AVFAudio

@MainActor
final class BrowserViewModel: NSObject, ObservableObject {

    // MARK: - Published state

    @Published var urlText: String = ""
    @Published var currentURL: URL?
    @Published var pageTitle: String = ""
    @Published var isLoading: Bool = false
    @Published var progress: Double = 0
    @Published var canGoBack: Bool = false
    @Published var canGoForward: Bool = false

    /// Cursor position in web-view coordinates.
    @Published var cursorPosition: CGPoint = CGPoint(x: 200, y: 200)

    enum InputMode: Int, CaseIterable {
        case trackpad   // relative cursor, laptop-style
        case touch      // native WKWebView touch handling

        var icon: String {
            switch self {
            case .trackpad: return "cursorarrow"
            case .touch: return "hand.tap"
            }
        }
        var label: String {
            switch self {
            case .trackpad: return loc("マウス", "Mouse")
            case .touch: return loc("タッチ", "Touch")
            }
        }
    }

    /// App-level mode: phone = plain touch browser without the control bar,
    /// PC = gaming browser with virtual mouse tools and desktop UA.
    @Published var pcMode: Bool {
        didSet {
            UserDefaults.standard.set(pcMode, forKey: "pcMode")
            if pcMode {
                inputMode = .trackpad
            } else {
                inputMode = .touch
                keyboardVisible = false
                joystickVisible = false
                imeActive = false
                if dragLocked { toggleDragLock() }
            }
            // Match the site presentation to the mode (still overridable).
            if desktopMode != pcMode { desktopMode = pcMode }
        }
    }

    @Published var inputMode: InputMode = .trackpad {
        didSet { applyKeyboardSuppression() }
    }

    // MARK: - Downloads

    let downloads = DownloadManager()
    @Published var showDownloads = false
    /// Mirrored so the toolbar can badge itself: SwiftUI doesn't observe
    /// objects nested inside an observed object.
    @Published var activeDownloads = 0
    private var cancellables = Set<AnyCancellable>()

    // MARK: - Toast

    /// Brief confirmation for things that otherwise happen invisibly.
    @Published var toastText: String?
    @Published var toastIcon: String = "checkmark.circle.fill"
    private var toastTask: Task<Void, Never>?

    func toast(_ text: String, icon: String = "checkmark.circle.fill") {
        toastText = text
        toastIcon = icon
        toastTask?.cancel()
        toastTask = Task { [weak self] in
            try? await Task.sleep(for: .seconds(2.4))
            guard !Task.isCancelled else { return }
            self?.toastText = nil
        }
    }

    // MARK: - Link under the cursor (right-click menu)

    /// Link the page reported under the pointer on a right-click, so the app
    /// can offer a real menu — WebKit's own long-press menu never appears in
    /// cursor mode, because the trackpad overlay takes the touches.
    struct LinkTarget: Equatable {
        var url: URL
        var text: String
    }
    @Published var linkTarget: LinkTarget?

    func openLinkInNewTab() {
        guard let target = linkTarget else { return }
        newTab(url: target.url)
        toast(loc("新しいタブで開きました", "Opened in a new tab"), icon: "square.on.square")
        linkTarget = nil
    }

    func copyLink() {
        guard let target = linkTarget else { return }
        UIPasteboard.general.string = target.url.absoluteString
        toast(loc("リンクをコピーしました", "Link copied"), icon: "doc.on.doc")
        linkTarget = nil
    }

    func downloadLink() {
        guard let target = linkTarget else { return }
        startDownload(target.url)
        linkTarget = nil
    }

    /// Download a URL directly (from the link menu). WebKit fetches it and
    /// hands back a WKDownload through the delegate below.
    func startDownload(_ url: URL) {
        guard !tabs.isEmpty else { return }
        webView.startDownload(using: URLRequest(url: url)) { [weak self] download in
            Task { @MainActor in
                guard let self else { return }
                download.delegate = self.downloads
                self.downloads.begin(download, suggested: nil, source: url)
                self.toast(loc("ダウンロードを開始しました", "Download started"),
                           icon: "arrow.down.circle.fill")
            }
        }
    }

    // MARK: - Focus the game / FPS meter

    /// True while the page's game element is blown up to fill the screen.
    @Published var gameFocused = false
    /// Frames per second reported by the page's own animation loop.
    @Published var fps: Int = 0
    @Published var showFPS: Bool {
        didSet {
            UserDefaults.standard.set(showFPS, forKey: "showFPS")
            if !showFPS { fps = 0 }
            applyFPSMeter()
        }
    }

    func applyFPSMeter() {
        guard !tabs.isEmpty else { return }
        webView.evaluateJavaScript(
            "window.__gb && __gb.setFpsMeter(\(showFPS))", completionHandler: nil)
    }

    /// Fill the screen with the game itself. Most browser-game pages wrap a
    /// small canvas or iframe in ads and site chrome; this promotes that one
    /// element and hides everything around it. Tapping again restores it.
    func toggleGameFocus() {
        guard !tabs.isEmpty else { return }
        let focusing = !gameFocused
        webView.evaluateJavaScript(
            focusing ? "window.__gb && __gb.focusGame()" : "window.__gb && __gb.unfocusGame()"
        ) { [weak self] result, _ in
            Task { @MainActor in
                guard let self else { return }
                let succeeded = (result as? Bool) ?? false
                if focusing {
                    // Nothing game-shaped on the page — say so by staying put
                    // rather than silently pretending it worked.
                    self.gameFocused = succeeded
                    if succeeded {
                        self.immersive = true
                        self.hapticMedium()
                    }
                } else {
                    self.gameFocused = false
                    self.hapticLight()
                }
            }
        }
    }

    /// Allow pinch-zoom even on pages that ask not to be scaled — nearly
    /// every game does, and a desktop layout on a phone often needs it.
    @Published var forceZoom: Bool {
        didSet {
            UserDefaults.standard.set(forceZoom, forKey: "forceZoom")
            applyViewportMode()
        }
    }

    /// Desktop presentation wins over the zoom override — it needs the
    /// viewport for the wide layout, and a desktop-mode page can be pinched
    /// anyway. Only one of the two ever patches the page.
    func applyViewportMode(in target: WKWebView? = nil) {
        guard !tabs.isEmpty else { return }
        let mode = desktopMode ? "desktop" : (forceZoom ? "zoom" : "none")
        // Defaults to the active tab; the page that just finished loading
        // passes itself, since that may be a background tab.
        (target ?? webView).evaluateJavaScript(
            "window.__gb && __gb.setViewportMode('\(mode)')", completionHandler: nil)
    }

    /// Pinch handled by the trackpad overlay: in cursor mode the overlay takes
    /// every touch, so the web view never sees a pinch of its own and the
    /// page simply could not be zoomed. `point` is the midpoint between the
    /// fingers, which stays put while the page scales around it.
    func pinchZoom(by factor: CGFloat, at point: CGPoint) {
        guard !tabs.isEmpty else { return }
        let scrollView = webView.scrollView
        guard scrollView.maximumZoomScale > scrollView.minimumZoomScale else { return }
        let current = scrollView.zoomScale
        let target = min(max(current * factor, scrollView.minimumZoomScale),
                         scrollView.maximumZoomScale)
        guard abs(target - current) > 0.0005 else { return }

        let offset = scrollView.contentOffset
        let contentPoint = CGPoint(x: (offset.x + point.x) / current,
                                   y: (offset.y + point.y) / current)
        scrollView.setZoomScale(target, animated: false)

        var newOffset = CGPoint(x: contentPoint.x * target - point.x,
                                y: contentPoint.y * target - point.y)
        let maxX = max(scrollView.contentSize.width - scrollView.bounds.width, 0)
        let maxY = max(scrollView.contentSize.height - scrollView.bounds.height, 0)
        newOffset.x = min(max(newOffset.x, 0), maxX)
        newOffset.y = min(max(newOffset.y, 0), maxY)
        scrollView.setContentOffset(newOffset, animated: false)
    }

    var isZoomed: Bool {
        guard !tabs.isEmpty else { return false }
        return webView.scrollView.zoomScale > webView.scrollView.minimumZoomScale + 0.01
    }

    func resetZoom() {
        guard !tabs.isEmpty else { return }
        webView.scrollView.setZoomScale(webView.scrollView.minimumZoomScale, animated: true)
        hapticLight()
    }

    /// In cursor mode, page focus must not open the iOS keyboard.
    func applyKeyboardSuppression() {
        guard !tabs.isEmpty else { return }
        webView.evaluateJavaScript(
            "window.__gb && __gb.setSuppressKeyboard(\(cursorMode))",
            completionHandler: nil
        )
    }

    /// Trackpad behavior scheme.
    enum ControlScheme: Int, CaseIterable {
        case classic   // long-press = drag, no momentum (original behavior)
        case quick     // tap-and-a-half = drag, flick momentum, long-press = right click

        var label: String {
            switch self {
            case .classic: return loc("従来", "Classic")
            case .quick: return loc("クイック", "Quick")
            }
        }
    }

    @Published var controlScheme: ControlScheme {
        didSet { UserDefaults.standard.set(controlScheme.rawValue, forKey: "controlScheme") }
    }

    var cursorMode: Bool { inputMode != .touch }
    @Published var keyboardVisible: Bool = false {
        didSet { if !keyboardVisible { releaseAllKeys() } }
    }
    @Published var fullKeyboard: Bool = false {
        didSet { releaseAllKeys() }   // avoid stuck keys when the layout swaps mid-press
    }
    @Published var pointerLocked: Bool = false
    /// The page under the cursor uses `cursor: none` (draws its own cursor).
    @Published var pageHidesCursor: Bool = false
    /// CSS cursor keyword under the pointer ("auto", "pointer", "text", ...).
    @Published var cursorStyle: String = "auto"
    @Published var dragLocked: Bool = false
    /// True while the left mouse button is held; drives cursor press animation.
    @Published var mouseButtonDown: Bool = false
    /// Cursor dims after a few seconds without movement.
    @Published var cursorFaded: Bool = false
    private var cursorFadeTask: Task<Void, Never>?

    @Published var joystickVisible: Bool = false {
        didSet { if !joystickVisible { releaseAllKeys() } }   // avoid a stuck held key when hidden mid-press
    }

    /// User-dragged joystick position offset from its default corner.
    @Published var joystickOffset: CGSize = BrowserViewModel.clampJoystickOffset(CGSize(
        width: UserDefaults.standard.double(forKey: "joystickOffsetX"),
        height: UserDefaults.standard.double(forKey: "joystickOffsetY")
    )) {
        didSet {
            UserDefaults.standard.set(joystickOffset.width, forKey: "joystickOffsetX")
            UserDefaults.standard.set(joystickOffset.height, forKey: "joystickOffsetY")
        }
    }

    /// Keep the joystick (including its move handle) fully inside the web
    /// area, so it can never be tucked under the toolbars.
    static func clampJoystickOffset(_ offset: CGSize, in container: CGSize = .zero) -> CGSize {
        let area = container == .zero ? UIScreen.main.bounds.size : container
        let stickWidth: CGFloat = 116
        let stickHeight: CGFloat = 142   // handle + spacing + stick
        let margin: CGFloat = 24         // default corner padding on both axes
        return CGSize(
            width: min(max(offset.width, -10), max(area.width - stickWidth - margin, 10)),
            height: max(min(offset.height, 10), -max(area.height - stickHeight - margin, 10))
        )
    }

    func clampJoystick(_ offset: CGSize) -> CGSize {
        Self.clampJoystickOffset(offset, in: webViewSize)
    }

    /// Called when the joystick is shown, in case a stale saved position is
    /// off screen (e.g. saved in landscape, reopened in portrait).
    func ensureJoystickOnScreen() {
        let clamped = clampJoystick(joystickOffset)
        if clamped != joystickOffset { joystickOffset = clamped }
    }

    func resetJoystickPosition() {
        joystickOffset = .zero
    }
    @Published var joystickUsesArrows: Bool {
        didSet { UserDefaults.standard.set(joystickUsesArrows, forKey: "joystickUsesArrows") }
    }

    @Published var hapticsEnabled: Bool {
        didSet { UserDefaults.standard.set(hapticsEnabled, forKey: "hapticsEnabled") }
    }

    // MARK: - Custom control pads

    /// Saved layouts. Every game binds different keys, and the fixed on-screen
    /// keyboard can't reach most of them — so the user builds their own pad.
    @Published var profiles: [ControlProfile] {
        // Dragging a button rewrites this on every touch sample, and encoding
        // every profile to JSON at 120Hz is not free — coalesce the writes.
        didSet { saveProfilesDebounced() }
    }
    private var saveProfilesTask: Task<Void, Never>?

    private func saveProfilesDebounced() {
        saveProfilesTask?.cancel()
        let snapshot = profiles
        saveProfilesTask = Task {
            try? await Task.sleep(for: .milliseconds(400))
            guard !Task.isCancelled else { return }
            ControlProfileStore.saveProfiles(snapshot)
        }
    }

    /// Write immediately — the app may not get another chance.
    private func saveProfilesNow() {
        saveProfilesTask?.cancel()
        ControlProfileStore.saveProfiles(profiles)
    }
    @Published var activeProfileID: UUID? {
        didSet { ControlProfileStore.saveActive(activeProfileID) }
    }
    /// Draw the active profile's buttons over the page.
    @Published var padVisible: Bool {
        didSet {
            UserDefaults.standard.set(padVisible, forKey: "padVisible")
            if !padVisible {
                releasePadButtons()
                padEditing = false
            }
        }
    }
    /// Arrange mode: buttons are draggable and tapping one opens its settings.
    @Published var padEditing: Bool = false {
        didSet { if padEditing { releasePadButtons() } }
    }
    /// Buttons currently latched down (sticky).
    @Published var padLatched: Set<UUID> = []
    @Published var selectedPadButton: UUID?
    @Published var showPadInspector = false
    @Published var showProfiles = false

    /// host → profile id, so a game's controls come back on their own.
    private var siteProfiles: [String: String] = ControlProfileStore.loadAssignments()
    private var turboTimers: [UUID: Timer] = [:]

    var activeProfile: ControlProfile? { profiles.first { $0.id == activeProfileID } }

    private func updateProfile(_ mutate: (inout ControlProfile) -> Void) {
        guard let id = activeProfileID,
              let index = profiles.firstIndex(where: { $0.id == id }) else { return }
        mutate(&profiles[index])
    }

    func activateProfile(_ id: UUID?) {
        releasePadButtons()
        activeProfileID = id
        selectedPadButton = nil
        if let profile = activeProfile {
            joystickUsesArrows = profile.joystickArrows
            if profile.showJoystick { joystickVisible = true }
            padVisible = true
        }
        hapticSelection()
    }

    func createProfile() {
        let profile = ControlProfile(name: loc("新しいプロファイル", "New profile"))
        profiles.append(profile)
        activateProfile(profile.id)
    }

    func duplicateProfile(_ id: UUID) {
        guard let source = profiles.first(where: { $0.id == id }) else { return }
        var copy = source
        copy.id = UUID()
        copy.name = source.name + loc(" のコピー", " copy")
        // Fresh button ids, or the two profiles would share latch/turbo state.
        copy.buttons = source.buttons.map { button in
            var fresh = button
            fresh.id = UUID()
            return fresh
        }
        profiles.append(copy)
        activateProfile(copy.id)
    }

    func deleteProfile(_ id: UUID) {
        profiles.removeAll { $0.id == id }
        siteProfiles = siteProfiles.filter { $0.value != id.uuidString }
        ControlProfileStore.saveAssignments(siteProfiles)
        if activeProfileID == id { activateProfile(profiles.first?.id) }
    }

    func renameProfile(_ id: UUID, to name: String) {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, let index = profiles.firstIndex(where: { $0.id == id })
        else { return }
        profiles[index].name = trimmed
    }

    func addPresetProfiles() {
        profiles.append(contentsOf: ControlProfile.presets())
        hapticMedium()
    }

    // MARK: Buttons

    func addPadButton() {
        guard activeProfileID != nil else { return }
        let new = PadButton(keys: ["Space"], x: 0.5, y: 0.5)
        updateProfile { $0.buttons.append(new) }
        selectedPadButton = new.id
        showPadInspector = true
        hapticLight()
    }

    func movePadButton(_ id: UUID, to point: CGPoint) {
        updateProfile { profile in
            guard let index = profile.buttons.firstIndex(where: { $0.id == id }) else { return }
            profile.buttons[index].x = min(max(point.x, 0.04), 0.96)
            profile.buttons[index].y = min(max(point.y, 0.04), 0.96)
        }
    }

    func updatePadButton(_ id: UUID, _ mutate: (inout PadButton) -> Void) {
        updateProfile { profile in
            guard let index = profile.buttons.firstIndex(where: { $0.id == id }) else { return }
            mutate(&profile.buttons[index])
        }
    }

    func deletePadButton(_ id: UUID) {
        releasePadButton(id)
        updateProfile { $0.buttons.removeAll { $0.id == id } }
        if selectedPadButton == id { selectedPadButton = nil }
    }

    /// Several keys on one button are held together — Shift+W is sprint.
    func addBinding(_ name: String, to id: UUID) {
        updatePadButton(id) { button in
            if PadKeyName.mouseNames.contains(name) {
                button.mouseButton = (name == PadKeyName.rightClick) ? 2 : 0
                button.keys = []
            } else {
                button.mouseButton = nil
                if !button.keys.contains(name) { button.keys.append(name) }
            }
        }
        hapticLight()
    }

    func removeBinding(_ name: String, from id: UUID) {
        updatePadButton(id) { button in
            if PadKeyName.mouseNames.contains(name) {
                button.mouseButton = nil
            } else {
                button.keys.removeAll { $0 == name }
            }
        }
    }

    // MARK: Per-site assignment

    func siteProfileID(for host: String) -> UUID? {
        siteProfiles[host].flatMap { UUID(uuidString: $0) }
    }

    func siteProfileName(for host: String) -> String? {
        guard let id = siteProfileID(for: host) else { return nil }
        return profiles.first { $0.id == id }?.name
    }

    func assignCurrentProfileToSite(_ pinned: Bool) {
        guard let host = currentURL?.host else { return }
        if pinned, let id = activeProfileID {
            siteProfiles[host] = id.uuidString
        } else {
            siteProfiles.removeValue(forKey: host)
        }
        ControlProfileStore.saveAssignments(siteProfiles)
        hapticLight()
    }

    /// Bring back the profile pinned to this site, if it isn't already on.
    fileprivate func applySiteProfile(for url: URL?) {
        guard let host = url?.host,
              let id = siteProfileID(for: host),
              id != activeProfileID,
              profiles.contains(where: { $0.id == id }) else { return }
        activateProfile(id)
    }

    // MARK: Physical controller mapping

    func gamepadBinding(_ slot: GamepadSlot) -> String {
        activeProfile?.gamepadKey(slot) ?? slot.defaultKey
    }

    func setGamepadBinding(_ slot: GamepadSlot, to name: String) {
        updateProfile { $0.gamepadMap[slot.rawValue] = name }
        hapticLight()
    }

    func resetGamepadMapping() {
        updateProfile { $0.gamepadMap = [:] }
        hapticLight()
    }

    // MARK: Per-profile tuning

    /// The profile's own cursor speed, or the global one when it has none.
    var effectiveSensitivity: Double { activeProfile?.cursorSensitivity ?? cursorSensitivity }

    func setProfileSensitivity(_ value: Double?) {
        updateProfile { $0.cursorSensitivity = value }
    }

    func setPadOpacity(_ value: Double) {
        updateProfile { $0.padOpacity = value }
    }

    func setAutoFocusGame(_ on: Bool) {
        updateProfile { $0.autoFocusGame = on }
        hapticLight()
    }

    /// Back to the values a new profile starts with — the buttons themselves
    /// and the controller mapping are left alone.
    func resetProfileTuning() {
        let fresh = ControlProfile(name: "")
        updateProfile { profile in
            profile.padOpacity = fresh.padOpacity
            profile.cursorSensitivity = fresh.cursorSensitivity
            profile.autoFocusGame = fresh.autoFocusGame
        }
        hapticMedium()
    }

    /// Copy the active profile as JSON — a layout is worth sharing, and worth
    /// keeping somewhere that survives a reinstall.
    @discardableResult
    func copyActiveProfile() -> Bool {
        guard let profile = activeProfile,
              let data = try? JSONEncoder().encode(profile),
              let json = String(data: data, encoding: .utf8) else { return false }
        UIPasteboard.general.string = json
        hapticMedium()
        return true
    }

    /// Load a profile from JSON on the clipboard, as a new copy.
    @discardableResult
    func pasteProfile() -> Bool {
        guard let text = UIPasteboard.general.string,
              let data = text.data(using: .utf8),
              var profile = try? JSONDecoder().decode(ControlProfile.self, from: data)
        else { return false }
        // Fresh ids so pasting the same layout twice gives two profiles
        // rather than two things claiming to be the same one.
        profile.id = UUID()
        profile.buttons = profile.buttons.map { button in
            var fresh = button
            fresh.id = UUID()
            return fresh
        }
        profiles.append(profile)
        activateProfile(profile.id)
        hapticMedium()
        return true
    }

    // MARK: Sending

    func padPress(_ button: PadButton) {
        hapticLight()
        if button.sticky {
            if padLatched.contains(button.id) {
                releasePadButton(button.id)
            } else {
                padLatched.insert(button.id)
                engage(button)
                if button.turbo { startTurbo(button) }
            }
            return
        }
        engage(button)
        if button.turbo { startTurbo(button) }
    }

    func padRelease(_ button: PadButton) {
        guard !button.sticky else { return }   // a latched button waits for the next tap
        stopTurbo(button.id)
        disengage(button)
    }

    private func engage(_ button: PadButton) {
        if let mouse = button.mouseButton {
            mouseDown(button: mouse)
            return
        }
        for name in button.keys {
            if let key = KeyCatalog.key(name) { keyDown(key) }
        }
    }

    private func disengage(_ button: PadButton) {
        if let mouse = button.mouseButton {
            mouseUp(button: mouse)
            return
        }
        // Reverse order so a modifier in a combo is released last.
        for name in button.keys.reversed() {
            if let key = KeyCatalog.key(name) { keyUp(key) }
        }
    }

    /// Turbo: re-press on an interval for games that expect mashing.
    private func startTurbo(_ button: PadButton) {
        stopTurbo(button.id)
        let timer = Timer(timeInterval: 0.09, repeats: true) { [weak self] _ in
            Task { @MainActor in
                guard let self else { return }
                self.disengage(button)
                self.engage(button)
            }
        }
        // .common, so mashing keeps firing while a gesture is tracking.
        RunLoop.main.add(timer, forMode: .common)
        turboTimers[button.id] = timer
    }

    private func stopTurbo(_ id: UUID) {
        turboTimers[id]?.invalidate()
        turboTimers.removeValue(forKey: id)
    }

    private func releasePadButton(_ id: UUID) {
        stopTurbo(id)
        padLatched.remove(id)
        if let button = activeProfile?.buttons.first(where: { $0.id == id }) { disengage(button) }
    }

    /// Drop everything the pads are holding — hiding them, editing, switching
    /// tabs or profiles must never leave a key or mouse button stuck down.
    func releasePadButtons() {
        for (_, timer) in turboTimers { timer.invalidate() }
        turboTimers.removeAll()
        let latched = padLatched
        padLatched.removeAll()
        guard let profile = activeProfile else { return }
        for button in profile.buttons where latched.contains(button.id) { disengage(button) }
    }

    // MARK: - Security settings

    /// Upgrade http:// navigations to https:// (HTTPS-first).
    @Published var httpsOnly: Bool {
        didSet { UserDefaults.standard.set(httpsOnly, forKey: "httpsOnly") }
    }

    /// Google Safe Browsing-backed fraudulent site warning.
    @Published var fraudWarning: Bool {
        didSet {
            UserDefaults.standard.set(fraudWarning, forKey: "fraudWarning")
            for tab in tabs {
                tab.webView.configuration.preferences.isFraudulentWebsiteWarningEnabled = fraudWarning
            }
        }
    }

    /// Refuse window.open popups instead of opening them as tabs.
    @Published var blockPopups: Bool {
        didSet { UserDefaults.standard.set(blockPopups, forKey: "blockPopups") }
    }

    /// Master JavaScript switch (applied per navigation).
    @Published var javaScriptEnabled: Bool {
        didSet {
            UserDefaults.standard.set(javaScriptEnabled, forKey: "javaScriptEnabled")
            webView.reload()
        }
    }

    /// Content-rule-based ad blocking (network-level, Safari-style).
    @Published var adBlockEnabled: Bool {
        didSet {
            UserDefaults.standard.set(adBlockEnabled, forKey: "adBlockEnabled")
            adBlockGeneration += 1
            let generation = adBlockGeneration
            Task { [weak self] in
                await self?.applyAdBlock(generation: generation)
                self?.webView.reload()
            }
        }
    }

    /// Edge-style tracking prevention level.
    @Published var trackingLevel: TrackerBlocker.Level {
        didSet {
            UserDefaults.standard.set(trackingLevel.rawValue, forKey: "trackingLevel")
            trackingRules = nil
            adBlockGeneration += 1
            let generation = adBlockGeneration
            Task { [weak self] in
                await self?.applyAdBlock(generation: generation)
                self?.webView.reload()
            }
        }
    }

    /// Full EasyList mode: downloaded and compiled, ~10,000s of rules.
    @Published var useFullAdList: Bool {
        didSet {
            UserDefaults.standard.set(useFullAdList, forKey: "useFullAdList")
            adBlockRules = nil
            adBlockGeneration += 1
            let generation = adBlockGeneration
            Task { [weak self] in
                await self?.applyAdBlock(generation: generation)
                self?.webView.reload()
            }
        }
    }

    private var adBlockRules: WKContentRuleList?
    private var trackingRules: WKContentRuleList?
    /// Bumped on every setting change; lets an in-flight (reentrant, async)
    /// apply notice it's been superseded and bail instead of overwriting
    /// newer rules with what it originally fetched.
    private var adBlockGeneration = 0

    /// Compile once, then attach/detach the rule lists on every tab.
    private func applyAdBlock(generation: Int) async {
        guard generation == adBlockGeneration else { return }
        if adBlockEnabled && adBlockRules == nil {
            let rules: WKContentRuleList?
            if useFullAdList, let full = await AdBlocker.fullRuleList() {
                rules = full
            } else {
                // Builtin list; also the fallback when the download fails.
                rules = await AdBlocker.compiledRuleList()
            }
            guard generation == adBlockGeneration else { return }
            adBlockRules = rules
        }
        if trackingLevel != .off && trackingRules == nil {
            let level = trackingLevel
            let rules = await TrackerBlocker.compiledRuleList(for: level)
            guard generation == adBlockGeneration else { return }
            trackingRules = rules
        }
        guard generation == adBlockGeneration else { return }
        for tab in tabs {
            let controller = tab.webView.configuration.userContentController
            controller.removeAllContentRuleLists()
            if adBlockEnabled, let rules = adBlockRules {
                controller.add(rules)
            }
            if trackingLevel != .off, let rules = trackingRules {
                controller.add(rules)
            }
        }
    }

    /// Attach the rules to a newly created web view.
    fileprivate func attachAdBlock(to webView: WKWebView) {
        guard adBlockEnabled || trackingLevel != .off else { return }
        let generation = adBlockGeneration
        Task { [weak self] in
            guard let self else { return }
            if self.adBlockEnabled {
                if self.adBlockRules == nil {
                    let rules: WKContentRuleList?
                    if self.useFullAdList, let full = await AdBlocker.fullRuleList() {
                        rules = full
                    } else {
                        rules = await AdBlocker.compiledRuleList()
                    }
                    if generation == self.adBlockGeneration { self.adBlockRules = rules }
                }
                if let rules = self.adBlockRules {
                    webView.configuration.userContentController.add(rules)
                }
            }
            if self.trackingLevel != .off {
                if self.trackingRules == nil {
                    let level = self.trackingLevel
                    let rules = await TrackerBlocker.compiledRuleList(for: level)
                    if generation == self.adBlockGeneration { self.trackingRules = rules }
                }
                if let rules = self.trackingRules {
                    webView.configuration.userContentController.add(rules)
                }
            }
        }
    }

    // MARK: - Search engine / new tab page / app lock

    enum SearchEngine: Int, CaseIterable {
        case google, bing, duckduckgo, yahooJapan

        var label: String {
            switch self {
            case .google: return "Google"
            case .bing: return "Bing"
            case .duckduckgo: return "DuckDuckGo"
            case .yahooJapan: return "Yahoo! JAPAN"
            }
        }

        func searchURL(for query: String) -> URL? {
            let q = query.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? query
            switch self {
            case .google: return URL(string: "https://www.google.com/search?q=\(q)")
            case .bing: return URL(string: "https://www.bing.com/search?q=\(q)")
            case .duckduckgo: return URL(string: "https://duckduckgo.com/?q=\(q)")
            case .yahooJapan: return URL(string: "https://search.yahoo.co.jp/search?p=\(q)")
            }
        }
    }

    @Published var searchEngine: SearchEngine {
        didSet { UserDefaults.standard.set(searchEngine.rawValue, forKey: "searchEngine") }
    }

    enum NewTabPage: Int, CaseIterable {
        case home, startPage

        var label: String {
            switch self {
            case .home: return loc("ホーム(Google)", "Home (Google)")
            case .startPage: return loc("スタートページ", "Start page")
            }
        }
    }

    @Published var newTabPage: NewTabPage {
        didSet { UserDefaults.standard.set(newTabPage.rawValue, forKey: "newTabPage") }
    }

    /// Require Face ID / passcode when the app returns to the foreground.
    @Published var appLockEnabled: Bool {
        didSet { UserDefaults.standard.set(appLockEnabled, forKey: "appLockEnabled") }
    }

    // MARK: - Autofill (passwords & payment card)

    @Published var autofillEnabled: Bool {
        didSet { UserDefaults.standard.set(autofillEnabled, forKey: "autofillEnabled") }
    }

    @Published var credentials: [Credential] = [] {
        didSet { AutofillStore.saveCredentials(credentials) }
    }
    @Published var paymentCard: PaymentCard = PaymentCard() {
        didSet { AutofillStore.saveCard(paymentCard) }
    }

    /// Credential waiting for the user's "save?" decision.
    @Published var pendingCredential: Credential?
    /// Suggestions for the focused field ("password" or "card").
    @Published var autofillSuggestions: [Credential] = []
    @Published var cardSuggestionVisible = false

    private var currentHost: String { currentURL?.host ?? "" }

    fileprivate func handleAutofillFocus(kind: String) {
        guard autofillEnabled else { return }
        if kind == "password" {
            autofillSuggestions = AutofillStore.credentials(for: currentHost, in: credentials)
            cardSuggestionVisible = false
        } else if kind == "card" {
            cardSuggestionVisible = !paymentCard.isEmpty
            autofillSuggestions = []
        }
    }

    fileprivate func handleCredentialSubmitted(username: String, password: String) {
        guard autofillEnabled, !isPrivateTab, !password.isEmpty, !currentHost.isEmpty
        else { return }
        // Already saved identically? Nothing to do.
        if credentials.contains(where: {
            $0.domain == currentHost && $0.username == username && $0.password == password
        }) { return }
        pendingCredential = Credential(domain: currentHost, username: username, password: password)
    }

    func savePendingCredential() {
        guard let pending = pendingCredential else { return }
        // Update an existing entry for the same site/user, else append.
        if let index = credentials.firstIndex(where: {
            $0.domain == pending.domain && $0.username == pending.username
        }) {
            credentials[index].password = pending.password
        } else {
            credentials.append(pending)
        }
        pendingCredential = nil
    }

    func fill(_ credential: Credential) {
        js("""
        window.__gb && __gb.fillCredentials('\(jsEscape(credential.username))', \
        '\(jsEscape(credential.password))')
        """)
        autofillSuggestions = []
    }

    func fillCard() {
        guard !paymentCard.isEmpty else { return }
        js("""
        window.__gb && __gb.fillCard('\(jsEscape(paymentCard.number))', \
        '\(jsEscape(paymentCard.holder))', '\(jsEscape(paymentCard.expMonth))', \
        '\(jsEscape(paymentCard.expYear))')
        """)
        cardSuggestionVisible = false
    }

    func dismissAutofill() {
        autofillSuggestions = []
        cardSuggestionVisible = false
        pendingCredential = nil
    }

    // MARK: - Granular browsing-data deletion

    func clearData(cookies: Bool, cache: Bool, history clearHistoryFlag: Bool) {
        var types = Set<String>()
        if cookies {
            types.formUnion([
                WKWebsiteDataTypeCookies,
                WKWebsiteDataTypeLocalStorage,
                WKWebsiteDataTypeSessionStorage,
                WKWebsiteDataTypeIndexedDBDatabases,
                WKWebsiteDataTypeWebSQLDatabases,
            ])
        }
        if cache {
            types.formUnion([
                WKWebsiteDataTypeDiskCache,
                WKWebsiteDataTypeMemoryCache,
                WKWebsiteDataTypeOfflineWebApplicationCache,
                WKWebsiteDataTypeFetchCache,
            ])
        }
        if !types.isEmpty {
            WKWebsiteDataStore.default().removeData(ofTypes: types, modifiedSince: .distantPast) {}
        }
        if clearHistoryFlag {
            clearHistory()
            clearTabSnapshots()
        }
    }

    /// What to do when a site asks for camera/microphone access.
    enum CapturePolicy: Int, CaseIterable {
        case ask, allow, deny
        var label: String {
            switch self {
            case .ask: return loc("毎回確認", "Ask")
            case .allow: return loc("許可", "Allow")
            case .deny: return loc("拒否", "Deny")
            }
        }
    }

    @Published var capturePolicy: CapturePolicy {
        didSet { UserDefaults.standard.set(capturePolicy.rawValue, forKey: "capturePolicy") }
    }

    private let locationManager = CLLocationManager()

    // MARK: - Background keep-alive (silent audio)

    /// Plays looping silence so iOS never suspends the app: pages keep
    /// running (timers, sockets, our Notification bridge) in the background.
    /// Costs battery, so it's opt-in.
    @Published var keepAliveInBackground: Bool {
        didSet {
            UserDefaults.standard.set(keepAliveInBackground, forKey: "keepAliveInBackground")
            keepAliveInBackground ? startSilence() : stopSilence()
        }
    }

    // MARK: - Highlight recording (instant replay)

    /// Buffer the last ~15s of on-screen play invisibly, so a single tap can
    /// save "what just happened" as a video — like a console's instant replay.
    @Published var highlightsEnabled: Bool {
        didSet {
            UserDefaults.standard.set(highlightsEnabled, forKey: "highlightsEnabled")
            highlightsEnabled ? HighlightRecorder.shared.startBuffering() : HighlightRecorder.shared.stopBuffering()
        }
    }

    @Published var highlightSaveState: HighlightSaveState = .idle

    enum HighlightSaveState: Equatable {
        case idle, saving, saved, failed
    }

    func saveHighlight() {
        guard highlightsEnabled, highlightSaveState != .saving else { return }
        highlightSaveState = .saving
        hapticMedium()
        HighlightRecorder.shared.saveHighlight { [weak self] success in
            guard let self else { return }
            self.highlightSaveState = success ? .saved : .failed
            if success { self.hapticMedium() }
            Task {
                try? await Task.sleep(for: .seconds(2))
                self.highlightSaveState = .idle
            }
        }
    }

    private var silenceEngine: AVAudioEngine?

    private func startSilence() {
        guard silenceEngine == nil else { return }
        let engine = AVAudioEngine()
        let player = AVAudioPlayerNode()
        engine.attach(player)

        let format = AVAudioFormat(standardFormatWithSampleRate: 44100, channels: 1)!
        engine.connect(player, to: engine.mainMixerNode, format: format)
        engine.mainMixerNode.outputVolume = 0

        guard let buffer = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: 44100) else { return }
        buffer.frameLength = 44100   // one second of silence, looped

        do {
            try AVAudioSession.sharedInstance().setCategory(
                .playback, mode: .default, options: [.mixWithOthers])
            try AVAudioSession.sharedInstance().setActive(true)
            try engine.start()
            player.scheduleBuffer(buffer, at: nil, options: .loops)
            player.play()
            silenceEngine = engine
        } catch {
            engine.stop()
        }
    }

    private func stopSilence() {
        silenceEngine?.stop()
        silenceEngine = nil
    }

    /// Allow sites to post iOS notifications via the bridged Notification API.
    @Published var webNotificationsEnabled: Bool {
        didSet { UserDefaults.standard.set(webNotificationsEnabled, forKey: "webNotificationsEnabled") }
    }

    func requestNotificationPermission() {
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge]) { _, _ in }
    }

    fileprivate func postWebNotification(title: String, body: String) {
        guard webNotificationsEnabled else { return }
        let center = UNUserNotificationCenter.current()
        center.getNotificationSettings { settings in
            let deliver = {
                let content = UNMutableNotificationContent()
                content.title = title.isEmpty ? "GameBrowser" : title
                content.body = body
                content.sound = .default
                center.add(UNNotificationRequest(
                    identifier: UUID().uuidString, content: content, trigger: nil))
            }
            if settings.authorizationStatus == .notDetermined {
                center.requestAuthorization(options: [.alert, .sound, .badge]) { granted, _ in
                    if granted { deliver() }
                }
            } else if settings.authorizationStatus == .authorized ||
                      settings.authorizationStatus == .provisional {
                deliver()
            }
        }
    }

    /// Trigger the system location permission prompt (used by page geolocation).
    func requestLocationPermission() {
        locationManager.requestWhenInUseAuthorization()
    }

    // Centralized haptics so the toggle applies everywhere.
    func hapticLight() {
        guard hapticsEnabled else { return }
        UIImpactFeedbackGenerator(style: .light).impactOccurred()
    }

    func hapticMedium() {
        guard hapticsEnabled else { return }
        UIImpactFeedbackGenerator(style: .medium).impactOccurred()
    }

    func hapticSelection() {
        guard hapticsEnabled else { return }
        UISelectionFeedbackGenerator().selectionChanged()
    }

    private var gamepad: GamepadController?
    @Published var pressedKeys: Set<InputBridge.Key> = []
    @Published var immersive: Bool = false {
        didSet { updateIdleTimer() }
    }

    /// True while a physical game controller is attached (set by GamepadController).
    var gamepadConnected: Bool = false {
        didSet { if gamepadConnected != oldValue { updateIdleTimer() } }
    }

    /// Playing with a controller produces no touches at all, so iOS dims and
    /// then locks the screen in the middle of a game — the same happens
    /// during a long cutscene or an idle/strategy game. Hold the idle timer
    /// off while the user is clearly playing: a controller is attached, or
    /// the app is in fullscreen (immersive) mode. Both are explicit signals,
    /// so ordinary browsing still sleeps normally.
    private func updateIdleTimer() {
        UIApplication.shared.isIdleTimerDisabled = immersive || gamepadConnected
    }

    @Published var cursorSensitivity: Double {
        didSet { UserDefaults.standard.set(cursorSensitivity, forKey: "cursorSensitivity") }
    }

    @Published var bookmarks: [Bookmark] {
        didSet {
            if let data = try? JSONEncoder().encode(bookmarks) {
                UserDefaults.standard.set(data, forKey: "bookmarks")
            }
        }
    }

    struct Bookmark: Codable, Identifiable, Equatable {
        var id = UUID()
        var title: String
        var url: String
    }

    struct HistoryEntry: Codable, Identifiable {
        var id = UUID()
        var title: String
        var url: String
        var date: Date
    }

    @Published var history: [HistoryEntry] {
        didSet {
            if let data = try? JSONEncoder().encode(history) {
                UserDefaults.standard.set(data, forKey: "history")
            }
        }
    }

    @Published var showScrollButtons: Bool {
        didSet { UserDefaults.standard.set(showScrollButtons, forKey: "showScrollButtons") }
    }

    /// UI language: 0 = system, 1 = Japanese, 2 = English.
    @Published var appLanguage: Int {
        didSet {
            UserDefaults.standard.set(appLanguage, forKey: "appLanguage")
            L.setting = appLanguage
        }
    }

    /// Show the URL/toolbar at the bottom of the screen instead of the top.
    @Published var toolbarOnBottom: Bool {
        didSet { UserDefaults.standard.set(toolbarOnBottom, forKey: "toolbarOnBottom") }
    }

    /// Translate the current page via Google Translate's proxy
    /// (host.translate.goog) — works without any API key.
    func translatePage() {
        guard let url = currentURL, let host = url.host, !host.hasSuffix(".translate.goog"),
              var comps = URLComponents(url: url, resolvingAgainstBaseURL: false) else { return }
        comps.scheme = "https"
        comps.host = host
            .replacingOccurrences(of: "-", with: "--")
            .replacingOccurrences(of: ".", with: "-") + ".translate.goog"
        let target = L.translationTarget
        var items = comps.queryItems ?? []
        items.append(contentsOf: [
            URLQueryItem(name: "_x_tr_sl", value: "auto"),
            URLQueryItem(name: "_x_tr_tl", value: target),
            URLQueryItem(name: "_x_tr_hl", value: target),
        ])
        comps.queryItems = items
        if let translated = comps.url {
            webView.load(URLRequest(url: translated))
        }
    }

    /// Desktop (PC) or mobile presentation: user agent + content mode.
    @Published var desktopMode: Bool {
        didSet {
            UserDefaults.standard.set(desktopMode, forKey: "desktopMode")
            applyContentMode()
        }
    }

    /// Switch every tab between the desktop and mobile presentation.
    ///
    /// This has to *re-navigate*, not reload. WebKit fixes a page's content
    /// mode — and with it the layout viewport, 980pt wide for desktop instead
    /// of the device's width — when the navigation that created the page
    /// begins; reload() re-renders the page in the mode it already had. The
    /// desktop user agent went out while the layout stayed phone-width, so a
    /// responsive site kept serving and laying out its mobile design: turning
    /// on PC mode visibly did nothing. The request also bypasses the cache,
    /// since a server that already answered with mobile HTML would otherwise
    /// just hand the same document back.
    private func applyContentMode() {
        for tab in tabs {
            tab.webView.customUserAgent = desktopMode ? Self.desktopUserAgent : nil
        }
        guard tabs.indices.contains(activeTabIndex) else { return }

        // Background tabs re-navigate the next time they are shown, rather
        // than all reloading at once behind the user's back.
        for (index, tab) in tabs.enumerated() where index != activeTabIndex {
            if let url = tab.webView.url, url.scheme == "http" || url.scheme == "https" {
                tab.pendingLoad = url
            }
        }

        if let url = webView.url, url.scheme == "http" || url.scheme == "https" {
            webView.load(URLRequest(url: url, cachePolicy: .reloadIgnoringLocalCacheData))
        } else {
            webView.reload()   // the start page and about: pages have no URL to re-request
        }
    }

    var webViewSize: CGSize = .zero {
        didSet {
            // Keep the cursor on screen after rotation / layout changes.
            if webViewSize != oldValue, webViewSize != .zero {
                cursorPosition = clamp(cursorPosition)
            }
        }
    }

    private var observers: [NSKeyValueObservation] = []
    private var saveTabsTask: Task<Void, Never>?
    private var lastClickTime: Date = .distantPast
    private var lastClickPoint: CGPoint = .zero

    static let desktopUserAgent =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 " +
        "(KHTML, like Gecko) Version/17.4 Safari/605.1.15"

    static let homeURL = URL(string: "https://www.google.com")!
    /// Marker URL used to persist tabs showing the built-in start page.
    static let startPageMarker = "gb://home"

    // MARK: - Start page

    /// Dark start page with bookmark tiles, shown in new tabs and via Home.
    private func startPageHTML() -> String {
        let tiles = bookmarks.map { bookmark -> String in
            let host = URL(string: bookmark.url)?.host ?? ""
            let initial = String(bookmark.title.prefix(1)).uppercased()
            // Bookmark titles come from whatever <title> the site served, so
            // they are untrusted markup here, not text.
            return """
            <a class="tile" href="\(Self.escapeHTML(bookmark.url))">
              <div class="icon"><img src="https://www.google.com/s2/favicons?domain=\
            \(Self.escapeHTML(host))&sz=64" \
            onerror="this.remove()" alt=""><span>\(Self.escapeHTML(initial))</span></div>
              <div class="name">\(Self.escapeHTML(bookmark.title))</div>
            </a>
            """
        }.joined()
        let subtitle = loc("ブックマークから開く、または上のバーで検索",
                           "Open a bookmark, or search from the bar above")

        // Recently visited sites, newest first and one entry per host, so the
        // game you were playing yesterday is one tap away.
        var seenHosts = Set<String>()
        let recents = history.reversed().compactMap { entry -> String? in
            guard let host = URL(string: entry.url)?.host, !seenHosts.contains(host),
                  !bookmarks.contains(where: { $0.url == entry.url }) else { return nil }
            seenHosts.insert(host)
            return """
            <a class="chip" href="\(Self.escapeHTML(entry.url))">
              <img src="https://www.google.com/s2/favicons?domain=\
            \(Self.escapeHTML(host))&sz=32" onerror="this.remove()" alt="">
              <span>\(Self.escapeHTML(host))</span>
            </a>
            """
        }.prefix(8).joined()

        let recentSection = recents.isEmpty ? "" : """
        <div class="section">\(loc("最近開いたサイト", "Recently visited"))</div>
        <div class="chips">\(recents)</div>
        """

        return """
        <!DOCTYPE html><html><head>
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <style>
          * { margin:0; padding:0; box-sizing:border-box; -webkit-tap-highlight-color:transparent; }
          body { background:#0b0f14; color:#e8edf2; font-family:-apple-system,sans-serif;
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
        <p class="sub">\(subtitle)</p>
        <div class="grid">\(tiles)</div>
        \(recentSection)
        </body></html>
        """
    }

    func loadStartPage(in webView: WKWebView? = nil) {
        (webView ?? self.webView).loadHTMLString(startPageHTML(), baseURL: nil)
    }

    // MARK: - Error page

    static func escapeHTML(_ text: String) -> String {
        text.replacingOccurrences(of: "&", with: "&amp;")
            .replacingOccurrences(of: "<", with: "&lt;")
            .replacingOccurrences(of: ">", with: "&gt;")
            .replacingOccurrences(of: "\"", with: "&quot;")
            .replacingOccurrences(of: "'", with: "&#39;")
    }

    /// A failed load used to leave the tab on a blank black screen with no
    /// explanation and nothing to tap — indistinguishable from the app
    /// hanging. Show what went wrong, on the URL that failed, with a retry.
    fileprivate func showErrorPage(for error: Error, in webView: WKWebView) {
        let ns = error as NSError
        // These are not failures: a load the user or the app replaced (a new
        // link tapped mid-load), and the policy cancel that HTTPS-first
        // performs on every plain-http navigation before reissuing it.
        if ns.domain == NSURLErrorDomain, ns.code == NSURLErrorCancelled { return }
        if ns.domain == "WebKitErrorDomain", ns.code == 101 || ns.code == 102 || ns.code == 204 {
            return
        }
        // Only ever act on the URL the error itself names. Falling back to
        // webView.url would risk replacing the page that is still committed
        // and perfectly readable underneath a failed navigation.
        let failing = (ns.userInfo[NSURLErrorFailingURLErrorKey] as? URL)
            ?? (ns.userInfo[NSURLErrorFailingURLStringErrorKey] as? String).flatMap { URL(string: $0) }
        guard let failed = failing,
              failed.scheme == "http" || failed.scheme == "https" else { return }
        // loadSimulatedRequest keeps `failed` as the web view's URL, so the
        // URL bar still shows where the user was going, back/forward stay
        // consistent, and the retry link is a plain navigation to it.
        webView.loadSimulatedRequest(
            URLRequest(url: failed),
            responseHTML: Self.errorPageHTML(for: ns, url: failed)
        )
    }

    /// Dark error page styled like the start page.
    private static func errorPageHTML(for error: NSError, url: URL) -> String {
        let offline = error.domain == NSURLErrorDomain && [
            NSURLErrorNotConnectedToInternet,
            NSURLErrorNetworkConnectionLost,
            NSURLErrorDataNotAllowed,
        ].contains(error.code)
        let title = offline
            ? loc("インターネットに接続されていません", "You're offline")
            : loc("ページを開けませんでした", "This page didn't load")
        let message = offline
            ? loc("Wi-Fi またはモバイル通信を確認してから、もう一度お試しください。",
                  "Check your Wi-Fi or mobile connection, then try again.")
            : escapeHTML(error.localizedDescription)
        let href = escapeHTML(url.absoluteString)

        return """
        <!DOCTYPE html><html><head>
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <style>
          * { margin:0; padding:0; box-sizing:border-box; -webkit-tap-highlight-color:transparent; }
          body { background:#0b0f14; color:#e8edf2; font-family:-apple-system,sans-serif;
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
        <div class="icon">\(offline ? "📡" : "⚠️")</div>
        <h1>\(title)</h1>
        <p class="msg">\(message)</p>
        <p class="host">\(href)</p>
        <a class="retry" href="\(href)">\(loc("再試行", "Try again"))</a>
        </body></html>
        """
    }

    // MARK: - Init

    /// Every setting's factory value, in one place: launch reads them here and
    /// so does "reset", so the two can't drift apart.
    enum Default {
        static let pcMode = false            // phone mode on a fresh install
        static let cursorSensitivity = 1.4
        static let scrollSpeed = 700.0
        static let controlScheme = ControlScheme.classic
        static let hapticsEnabled = true
        static let forceZoom = true
        static let showFPS = false
        static let joystickUsesArrows = false
        static let showScrollButtons = true
        static let appLanguage = 0           // follow the system
        static let appTheme = 0              // dark
        static let toolbarOnBottom = false
        static let searchEngine = SearchEngine.google
        static let newTabPage = NewTabPage.home
        static let autofillEnabled = true
        static let adBlockEnabled = true
        static let useFullAdList = false
        static let trackingLevel = TrackerBlocker.Level.balanced
        static let appLockEnabled = false
        static let fraudWarning = true
        static let httpsOnly = true
        static let blockPopups = false
        static let javaScriptEnabled = true
        static let capturePolicy = CapturePolicy.ask
        static let webNotificationsEnabled = true
        static let highlightsEnabled = false
        static let keepAliveInBackground = false
    }

    // MARK: - Resetting settings

    /// One settings card's worth of options.
    enum SettingsSection: CaseIterable {
        case browserMode, controls, searchTabs, appearance, autofill
        case security, permissions, highlights, background
    }

    /// Put one section back to its factory values, with feedback.
    ///
    /// Browsing data is deliberately left alone — bookmarks, history, saved
    /// passwords and cards, open tabs and control profiles all survive. This
    /// resets settings, not the user's own stuff.
    func resetSettings(_ section: SettingsSection) {
        applyDefaults(to: section)
        hapticMedium()
    }

    func resetAllSettings() {
        for section in SettingsSection.allCases { applyDefaults(to: section) }
        hapticMedium()
    }

    private func applyDefaults(to section: SettingsSection) {
        switch section {
        case .browserMode:
            pcMode = Default.pcMode
            desktopMode = Default.pcMode
        case .controls:
            controlScheme = Default.controlScheme
            cursorSensitivity = Default.cursorSensitivity
            scrollSpeed = Default.scrollSpeed
            hapticsEnabled = Default.hapticsEnabled
            forceZoom = Default.forceZoom
            showFPS = Default.showFPS
            joystickUsesArrows = Default.joystickUsesArrows
            resetJoystickPosition()
        case .searchTabs:
            searchEngine = Default.searchEngine
            newTabPage = Default.newTabPage
        case .appearance:
            // Theme lives in @AppStorage; writing the same key updates it.
            UserDefaults.standard.set(Default.appTheme, forKey: "appTheme")
            appLanguage = Default.appLanguage
            toolbarOnBottom = Default.toolbarOnBottom
            showScrollButtons = Default.showScrollButtons
            desktopMode = pcMode   // its factory value is "match the mode"
        case .autofill:
            autofillEnabled = Default.autofillEnabled
        case .security:
            adBlockEnabled = Default.adBlockEnabled
            useFullAdList = Default.useFullAdList
            trackingLevel = Default.trackingLevel
            appLockEnabled = Default.appLockEnabled
            fraudWarning = Default.fraudWarning
            httpsOnly = Default.httpsOnly
            blockPopups = Default.blockPopups
            javaScriptEnabled = Default.javaScriptEnabled
        case .permissions:
            capturePolicy = Default.capturePolicy
            webNotificationsEnabled = Default.webNotificationsEnabled
        case .highlights:
            highlightsEnabled = Default.highlightsEnabled
        case .background:
            keepAliveInBackground = Default.keepAliveInBackground
        }
    }

    override init() {
        let defaults = UserDefaults.standard
        let savedSensitivity = defaults.double(forKey: "cursorSensitivity")
        cursorSensitivity = savedSensitivity > 0 ? savedSensitivity : Default.cursorSensitivity
        pcMode = defaults.object(forKey: "pcMode") as? Bool ?? Default.pcMode
        showScrollButtons = defaults.object(forKey: "showScrollButtons") as? Bool
            ?? Default.showScrollButtons
        appLanguage = defaults.object(forKey: "appLanguage") as? Int ?? Default.appLanguage
        toolbarOnBottom = defaults.object(forKey: "toolbarOnBottom") as? Bool
            ?? Default.toolbarOnBottom
        joystickUsesArrows = defaults.object(forKey: "joystickUsesArrows") as? Bool
            ?? Default.joystickUsesArrows
        hapticsEnabled = defaults.object(forKey: "hapticsEnabled") as? Bool
            ?? Default.hapticsEnabled
        controlScheme = ControlScheme(rawValue: defaults.integer(forKey: "controlScheme"))
            ?? Default.controlScheme
        httpsOnly = defaults.object(forKey: "httpsOnly") as? Bool ?? Default.httpsOnly
        fraudWarning = defaults.object(forKey: "fraudWarning") as? Bool ?? Default.fraudWarning
        blockPopups = defaults.object(forKey: "blockPopups") as? Bool ?? Default.blockPopups
        javaScriptEnabled = defaults.object(forKey: "javaScriptEnabled") as? Bool
            ?? Default.javaScriptEnabled
        capturePolicy = CapturePolicy(rawValue: defaults.integer(forKey: "capturePolicy"))
            ?? Default.capturePolicy
        adBlockEnabled = defaults.object(forKey: "adBlockEnabled") as? Bool
            ?? Default.adBlockEnabled
        if let saved = defaults.object(forKey: "trackingLevel") as? Int {
            trackingLevel = TrackerBlocker.Level(rawValue: saved) ?? Default.trackingLevel
        } else {
            trackingLevel = Default.trackingLevel   // on by default
        }
        searchEngine = SearchEngine(rawValue: defaults.integer(forKey: "searchEngine"))
            ?? Default.searchEngine
        newTabPage = NewTabPage(rawValue: defaults.integer(forKey: "newTabPage"))
            ?? Default.newTabPage
        appLockEnabled = defaults.object(forKey: "appLockEnabled") as? Bool
            ?? Default.appLockEnabled
        autofillEnabled = defaults.object(forKey: "autofillEnabled") as? Bool
            ?? Default.autofillEnabled
        useFullAdList = defaults.object(forKey: "useFullAdList") as? Bool ?? Default.useFullAdList
        webNotificationsEnabled = defaults.object(forKey: "webNotificationsEnabled") as? Bool
            ?? Default.webNotificationsEnabled
        keepAliveInBackground = defaults.object(forKey: "keepAliveInBackground") as? Bool
            ?? Default.keepAliveInBackground
        highlightsEnabled = defaults.object(forKey: "highlightsEnabled") as? Bool
            ?? Default.highlightsEnabled
        desktopMode = defaults.object(forKey: "desktopMode") as? Bool
            ?? (defaults.object(forKey: "pcMode") as? Bool ?? Default.pcMode)
        forceZoom = defaults.object(forKey: "forceZoom") as? Bool ?? Default.forceZoom
        showFPS = defaults.object(forKey: "showFPS") as? Bool ?? Default.showFPS
        profiles = ControlProfileStore.loadProfiles()
        activeProfileID = ControlProfileStore.loadActive()
        padVisible = defaults.bool(forKey: "padVisible")
        if let data = defaults.data(forKey: "history"),
           let saved = try? JSONDecoder().decode([HistoryEntry].self, from: data) {
            history = saved
        } else {
            history = []
        }
        if let data = defaults.data(forKey: "bookmarks"),
           let saved = try? JSONDecoder().decode([Bookmark].self, from: data) {
            bookmarks = saved
        } else {
            // Starter bookmarks: popular PC browser game portals.
            bookmarks = [
                Bookmark(title: "CrazyGames", url: "https://www.crazygames.com"),
                Bookmark(title: "Poki", url: "https://poki.com"),
                Bookmark(title: "itch.io", url: "https://itch.io/games/platform-web"),
                Bookmark(title: "Miniclip", url: "https://www.miniclip.com"),
            ]
        }

        super.init()

        gamepad = GamepadController(viewModel: self)
        // A finished download is otherwise completely silent.
        downloads.$lastFinished
            .compactMap { $0 }
            .sink { [weak self] name in
                Task { @MainActor in
                    self?.toast(loc("保存しました: ", "Saved: ") + name,
                                icon: "checkmark.circle.fill")
                }
            }
            .store(in: &cancellables)
        downloads.$activeCount
            .removeDuplicates()
            .sink { [weak self] count in
                Task { @MainActor in self?.activeDownloads = count }
            }
            .store(in: &cancellables)
        UNUserNotificationCenter.current().delegate = self
        credentials = AutofillStore.loadCredentials()
        paymentCard = AutofillStore.loadCard()

        // Playback session + the "audio" background mode keep pages alive in
        // the background while they are producing sound (game music, calls).
        try? AVAudioSession.sharedInstance().setCategory(
            .playback, mode: .default, options: [.mixWithOthers])
        try? AVAudioSession.sharedInstance().setActive(true)
        if keepAliveInBackground { startSilence() }
        if highlightsEnabled { HighlightRecorder.shared.startBuffering() }
        inputMode = pcMode ? .trackpad : .touch
        restoreTabs()

        // Persist tabs when the app is backgrounded or killed by the system.
        NotificationCenter.default.addObserver(
            forName: UIApplication.didEnterBackgroundNotification,
            object: nil, queue: .main
        ) { [weak self] _ in
            Task { @MainActor in
                self?.saveTabs()
                self?.saveProfilesNow()
            }
        }
    }

    // MARK: - Tab persistence

    private static let snapshotsDir: URL = {
        let dir = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("TabSnapshots", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }()

    private func snapshotFile(_ index: Int) -> URL {
        Self.snapshotsDir.appendingPathComponent("\(index).jpg")
    }

    /// Persist thumbnails so the tab switcher isn't blank after a relaunch.
    private func saveSnapshots() {
        // Indexed to match saveTabs()'s records, which exclude private tabs —
        // a private tab's thumbnail is a picture of a page that is not
        // supposed to exist after the session.
        let images = tabs.filter { !$0.isPrivate }.map(\.snapshot)
        let dir = Self.snapshotsDir
        Task.detached(priority: .utility) {
            for (i, image) in images.enumerated() {
                let file = dir.appendingPathComponent("\(i).jpg")
                if let data = image?.jpegData(compressionQuality: 0.5) {
                    try? data.write(to: file, options: .atomic)
                }
            }
            // Drop stale files from closed tabs.
            var i = images.count
            while FileManager.default.fileExists(atPath: dir.appendingPathComponent("\(i).jpg").path) {
                try? FileManager.default.removeItem(at: dir.appendingPathComponent("\(i).jpg"))
                i += 1
            }
        }
    }

    /// Tab thumbnails are pictures of the pages you visited, so clearing
    /// history has to take them with it — they used to outlive it on disk,
    /// and stayed visible in the tab switcher afterwards.
    private func clearTabSnapshots() {
        for tab in tabs { tab.snapshot = nil }
        let dir = Self.snapshotsDir
        Task.detached(priority: .utility) {
            let files = (try? FileManager.default.contentsOfDirectory(
                at: dir, includingPropertiesForKeys: nil)) ?? []
            for file in files { try? FileManager.default.removeItem(at: file) }
        }
    }

    /// Per-tab persisted state (URL, scroll position, ...).
    private struct TabRecord: Codable {
        var url: String
        var scrollX: Double = 0
        var scrollY: Double = 0
    }

    private func saveTabs() {
        // Private tabs are left out entirely: they must not reappear after a
        // relaunch, and neither their URLs nor their thumbnails belong on disk.
        let persisted = tabs.enumerated().filter { !$0.element.isPrivate }
        let records = persisted.map { entry -> TabRecord in
            let tab = entry.element
            let url = tab.webView.url
            // loadHTMLString reports about:blank — persist the start-page marker.
            let urlString: String
            if url == nil || url?.scheme == "about" {
                urlString = tab.pendingURL?.absoluteString ?? Self.startPageMarker
            } else {
                urlString = url!.absoluteString
            }
            // A restored tab that hasn't been opened yet has an empty web
            // view, so keep the scroll position it was saved with instead of
            // overwriting it with that empty view's (0, 0).
            let offset = tab.pendingScroll ?? tab.webView.scrollView.contentOffset
            return TabRecord(url: urlString, scrollX: offset.x, scrollY: max(offset.y, 0))
        }
        let defaults = UserDefaults.standard
        if let data = try? JSONEncoder().encode(records) {
            defaults.set(data, forKey: "savedTabRecords")
        }
        // Expressed within the persisted subset, since private tabs shift
        // every index after them.
        let activeAmongPersisted = persisted.firstIndex { $0.offset == activeTabIndex } ?? 0
        defaults.set(activeAmongPersisted, forKey: "savedActiveTabIndex")
        saveSnapshots()
    }

    private func restoreTabs() {
        let defaults = UserDefaults.standard
        var records: [TabRecord] = []
        if let data = defaults.data(forKey: "savedTabRecords"),
           let decoded = try? JSONDecoder().decode([TabRecord].self, from: data) {
            records = decoded
        } else if let legacy = defaults.stringArray(forKey: "savedTabs") {
            records = legacy.map { TabRecord(url: $0) }   // pre-scroll format
        }
        let valid = records.filter { URL(string: $0.url) != nil }
        guard !valid.isEmpty else {
            newTab()
            return
        }
        for (i, record) in valid.enumerated() {
            let url = URL(string: record.url)!
            let tab = Tab(webView: makeWebView())
            tab.pendingURL = url
            // Deferred: selectTab() loads a tab the first time it is shown.
            // Loading every restored tab up front meant a relaunch with a
            // dozen tabs fired a dozen page loads at once — slow to launch,
            // and all of it competing for memory and bandwidth with the one
            // tab the user is actually looking at.
            tab.pendingLoad = url
            tab.pendingScroll = CGPoint(x: record.scrollX, y: record.scrollY)
            tab.snapshot = UIImage(contentsOfFile: snapshotFile(i).path)
            tabs.append(tab)
        }
        let saved = defaults.integer(forKey: "savedActiveTabIndex")
        selectTab(min(max(saved, 0), tabs.count - 1))
    }

    // MARK: - Tabs

    final class Tab: Identifiable, ObservableObject {
        let id = UUID()
        let webView: WKWebView
        /// Private tabs use an ephemeral data store, record no history, and
        /// are never written to disk — neither the URL nor the thumbnail.
        let isPrivate: Bool
        @Published var snapshot: UIImage?
        /// Last requested URL; used for persistence while the page is still loading.
        var pendingURL: URL?
        /// Set while this tab still owes itself a load — a restored tab that
        /// hasn't been opened yet, or a brand new one. Popup tabs never set
        /// it: WebKit performs those loads itself.
        var pendingLoad: URL?
        /// Scroll position to restore once the page finishes loading.
        var pendingScroll: CGPoint?
        init(webView: WKWebView, isPrivate: Bool = false) {
            self.webView = webView
            self.isPrivate = isPrivate
        }

        @MainActor var title: String {
            let t = webView.title ?? ""
            if !t.isEmpty { return t }
            if let host = webView.url?.host { return host }
            // A restored tab that hasn't been opened yet has no web view URL
            // to fall back on — only the one it is going to load.
            if let pending = pendingURL,
               pending.absoluteString != BrowserViewModel.startPageMarker,
               let host = pending.host {
                return host
            }
            return loc("新しいタブ", "New Tab")
        }
        @MainActor var urlString: String {
            if let url = webView.url { return url.absoluteString }
            guard let pending = pendingURL,
                  pending.absoluteString != BrowserViewModel.startPageMarker else { return "" }
            return pending.absoluteString
        }
    }

    @Published var tabs: [Tab] = []
    @Published var activeTabIndex: Int = 0

    var webView: WKWebView { tabs[activeTabIndex].webView }

    /// One shared ephemeral store, so private tabs share a session with each
    /// other but nothing with normal browsing. It is replaced once the last
    /// private tab closes — that, not closing a single tab, is what ends the
    /// private session.
    private var privateDataStore = WKWebsiteDataStore.nonPersistent()

    private func endPrivateSessionIfEmpty() {
        guard !tabs.contains(where: { $0.isPrivate }) else { return }
        privateDataStore = WKWebsiteDataStore.nonPersistent()
    }

    // MARK: - Appearance

    /// The interface style the page itself should render against.
    ///
    /// This used to be left to the window. `.preferredColorScheme` sets the
    /// window's override after the scene's first layout, but tabs are created
    /// and start loading from this class's initialiser — before a window
    /// exists at all. The first page of a cold start therefore answered
    /// `prefers-color-scheme` from the device's setting rather than the app's,
    /// and only a later navigation came out right, which is why switching to
    /// PC mode (which re-navigates every tab) appeared to be what fixed it.
    /// Setting it on the web view takes the window out of the picture.
    var webInterfaceStyle: UIUserInterfaceStyle {
        switch UserDefaults.standard.object(forKey: "appTheme") as? Int ?? Default.appTheme {
        case 1: return .light
        case 2: return .unspecified   // follow the device
        default: return .dark
        }
    }

    /// Re-apply after the setting changes. The media query re-evaluates on a
    /// trait change by itself, so nothing needs reloading.
    func applyInterfaceStyle() {
        let style = webInterfaceStyle
        for tab in tabs { tab.webView.overrideUserInterfaceStyle = style }
    }

    private func makeWebView(isPrivate: Bool = false) -> WKWebView {
        let config = WKWebViewConfiguration()
        // Cookies, storage and caches live only in memory for a private tab
        // and go away with the session.
        config.websiteDataStore = isPrivate ? privateDataStore : .default()
        config.allowsInlineMediaPlayback = true
        config.mediaTypesRequiringUserActionForPlayback = []
        config.preferences.javaScriptCanOpenWindowsAutomatically = true
        config.preferences.isFraudulentWebsiteWarningEnabled = fraudWarning
        config.upgradeKnownHostsToHTTPS = httpsOnly
        // The first navigation in a new tab uses this, before the delegate
        // below gets a say — hardcoding .desktop meant a new tab in phone mode
        // laid its first page out as desktop and then switched on the next one.
        config.defaultWebpagePreferences.preferredContentMode = desktopMode ? .desktop : .mobile

        let userScript = WKUserScript(
            source: InputBridge.script,
            injectionTime: .atDocumentStart,
            forMainFrameOnly: false   // inject into iframes so games inside them get events
        )
        config.userContentController.addUserScript(userScript)
        config.userContentController.add(ScriptMessageProxy(self), name: "gbEvents")

        let webView = WKWebView(frame: .zero, configuration: config)
        webView.overrideUserInterfaceStyle = webInterfaceStyle
        webView.customUserAgent = desktopMode ? Self.desktopUserAgent : nil
        webView.allowsBackForwardNavigationGestures = false
        webView.scrollView.contentInsetAdjustmentBehavior = .never
        webView.isOpaque = false
        webView.backgroundColor = .black
        webView.navigationDelegate = self
        webView.uiDelegate = self
        attachAdBlock(to: webView)
        return webView
    }

    func newTab(url: URL? = nil, isPrivate: Bool = false) {
        let tab = Tab(webView: makeWebView(isPrivate: isPrivate), isPrivate: isPrivate)
        let useStartPage = url == nil && newTabPage == .startPage && !isPrivate
        tab.pendingURL = useStartPage ? URL(string: Self.startPageMarker) : (url ?? Self.homeURL)
        tab.pendingLoad = tab.pendingURL
        tabs.append(tab)
        selectTab(tabs.count - 1)   // performs the load
        saveTabs()
        if isPrivate { hapticMedium() }
    }

    /// True when the tab on screen is private — drives the violet accent and
    /// everything that must not be recorded.
    var isPrivateTab: Bool {
        tabs.indices.contains(activeTabIndex) && tabs[activeTabIndex].isPrivate
    }

    var privateTabCount: Int { tabs.filter(\.isPrivate).count }

    /// Load a tab that hasn't been loaded yet, the first time it is shown.
    private func loadPendingIfNeeded(_ tab: Tab) {
        guard let url = tab.pendingLoad else { return }
        tab.pendingLoad = nil
        if url.absoluteString == Self.startPageMarker {
            loadStartPage(in: tab.webView)
        } else {
            tab.webView.load(URLRequest(url: url))
        }
    }

    func selectTab(_ index: Int) {
        guard tabs.indices.contains(index) else { return }
        snapshotActiveTab()
        releaseAllKeys()
        if dragLocked { mouseUp() }   // don't leave a mouse button held in the old tab
        activeTabIndex = index
        loadPendingIfNeeded(tabs[index])
        bindObservers(to: tabs[index].webView)
        pointerLocked = false
        pageHidesCursor = false
        cursorStyle = "auto"
        dragLocked = false
        applyKeyboardSuppression()

        // Silence background tabs (also suspends Web Audio); resume the active one.
        for (i, tab) in tabs.enumerated() {
            tab.webView.setAllMediaPlaybackSuspended(i != index)
        }
        saveTabs()
    }

    /// Capture a thumbnail of the currently displayed tab for the tab switcher.
    func snapshotActiveTab() {
        guard tabs.indices.contains(activeTabIndex) else { return }
        let tab = tabs[activeTabIndex]
        // Don't capture a web view that isn't on screen yet (e.g. during
        // launch restore) — it would overwrite the persisted thumbnail
        // with a blank image.
        guard tab.webView.superview != nil,
              tab.webView.bounds.width > 0,
              tab.webView.url != nil else { return }
        let config = WKSnapshotConfiguration()
        config.afterScreenUpdates = false
        tab.webView.takeSnapshot(with: config) { [weak self] image, _ in
            if let image {
                tab.snapshot = image
                self?.saveSnapshots()
            }
        }
    }

    func closeTab(_ index: Int) {
        guard tabs.indices.contains(index) else { return }
        let wasActive = index == activeTabIndex
        let tab = tabs.remove(at: index)
        tab.webView.stopLoading()
        tab.webView.setAllMediaPlaybackSuspended(true)

        endPrivateSessionIfEmpty()

        if tabs.isEmpty {
            newTab()
        } else if wasActive {
            // The tab sliding into the closed slot becomes active (or the
            // previous one, if the last tab was closed).
            selectTab(min(index, tabs.count - 1))
        } else if index < activeTabIndex {
            // A tab before the active one closed — its own webview/state is
            // unaffected, so just shift the index. Routing this through
            // selectTab() would wrongly reset keys/drag/pointer-lock state
            // on the still-active tab (e.g. dropping a held movement key).
            activeTabIndex -= 1
        }
        saveTabs()
    }

    private func saveTabsDebounced() {
        saveTabsTask?.cancel()
        saveTabsTask = Task { [weak self] in
            try? await Task.sleep(for: .seconds(1))
            guard !Task.isCancelled else { return }
            self?.saveTabs()
        }
    }

    private func bindObservers(to webView: WKWebView) {
        observers = [
            webView.observe(\.estimatedProgress) { [weak self] wv, _ in
                Task { @MainActor in self?.progress = wv.estimatedProgress }
            },
            webView.observe(\.isLoading) { [weak self] wv, _ in
                Task { @MainActor in self?.isLoading = wv.isLoading }
            },
            webView.observe(\.canGoBack) { [weak self] wv, _ in
                Task { @MainActor in self?.canGoBack = wv.canGoBack }
            },
            webView.observe(\.canGoForward) { [weak self] wv, _ in
                Task { @MainActor in self?.canGoForward = wv.canGoForward }
            },
            webView.observe(\.url) { [weak self] wv, _ in
                Task { @MainActor in
                    self?.currentURL = wv.url
                    if let url = wv.url {
                        self?.urlText = url.scheme == "about" ? "" : url.absoluteString
                    }
                    self?.saveTabsDebounced()
                    self?.pageHidesCursor = false   // reset on navigation
                    self?.cursorStyle = "auto"
                    self?.dismissAutofill()
                    self?.applySiteProfile(for: wv.url)
                }
            },
            webView.observe(\.title) { [weak self] wv, _ in
                Task { @MainActor in self?.pageTitle = wv.title ?? "" }
            },
        ]
        // Sync published state to the newly selected tab immediately.
        progress = webView.estimatedProgress
        isLoading = webView.isLoading
        canGoBack = webView.canGoBack
        canGoForward = webView.canGoForward
        currentURL = webView.url
        urlText = webView.url?.absoluteString ?? ""
        pageTitle = webView.title ?? ""
    }

    // MARK: - Navigation

    func submitURL() {
        let text = urlText.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return }

        let url: URL?
        if text.contains("://") {
            url = URL(string: text)
        } else if text.contains(".") && !text.contains(" ") {
            url = URL(string: "https://\(text)")
        } else {
            url = searchEngine.searchURL(for: text)
        }
        if let url { webView.load(URLRequest(url: url)) }
    }

    func goBack() { webView.goBack() }
    func goForward() { webView.goForward() }
    func reload() { webView.reload() }
    func goHome() { webView.load(URLRequest(url: Self.homeURL)) }

    func open(_ bookmark: Bookmark) {
        if let url = URL(string: bookmark.url) { webView.load(URLRequest(url: url)) }
    }

    var isCurrentPageBookmarked: Bool {
        guard let url = currentURL?.absoluteString else { return false }
        return bookmarks.contains { $0.url == url }
    }

    // MARK: - History

    fileprivate func recordHistory(url: URL, title: String) {
        guard url.scheme == "http" || url.scheme == "https" else { return }
        let urlString = url.absoluteString
        if history.last?.url == urlString { return }
        history.append(HistoryEntry(
            title: title.isEmpty ? (url.host ?? urlString) : title,
            url: urlString, date: Date()
        ))
        if history.count > 300 { history.removeFirst(history.count - 300) }
    }

    func clearHistory() { history = [] }

    /// Wipe cookies, cache and all site data.
    func clearBrowsingData() {
        WKWebsiteDataStore.default().removeData(
            ofTypes: WKWebsiteDataStore.allWebsiteDataTypes(),
            modifiedSince: .distantPast
        ) {}
    }

    // MARK: - Find in page

    func findInPage(_ query: String, forward: Bool = true) {
        guard !query.isEmpty else { return }
        js("window.find('\(jsEscape(query))', false, \(forward ? "false" : "true"), true, false, true, false)")
    }

    func clearFindSelection() {
        js("window.getSelection && window.getSelection().removeAllRanges()")
    }

    func toggleBookmark() {
        guard let url = currentURL?.absoluteString else { return }
        if let index = bookmarks.firstIndex(where: { $0.url == url }) {
            bookmarks.remove(at: index)
        } else {
            bookmarks.append(Bookmark(title: pageTitle.isEmpty ? url : pageTitle, url: url))
        }
    }

    // MARK: - Virtual mouse

    private func js(_ source: String) {
        webView.evaluateJavaScript(source, completionHandler: nil)
    }

    private func clamp(_ p: CGPoint) -> CGPoint {
        CGPoint(
            x: min(max(p.x, 0), max(webViewSize.width - 1, 0)),
            y: min(max(p.y, 0), max(webViewSize.height - 1, 0))
        )
    }

    func moveCursor(by delta: CGSize) {
        // Gentle pointer acceleration: slow finger = precision, fast = distance.
        let speed = hypot(delta.width, delta.height)
        let accel = min(2.2, 0.7 + speed / 9)
        let sensitivity = effectiveSensitivity
        let dx = delta.width * sensitivity * accel
        let dy = delta.height * sensitivity * accel
        cursorPosition = clamp(CGPoint(x: cursorPosition.x + dx, y: cursorPosition.y + dy))
        js("window.__gb && __gb.move(\(f(cursorPosition.x)), \(f(cursorPosition.y)), \(f(dx)), \(f(dy)))")
        wakeCursor()
    }

    /// Reset the idle-fade timer: cursor is fully visible while in use.
    private func wakeCursor() {
        cursorFaded = false
        cursorFadeTask?.cancel()
        cursorFadeTask = Task { [weak self] in
            try? await Task.sleep(for: .seconds(4))
            guard !Task.isCancelled else { return }
            self?.cursorFaded = true
        }
    }

    func mouseDown(button: Int = 0) {
        if button == 0 { mouseButtonDown = true }
        wakeCursor()
        js("window.__gb && __gb.down(\(f(cursorPosition.x)), \(f(cursorPosition.y)), \(button))")
    }

    func mouseUp(button: Int = 0) {
        if button == 0 { mouseButtonDown = false }
        let now = Date()
        let isDouble = button == 0
            && now.timeIntervalSince(lastClickTime) < 0.35
            && hypot(cursorPosition.x - lastClickPoint.x, cursorPosition.y - lastClickPoint.y) < 12
        if button == 0 {
            lastClickTime = now
            lastClickPoint = cursorPosition
        }
        js("window.__gb && __gb.up(\(f(cursorPosition.x)), \(f(cursorPosition.y)), \(button), \(isDouble ? 2 : 1))")
    }

    func click(button: Int = 0) {
        mouseDown(button: button)
        mouseUp(button: button)
    }

    func scroll(dx: CGFloat, dy: CGFloat) {
        js("window.__gb && __gb.wheel(\(f(cursorPosition.x)), \(f(cursorPosition.y)), \(f(dx)), \(f(dy)))")
    }

    // MARK: - Smooth scrolling

    private var scrollTimer: Timer?
    private var scrollDirection: CGFloat = 0
    private var scrollHeldSince: Date?
    private var scrollReleasedAt: Date?
    private var scrollLastTick: Date?
    /// Current speed as a multiple of `scrollSpeed`, carried across a release
    /// so the glide starts from whatever the hold had reached.
    private var scrollMultiplier: Double = 0

    /// Hold-to-scroll shape. A page can be twenty screens long, so a single
    /// speed is always wrong: slow enough to place yourself precisely is far
    /// too slow to get anywhere, and fast enough to travel overshoots every
    /// time. Holding ramps from one to the other, and releasing glides to a
    /// stop rather than stopping dead, which is how a flick already behaves.
    private enum ScrollRamp {
        static let start = 0.4          // × scrollSpeed at the moment of press
        static let peak = 1.6           // ... after `ramp` seconds held
        static let ramp: Double = 1.2
        static let glide: Double = 0.35
        /// A stalled main thread must not teleport the page on the next tick.
        static let maxStep: Double = 1.0 / 20
    }

    /// Smooth-scroll speed in px/s while a scroll button is held (user setting).
    @Published var scrollSpeed: Double = {
        let saved = UserDefaults.standard.double(forKey: "scrollSpeed")
        return saved > 0 ? saved : Default.scrollSpeed
    }() {
        didSet { UserDefaults.standard.set(scrollSpeed, forKey: "scrollSpeed") }
    }

    /// Start (or resume) a held scroll in `direction` (+1 down / -1 up).
    func startSmoothScroll(direction: CGFloat) {
        let now = Date()
        // Pressing again mid-glide picks the ramp back up where the speed
        // currently is, so repeated taps read as one continuous scroll rather
        // than restarting from a crawl each time.
        let resumed = ((scrollMultiplier - ScrollRamp.start)
                       / (ScrollRamp.peak - ScrollRamp.start)) * ScrollRamp.ramp
        scrollHeldSince = now.addingTimeInterval(-min(max(resumed, 0), ScrollRamp.ramp))
        scrollReleasedAt = nil
        scrollDirection = direction
        guard scrollTimer == nil else { return }
        scrollLastTick = now
        let timer = Timer(timeInterval: 1.0 / 60, repeats: true) { [weak self] _ in
            Task { @MainActor in self?.smoothScrollTick() }
        }
        // .common, so the page keeps moving while a finger is down: in
        // .default the run loop enters tracking mode and the timer stops
        // firing, which is exactly the whole time a scroll button is held.
        RunLoop.main.add(timer, forMode: .common)
        scrollTimer = timer
    }

    private func smoothScrollTick() {
        let now = Date()
        let step = min(now.timeIntervalSince(scrollLastTick ?? now), ScrollRamp.maxStep)
        scrollLastTick = now

        if let released = scrollReleasedAt {
            let progress = now.timeIntervalSince(released) / ScrollRamp.glide
            guard progress < 1 else { return stopSmoothScroll() }
            // Ease out, so the last few frames barely move.
            scrollMultiplier *= (1 - progress) * (1 - progress)
        } else {
            let held = now.timeIntervalSince(scrollHeldSince ?? now)
            let progress = min(held / ScrollRamp.ramp, 1)
            scrollMultiplier = ScrollRamp.start
                + (ScrollRamp.peak - ScrollRamp.start) * progress
        }
        scroll(dx: 0, dy: scrollDirection * scrollSpeed * scrollMultiplier * step)
    }

    /// Let go: coast to a stop instead of stopping mid-motion.
    func endSmoothScroll() {
        guard scrollTimer != nil, scrollReleasedAt == nil else { return }
        scrollReleasedAt = Date()
    }

    private func stopSmoothScroll() {
        scrollTimer?.invalidate()
        scrollTimer = nil
        scrollReleasedAt = nil
        scrollHeldSince = nil
        scrollLastTick = nil
        scrollMultiplier = 0
    }

    func toggleDragLock() {
        dragLocked.toggle()
        if dragLocked { mouseDown() } else { mouseUp() }
    }

    // MARK: - Virtual keyboard

    func keyDown(_ key: InputBridge.Key) {
        pressedKeys.insert(key)
        sendKey(type: "keydown", key)
    }

    func keyUp(_ key: InputBridge.Key) {
        pressedKeys.remove(key)
        sendKey(type: "keyup", key)
    }

    /// Send keyup for everything still held (tab switch, keyboard hide).
    func releaseAllKeys() {
        for key in pressedKeys { sendKey(type: "keyup", key) }
        pressedKeys.removeAll()
        // Latched pad buttons were holding some of those — and possibly a
        // mouse button, which the loop above doesn't cover.
        releasePadButtons()
    }

    func tapKey(_ key: InputBridge.Key) {
        keyDown(key)
        keyUp(key)
    }

    // MARK: - Built-in romaji IME

    /// While true, virtual keyboard letters feed the romaji buffer instead of
    /// sending key events to the page.
    @Published var imeActive: Bool = false {
        didSet {
            // Closing the IME mid-composition leaves the typed text committed.
            if !imeActive {
                if !imeComposition.isEmpty { setComposition(imeComposition, commit: true) }
                imeClear()
            }
        }
    }
    @Published var imeKana: String = ""
    @Published var imePending: String = ""
    @Published var imeCandidates: [String] = []
    private var romajiBuffer: String = ""
    private var candidateTask: Task<Void, Never>?

    var imeComposition: String { imeKana + imePending }

    func imeType(_ ch: String) {
        romajiBuffer += ch.lowercased()
        recompose()
    }

    func imeBackspace() {
        if romajiBuffer.isEmpty {
            tapKey(InputBridge.backspace)   // empty buffer: delete in the page
            return
        }
        romajiBuffer.removeLast()
        recompose()
    }

    /// Space during composition = request kanji candidates; otherwise a real space.
    func imeSpace() {
        if imeComposition.isEmpty {
            tapKey(InputBridge.space)
        } else {
            fetchCandidates()
        }
    }

    /// Enter = commit composition as-is; otherwise a real Enter.
    func imeConfirm() {
        if imeComposition.isEmpty {
            tapKey(InputBridge.enter)
        } else {
            setComposition(imeComposition, commit: true)
            imeClear()
        }
    }

    func imeSelectCandidate(_ candidate: String) {
        setComposition(candidate, commit: true)
        imeClear()
        hapticLight()
    }

    /// Mirror the composition into the page's focused field (inline editing).
    private func setComposition(_ text: String, commit: Bool = false) {
        js("window.__gb && __gb.setComposition('\(jsEscape(text))', \(commit))")
    }

    private func jsEscape(_ text: String) -> String {
        text.replacingOccurrences(of: "\\", with: "\\\\")
            .replacingOccurrences(of: "'", with: "\\'")
            .replacingOccurrences(of: "\n", with: "\\n")
    }

    private func imeClear() {
        romajiBuffer = ""
        imeKana = ""
        imePending = ""
        imeCandidates = []
        candidateTask?.cancel()
    }

    private func recompose() {
        let (kana, pending) = RomajiConverter.convert(romajiBuffer)
        imeKana = kana
        imePending = pending
        setComposition(imeComposition)   // typed text appears in the field itself
        fetchCandidates(debounced: true)
    }

    private func fetchCandidates(debounced: Bool = false) {
        candidateTask?.cancel()
        let kana = imeKana
        guard !kana.isEmpty else {
            imeCandidates = []
            return
        }
        candidateTask = Task { [weak self] in
            if debounced { try? await Task.sleep(for: .milliseconds(250)) }
            guard !Task.isCancelled else { return }
            let results = await KanjiConverter.candidates(for: kana)
            guard !Task.isCancelled else { return }
            self?.imeCandidates = results
        }
    }

    /// Insert committed text into the page's focused editable element.
    func insertText(_ text: String) {
        guard !text.isEmpty else { return }
        js("window.__gb && __gb.insertText('\(jsEscape(text))')")
    }

    /// OS-style key auto-repeat: keydown with repeat=true while held.
    func repeatKey(_ key: InputBridge.Key) {
        sendKey(type: "keydown", key, repeating: true)
    }

    private func sendKey(type: String, _ key: InputBridge.Key, repeating: Bool = false) {
        let shift = pressedKeys.contains(InputBridge.shift)
        let ctrl = pressedKeys.contains(InputBridge.ctrl)
        let alt = pressedKeys.contains(InputBridge.alt)
        var keyValue = key.key
        if shift, keyValue.count == 1, keyValue.first!.isLetter {
            keyValue = keyValue.uppercased()
        }
        js("""
        window.__gb && __gb.key('\(type)', '\(jsEscape(keyValue))', '\(key.code)', \(key.keyCode), \
        {shift:\(shift), ctrl:\(ctrl), alt:\(alt), repeat:\(repeating)})
        """)
    }

    private func f(_ v: CGFloat) -> String { String(format: "%.1f", v) }
    private func f(_ v: Double) -> String { String(format: "%.1f", v) }

    // MARK: - Messages from JS

    fileprivate func handleScriptMessage(_ body: Any) {
        guard let dict = body as? [String: Any],
              let type = dict["type"] as? String else { return }
        if type == "pointerlock" {
            pointerLocked = (dict["locked"] as? Bool) ?? false
        } else if type == "cursorstyle" {
            let style = (dict["style"] as? String) ?? "auto"
            if cursorStyle != style { cursorStyle = style }
            let hidden = style == "none"
            if pageHidesCursor != hidden { pageHidesCursor = hidden }
        } else if type == "link" {
            if let href = dict["href"] as? String, let url = URL(string: href),
               url.scheme == "http" || url.scheme == "https" {
                linkTarget = LinkTarget(url: url, text: (dict["text"] as? String) ?? href)
                hapticMedium()
            }
        } else if type == "fps" {
            if let value = dict["value"] as? Int, showFPS { fps = value }
        } else if type == "notification" {
            postWebNotification(
                title: dict["title"] as? String ?? "",
                body: dict["body"] as? String ?? ""
            )
        } else if type == "notificationPermission" {
            requestNotificationPermission()
        } else if type == "autofillFocus" {
            handleAutofillFocus(kind: dict["kind"] as? String ?? "")
        } else if type == "credentialSubmitted" {
            handleCredentialSubmitted(
                username: dict["username"] as? String ?? "",
                password: dict["password"] as? String ?? ""
            )
        }
    }
}

// MARK: - WKNavigationDelegate / WKUIDelegate

extension BrowserViewModel: UNUserNotificationCenterDelegate {
    /// Show web notifications as banners even while the app is in the foreground.
    nonisolated func userNotificationCenter(_ center: UNUserNotificationCenter,
                                            willPresent notification: UNNotification,
                                            withCompletionHandler completionHandler:
                                            @escaping (UNNotificationPresentationOptions) -> Void) {
        completionHandler([.banner, .sound])
    }
}

extension BrowserViewModel: WKNavigationDelegate, WKUIDelegate {

    nonisolated func webView(_ webView: WKWebView,
                             createWebViewWith configuration: WKWebViewConfiguration,
                             for navigationAction: WKNavigationAction,
                             windowFeatures: WKWindowFeatures) -> WKWebView? {
        // window.open / target=_blank: create the new tab's web view from the
        // provided configuration and hand it back, so the page receives a real
        // window reference and WebKit performs the load itself.
        MainActor.assumeIsolated {
            guard !blockPopups else { return nil }
            return addPopupTab(configuration: configuration, requestedURL: navigationAction.request.url)
        }
    }

    /// New tab backed by a WebKit-provided popup configuration.
    private func addPopupTab(configuration: WKWebViewConfiguration, requestedURL: URL?) -> WKWebView {
        // The parent's user scripts and message handlers are inherited via the
        // configuration, so don't add them again.
        let webView = WKWebView(frame: .zero, configuration: configuration)
        webView.customUserAgent = desktopMode ? Self.desktopUserAgent : nil
        webView.allowsBackForwardNavigationGestures = false
        webView.scrollView.contentInsetAdjustmentBehavior = .never
        webView.isOpaque = false
        webView.backgroundColor = .black
        webView.navigationDelegate = self
        webView.uiDelegate = self
        attachAdBlock(to: webView)

        let tab = Tab(webView: webView, isPrivate: isPrivateTab)
        // WebKit performs the popup's load itself (not via our load(_:)), so
        // without this, saveTabs() — called synchronously right below, before
        // the popup's own .url KVO or navigation delegate callbacks fire —
        // would persist it as the start page instead of its real destination.
        tab.pendingURL = requestedURL
        tabs.append(tab)
        selectTab(tabs.count - 1)
        saveTabs()
        return webView
    }

    /// window.close() on a script-opened tab.
    nonisolated func webViewDidClose(_ webView: WKWebView) {
        MainActor.assumeIsolated {
            if let index = tabs.firstIndex(where: { $0.webView === webView }) {
                closeTab(index)
            }
        }
    }

    nonisolated func webView(_ webView: WKWebView,
                             decidePolicyFor navigationAction: WKNavigationAction,
                             preferences: WKWebpagePreferences,
                             decisionHandler: @escaping (WKNavigationActionPolicy, WKWebpagePreferences) -> Void) {
        Task { @MainActor [weak self] in
            guard let self else {
                decisionHandler(.allow, preferences)
                return
            }
            preferences.preferredContentMode = self.desktopMode ? .desktop : .mobile
            preferences.allowsContentJavaScript = self.javaScriptEnabled

            // A link with a `download` attribute: save it instead of trying
            // to navigate to it, which used to leave a blank tab behind.
            if navigationAction.shouldPerformDownload {
                decisionHandler(.download, preferences)
                return
            }

            // HTTPS-first: rewrite plain-http main-frame navigations.
            if self.httpsOnly,
               navigationAction.targetFrame?.isMainFrame == true,
               let url = navigationAction.request.url,
               url.scheme == "http",
               var components = URLComponents(url: url, resolvingAgainstBaseURL: false) {
                components.scheme = "https"
                if let httpsURL = components.url {
                    // Mutate the original request's URL rather than building a
                    // fresh URLRequest(url:), which would default to GET and
                    // silently drop the method/body/headers of e.g. a POSTed
                    // login or search form.
                    var request = navigationAction.request
                    request.url = httpsURL
                    decisionHandler(.cancel, preferences)
                    webView.load(request)
                    return
                }
            }
            decisionHandler(.allow, preferences)
        }
    }

    /// Camera / microphone access requested by a page (getUserMedia).
    nonisolated func webView(_ webView: WKWebView,
                             requestMediaCapturePermissionFor origin: WKSecurityOrigin,
                             initiatedByFrame frame: WKFrameInfo,
                             type: WKMediaCaptureType,
                             decisionHandler: @escaping (WKPermissionDecision) -> Void) {
        Task { @MainActor [weak self] in
            switch self?.capturePolicy ?? .ask {
            case .ask: decisionHandler(.prompt)
            case .allow: decisionHandler(.grant)
            case .deny: decisionHandler(.deny)
            }
        }
    }

    nonisolated func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        Task { @MainActor [weak self] in
            guard let self else { return }
            self.applyKeyboardSuppression()
            self.applyViewportMode(in: webView)
            self.applyFPSMeter()
            // Only the tab on screen: a background tab finishing its load
            // says nothing about what the user is looking at.
            if !self.tabs.isEmpty, webView === self.webView {
                // A fresh document has none of the focus styling we applied.
                self.gameFocused = false
                // "Open the game and play": a profile can ask for the game to
                // be blown up as soon as its page is ready.
                if self.activeProfile?.autoFocusGame == true { self.toggleGameFocus() }
            }

            // Restore the saved scroll position after a relaunch.
            if let tab = self.tabs.first(where: { $0.webView === webView }),
               let scroll = tab.pendingScroll {
                tab.pendingScroll = nil
                if scroll != .zero {
                    // Guard against the tab having navigated elsewhere during
                    // the wait — this restores a scroll offset captured for
                    // the page that had just finished loading, not whatever
                    // happens to be loaded 600ms later.
                    let expectedURL = webView.url
                    Task {
                        try? await Task.sleep(for: .milliseconds(600))
                        guard webView.url == expectedURL else { return }
                        webView.scrollView.setContentOffset(scroll, animated: false)
                    }
                }
            }

            guard let url = webView.url else { return }
            // A private tab leaves no trace in history.
            let fromPrivateTab = self.tabs.first { $0.webView === webView }?.isPrivate ?? false
            if !fromPrivateTab {
                self.recordHistory(url: url, title: webView.title ?? "")
            }
        }
    }

    /// Anything WebKit can't display — a zip, an installer, a save file — is
    /// a download rather than a dead end. This is what made "tap the download
    /// link and nothing happens" the app's behaviour until now.
    nonisolated func webView(_ webView: WKWebView,
                             decidePolicyFor navigationResponse: WKNavigationResponse,
                             decisionHandler: @escaping (WKNavigationResponsePolicy) -> Void) {
        decisionHandler(navigationResponse.canShowMIMEType ? .allow : .download)
    }

    nonisolated func webView(_ webView: WKWebView,
                             navigationAction: WKNavigationAction,
                             didBecome download: WKDownload) {
        MainActor.assumeIsolated {
            download.delegate = downloads
            downloads.begin(download, suggested: nil, source: navigationAction.request.url)
            toast(loc("ダウンロードを開始しました", "Download started"),
                  icon: "arrow.down.circle.fill")
        }
    }

    nonisolated func webView(_ webView: WKWebView,
                             navigationResponse: WKNavigationResponse,
                             didBecome download: WKDownload) {
        MainActor.assumeIsolated {
            download.delegate = downloads
            downloads.begin(download,
                            suggested: navigationResponse.response.suggestedFilename,
                            source: navigationResponse.response.url)
            toast(loc("ダウンロードを開始しました", "Download started"),
                  icon: "arrow.down.circle.fill")
        }
    }

    /// The request never got off the ground (DNS, offline, TLS, timeout).
    nonisolated func webView(_ webView: WKWebView,
                             didFailProvisionalNavigation navigation: WKNavigation!,
                             withError error: Error) {
        Task { @MainActor [weak self] in
            self?.showErrorPage(for: error, in: webView)
        }
    }

    /// The load started and then died part-way through.
    nonisolated func webView(_ webView: WKWebView,
                             didFail navigation: WKNavigation!,
                             withError error: Error) {
        Task { @MainActor [weak self] in
            self?.showErrorPage(for: error, in: webView)
        }
    }
}

/// Breaks the retain cycle WKUserContentController -> handler -> view model.
private final class ScriptMessageProxy: NSObject, WKScriptMessageHandler {
    weak var viewModel: BrowserViewModel?
    init(_ viewModel: BrowserViewModel) { self.viewModel = viewModel }

    func userContentController(_ userContentController: WKUserContentController,
                               didReceive message: WKScriptMessage) {
        Task { @MainActor [weak viewModel] in
            viewModel?.handleScriptMessage(message.body)
        }
    }
}
