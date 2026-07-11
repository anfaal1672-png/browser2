import SwiftUI
import WebKit
import Combine

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
            case .trackpad: return "マウス"
            case .touch: return "タッチ"
            }
        }
    }

    @Published var inputMode: InputMode = .trackpad {
        didSet { applyKeyboardSuppression() }
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
            case .classic: return "従来"
            case .quick: return "クイック"
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
    @Published var dragLocked: Bool = false
    /// True while the left mouse button is held; drives cursor press animation.
    @Published var mouseButtonDown: Bool = false
    /// Cursor dims after a few seconds without movement.
    @Published var cursorFaded: Bool = false
    private var cursorFadeTask: Task<Void, Never>?

    @Published var joystickVisible: Bool = false
    @Published var joystickUsesArrows: Bool {
        didSet { UserDefaults.standard.set(joystickUsesArrows, forKey: "joystickUsesArrows") }
    }

    @Published var hapticsEnabled: Bool {
        didSet { UserDefaults.standard.set(hapticsEnabled, forKey: "hapticsEnabled") }
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
    @Published var immersive: Bool = false

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

    /// Desktop (PC) or mobile presentation: user agent + content mode.
    @Published var desktopMode: Bool {
        didSet {
            UserDefaults.standard.set(desktopMode, forKey: "desktopMode")
            for tab in tabs {
                tab.webView.customUserAgent = desktopMode ? Self.desktopUserAgent : nil
            }
            webView.reload()
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
            return """
            <a class="tile" href="\(bookmark.url)">
              <div class="icon"><img src="https://www.google.com/s2/favicons?domain=\(host)&sz=64" \
            onerror="this.remove()" alt=""><span>\(initial)</span></div>
              <div class="name">\(bookmark.title)</div>
            </a>
            """
        }.joined()

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
        </style></head><body>
        <h1>GameBrowser</h1>
        <p class="sub">ブックマークから開く、または上のバーで検索</p>
        <div class="grid">\(tiles)</div>
        </body></html>
        """
    }

    func loadStartPage(in webView: WKWebView? = nil) {
        (webView ?? self.webView).loadHTMLString(startPageHTML(), baseURL: nil)
    }

    // MARK: - Init

    override init() {
        let defaults = UserDefaults.standard
        let savedSensitivity = defaults.double(forKey: "cursorSensitivity")
        cursorSensitivity = savedSensitivity > 0 ? savedSensitivity : 1.4
        showScrollButtons = defaults.object(forKey: "showScrollButtons") as? Bool ?? true
        joystickUsesArrows = defaults.bool(forKey: "joystickUsesArrows")
        hapticsEnabled = defaults.object(forKey: "hapticsEnabled") as? Bool ?? true
        controlScheme = ControlScheme(rawValue: defaults.integer(forKey: "controlScheme")) ?? .classic
        desktopMode = defaults.object(forKey: "desktopMode") as? Bool ?? true
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
        restoreTabs()

        // Persist tabs when the app is backgrounded or killed by the system.
        NotificationCenter.default.addObserver(
            forName: UIApplication.didEnterBackgroundNotification,
            object: nil, queue: .main
        ) { [weak self] _ in
            Task { @MainActor in self?.saveTabs() }
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
        let images = tabs.map(\.snapshot)
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

    private func saveTabs() {
        let urls = tabs.map { tab -> String in
            let url = tab.webView.url
            // loadHTMLString reports about:blank — persist the start-page marker.
            if url == nil || url?.scheme == "about" {
                return tab.pendingURL?.absoluteString ?? Self.startPageMarker
            }
            return url!.absoluteString
        }
        let defaults = UserDefaults.standard
        defaults.set(urls, forKey: "savedTabs")
        defaults.set(activeTabIndex, forKey: "savedActiveTabIndex")
        saveSnapshots()
    }

    private func restoreTabs() {
        let defaults = UserDefaults.standard
        let urls = (defaults.stringArray(forKey: "savedTabs") ?? [])
            .compactMap { URL(string: $0) }
        guard !urls.isEmpty else {
            newTab()
            return
        }
        for (i, url) in urls.enumerated() {
            let tab = Tab(webView: makeWebView())
            tab.pendingURL = url
            tab.snapshot = UIImage(contentsOfFile: snapshotFile(i).path)
            tabs.append(tab)
            if url.absoluteString == Self.startPageMarker {
                loadStartPage(in: tab.webView)
            } else {
                tab.webView.load(URLRequest(url: url))
            }
        }
        let saved = defaults.integer(forKey: "savedActiveTabIndex")
        selectTab(min(max(saved, 0), tabs.count - 1))
    }

    // MARK: - Tabs

    final class Tab: Identifiable, ObservableObject {
        let id = UUID()
        let webView: WKWebView
        @Published var snapshot: UIImage?
        /// Last requested URL; used for persistence while the page is still loading.
        var pendingURL: URL?
        init(webView: WKWebView) { self.webView = webView }

        @MainActor var title: String {
            let t = webView.title ?? ""
            return t.isEmpty ? (webView.url?.host ?? "新しいタブ") : t
        }
        @MainActor var urlString: String { webView.url?.absoluteString ?? "" }
    }

    @Published var tabs: [Tab] = []
    @Published var activeTabIndex: Int = 0

    var webView: WKWebView { tabs[activeTabIndex].webView }

    private func makeWebView() -> WKWebView {
        let config = WKWebViewConfiguration()
        config.allowsInlineMediaPlayback = true
        config.mediaTypesRequiringUserActionForPlayback = []
        config.preferences.javaScriptCanOpenWindowsAutomatically = true
        config.defaultWebpagePreferences.preferredContentMode = .desktop

        let userScript = WKUserScript(
            source: InputBridge.script,
            injectionTime: .atDocumentStart,
            forMainFrameOnly: false   // inject into iframes so games inside them get events
        )
        config.userContentController.addUserScript(userScript)
        config.userContentController.add(ScriptMessageProxy(self), name: "gbEvents")

        let webView = WKWebView(frame: .zero, configuration: config)
        webView.customUserAgent = desktopMode ? Self.desktopUserAgent : nil
        webView.allowsBackForwardNavigationGestures = false
        webView.scrollView.contentInsetAdjustmentBehavior = .never
        webView.isOpaque = false
        webView.backgroundColor = .black
        webView.navigationDelegate = self
        webView.uiDelegate = self
        return webView
    }

    func newTab(url: URL? = nil) {
        let tab = Tab(webView: makeWebView())
        tab.pendingURL = url ?? Self.homeURL
        tabs.append(tab)
        selectTab(tabs.count - 1)
        tab.webView.load(URLRequest(url: url ?? Self.homeURL))
        saveTabs()
    }

    func selectTab(_ index: Int) {
        guard tabs.indices.contains(index) else { return }
        snapshotActiveTab()
        releaseAllKeys()
        if dragLocked { mouseUp() }   // don't leave a mouse button held in the old tab
        activeTabIndex = index
        bindObservers(to: tabs[index].webView)
        pointerLocked = false
        pageHidesCursor = false
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
        let tab = tabs.remove(at: index)
        tab.webView.stopLoading()
        tab.webView.setAllMediaPlaybackSuspended(true)
        if tabs.isEmpty {
            newTab()
        } else {
            let shifted = activeTabIndex >= index ? activeTabIndex - 1 : activeTabIndex
            selectTab(min(max(shifted, 0), tabs.count - 1))
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
            let q = text.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? text
            url = URL(string: "https://www.google.com/search?q=\(q)")
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
        let escaped = query
            .replacingOccurrences(of: "\\", with: "\\\\")
            .replacingOccurrences(of: "'", with: "\\'")
        js("window.find('\(escaped)', false, \(forward ? "false" : "true"), true, false, true, false)")
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
        let dx = delta.width * cursorSensitivity * accel
        let dy = delta.height * cursorSensitivity * accel
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

    /// Smooth-scroll speed in px/s while a scroll button is held (user setting).
    @Published var scrollSpeed: Double = {
        let saved = UserDefaults.standard.double(forKey: "scrollSpeed")
        return saved > 0 ? saved : 700
    }() {
        didSet { UserDefaults.standard.set(scrollSpeed, forKey: "scrollSpeed") }
    }

    /// Scroll smoothly at constant speed in `direction` (+1 down / -1 up).
    func startSmoothScroll(direction: CGFloat) {
        scrollDirection = direction
        guard scrollTimer == nil else { return }
        scrollTimer = Timer.scheduledTimer(withTimeInterval: 1.0 / 60, repeats: true) { [weak self] _ in
            Task { @MainActor in
                guard let self else { return }
                self.scroll(dx: 0, dy: self.scrollDirection * self.scrollSpeed / 60)
            }
        }
    }

    func endSmoothScroll() {
        scrollTimer?.invalidate()
        scrollTimer = nil
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
        let escaped = text
            .replacingOccurrences(of: "\\", with: "\\\\")
            .replacingOccurrences(of: "'", with: "\\'")
            .replacingOccurrences(of: "\n", with: "\\n")
        js("window.__gb && __gb.insertText('\(escaped)')")
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
        let escaped = keyValue
            .replacingOccurrences(of: "\\", with: "\\\\")
            .replacingOccurrences(of: "'", with: "\\'")
        js("""
        window.__gb && __gb.key('\(type)', '\(escaped)', '\(key.code)', \(key.keyCode), \
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
            pageHidesCursor = (dict["hidden"] as? Bool) ?? false
        }
    }
}

// MARK: - WKNavigationDelegate / WKUIDelegate

extension BrowserViewModel: WKNavigationDelegate, WKUIDelegate {

    nonisolated func webView(_ webView: WKWebView,
                             createWebViewWith configuration: WKWebViewConfiguration,
                             for navigationAction: WKNavigationAction,
                             windowFeatures: WKWindowFeatures) -> WKWebView? {
        // Open popups in a new tab.
        if navigationAction.targetFrame == nil, let url = navigationAction.request.url {
            Task { @MainActor [weak self] in self?.newTab(url: url) }
        }
        return nil
    }

    nonisolated func webView(_ webView: WKWebView,
                             decidePolicyFor navigationAction: WKNavigationAction,
                             preferences: WKWebpagePreferences,
                             decisionHandler: @escaping (WKNavigationActionPolicy, WKWebpagePreferences) -> Void) {
        Task { @MainActor [weak self] in
            preferences.preferredContentMode = (self?.desktopMode ?? true) ? .desktop : .mobile
            decisionHandler(.allow, preferences)
        }
    }

    nonisolated func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        Task { @MainActor [weak self] in
            self?.applyKeyboardSuppression()
            guard let url = webView.url else { return }
            self?.recordHistory(url: url, title: webView.title ?? "")
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
