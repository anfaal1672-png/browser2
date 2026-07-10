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
        case direct     // absolute: touches dispatch mouse events at the touch point
        case touch      // native WKWebView touch handling

        var icon: String {
            switch self {
            case .trackpad: return "cursorarrow"
            case .direct: return "hand.point.up.left.and.text"
            case .touch: return "hand.tap"
            }
        }
        var label: String {
            switch self {
            case .trackpad: return "マウス"
            case .direct: return "ダイレクト"
            case .touch: return "タッチ"
            }
        }
    }

    @Published var inputMode: InputMode = .trackpad

    var cursorMode: Bool { inputMode != .touch }
    @Published var keyboardVisible: Bool = false
    @Published var fullKeyboard: Bool = false
    @Published var pointerLocked: Bool = false
    @Published var dragLocked: Bool = false
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

    var webViewSize: CGSize = .zero

    private var observers: [NSKeyValueObservation] = []
    private var lastClickTime: Date = .distantPast
    private var lastClickPoint: CGPoint = .zero

    static let desktopUserAgent =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 " +
        "(KHTML, like Gecko) Version/17.4 Safari/605.1.15"

    static let homeURL = URL(string: "https://www.google.com")!

    // MARK: - Init

    override init() {
        let defaults = UserDefaults.standard
        let savedSensitivity = defaults.double(forKey: "cursorSensitivity")
        cursorSensitivity = savedSensitivity > 0 ? savedSensitivity : 1.4
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

        newTab()
    }

    // MARK: - Tabs

    final class Tab: Identifiable, ObservableObject {
        let id = UUID()
        let webView: WKWebView
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
        webView.customUserAgent = Self.desktopUserAgent
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
        tabs.append(tab)
        selectTab(tabs.count - 1)
        tab.webView.load(URLRequest(url: url ?? Self.homeURL))
    }

    func selectTab(_ index: Int) {
        guard tabs.indices.contains(index) else { return }
        activeTabIndex = index
        bindObservers(to: tabs[index].webView)
        pointerLocked = false
        dragLocked = false
    }

    func closeTab(_ index: Int) {
        guard tabs.indices.contains(index) else { return }
        let tab = tabs.remove(at: index)
        tab.webView.stopLoading()
        if tabs.isEmpty {
            newTab()
        } else {
            let shifted = activeTabIndex >= index ? activeTabIndex - 1 : activeTabIndex
            selectTab(min(max(shifted, 0), tabs.count - 1))
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
                    if let url = wv.url { self?.urlText = url.absoluteString }
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
        let dx = delta.width * cursorSensitivity
        let dy = delta.height * cursorSensitivity
        cursorPosition = clamp(CGPoint(x: cursorPosition.x + dx, y: cursorPosition.y + dy))
        js("window.__gb && __gb.move(\(f(cursorPosition.x)), \(f(cursorPosition.y)), \(f(dx)), \(f(dy)))")
    }

    /// Absolute positioning used by direct-touch mode.
    func setCursor(to point: CGPoint) {
        let old = cursorPosition
        cursorPosition = clamp(point)
        let dx = cursorPosition.x - old.x
        let dy = cursorPosition.y - old.y
        js("window.__gb && __gb.move(\(f(cursorPosition.x)), \(f(cursorPosition.y)), \(f(dx)), \(f(dy)))")
    }

    func mouseDown(button: Int = 0) {
        js("window.__gb && __gb.down(\(f(cursorPosition.x)), \(f(cursorPosition.y)), \(button))")
    }

    func mouseUp(button: Int = 0) {
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

    func tapKey(_ key: InputBridge.Key) {
        keyDown(key)
        keyUp(key)
    }

    private func sendKey(type: String, _ key: InputBridge.Key) {
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
        {shift:\(shift), ctrl:\(ctrl), alt:\(alt)})
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
                             decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
        decisionHandler(.allow)
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
