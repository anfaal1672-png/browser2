import SwiftUI
import LocalAuthentication

struct ContentView: View {
    @StateObject private var viewModel = BrowserViewModel()
    @FocusState private var urlFieldFocused: Bool
    @State private var showSettings = false
    @Environment(\.scenePhase) private var scenePhase
    @State private var isLocked = UserDefaults.standard.bool(forKey: "appLockEnabled")
    @State private var unlocking = false
    @State private var showBookmarks = false
    @State private var showTabs = false
    @State private var showHistory = false
    @State private var showFindBar = false
    @State private var findQuery = ""
    @FocusState private var findFieldFocused: Bool

    var body: some View {
        VStack(spacing: 0) {
            if !viewModel.immersive && !viewModel.toolbarOnBottom {
                toolbar
                progressBar
                if showFindBar { findBar }
            }

            GeometryReader { geo in
                ZStack {
                    WebViewContainer(viewModel: viewModel)

                    if viewModel.cursorMode {
                        TrackpadView(viewModel: viewModel)
                    }

                    // The user's own buttons sit above the trackpad, so they
                    // take their own touches first, and below the cursor, so
                    // the pointer is never hidden behind one.
                    if viewModel.pcMode && viewModel.padVisible {
                        ControlPadOverlay(viewModel: viewModel)
                    }

                    if viewModel.cursorMode
                        && !viewModel.pointerLocked && !viewModel.pageHidesCursor {
                        CursorView(
                            position: viewModel.cursorPosition,
                            pressed: viewModel.dragLocked || viewModel.mouseButtonDown,
                            style: viewModel.cursorStyle
                        )
                        .opacity(viewModel.cursorFaded ? 0.35 : 1)
                        .animation(.easeInOut(duration: 0.4), value: viewModel.cursorFaded)
                    }
                }
                .overlay(alignment: .trailing) {
                    if viewModel.cursorMode && viewModel.showScrollButtons { scrollStrip }
                }
                .overlay(alignment: .bottomLeading) {
                    if viewModel.joystickVisible {
                        JoystickView(viewModel: viewModel)
                            .padding(.leading, 14)
                            .padding(.bottom, 14)
                            .transition(.scale.combined(with: .opacity))
                    }
                }
                .onAppear { viewModel.webViewSize = geo.size }
                .onChange(of: geo.size) { _, newSize in viewModel.webViewSize = newSize }
                .overlay(alignment: .topTrailing) {
                    if viewModel.immersive { immersiveExitButton }
                }
                .overlay(alignment: .topLeading) {
                    HStack(spacing: 0) {
                        if viewModel.highlightsEnabled { highlightButton }
                        if viewModel.showFPS { fpsBadge }
                    }
                }
                .overlay(alignment: .top) {
                    if viewModel.padEditing {
                        PadEditBar(viewModel: viewModel)
                            .padding(.top, 8)
                            .transition(.move(edge: .top).combined(with: .opacity))
                    }
                }
            }
            .clipped()

            if viewModel.pendingCredential != nil {
                credentialSavePrompt
            }
            if !viewModel.autofillSuggestions.isEmpty || viewModel.cardSuggestionVisible {
                autofillBar
            }

            if viewModel.keyboardVisible {
                VirtualKeyboardView(viewModel: viewModel)
                    .transition(.move(edge: .bottom).combined(with: .opacity))
            }

            if viewModel.pcMode && !viewModel.immersive {
                controlBar
            }

            if !viewModel.immersive && viewModel.toolbarOnBottom {
                if showFindBar { findBar }
                progressBar
                toolbar
            }
        }
        .background(Color.black.ignoresSafeArea())
        .overlay(alignment: .bottom) {
            if let toast = viewModel.toastText {
                ToastView(text: toast, icon: viewModel.toastIcon)
                    .padding(.bottom, 92)
                    .transition(.move(edge: .bottom).combined(with: .opacity))
            }
        }
        .animation(.easeInOut(duration: 0.22), value: viewModel.toastText)
        .animation(.easeInOut(duration: 0.2), value: viewModel.keyboardVisible)
        .animation(.easeInOut(duration: 0.2), value: viewModel.fullKeyboard)
        .animation(.easeInOut(duration: 0.25), value: viewModel.immersive)
        .animation(.easeInOut(duration: 0.2), value: viewModel.pcMode)
        .overlay {
            if isLocked { lockScreen }
        }
        .onChange(of: scenePhase) { _, phase in
            switch phase {
            case .background:
                if viewModel.appLockEnabled {
                    isLocked = true
                    // A `.sheet` always renders above this view's `.overlay`,
                    // so the lock screen was invisible (and bypassable) behind
                    // whichever sheet happened to be open when backgrounding.
                    showSettings = false
                    showBookmarks = false
                    showTabs = false
                    showHistory = false
                }
            case .active:
                if isLocked { unlock() }
            default:
                break
            }
        }
        .onAppear { if isLocked { unlock() } }
        .sheet(isPresented: $showSettings) { SettingsView(viewModel: viewModel) }
        .sheet(isPresented: $showBookmarks) { bookmarksSheet }
        .sheet(isPresented: $showTabs) { tabsSheet }
        .sheet(isPresented: $showHistory) { historySheet }
        .sheet(isPresented: $viewModel.showProfiles) { ControlProfilesView(viewModel: viewModel) }
        .sheet(isPresented: $viewModel.showPadInspector) { PadButtonInspector(viewModel: viewModel) }
        .sheet(isPresented: $viewModel.showDownloads) { DownloadsView(downloads: viewModel.downloads) }
        // Right-clicking a link in cursor mode: WebKit's own menu can't reach
        // us there, so offer the same actions natively.
        .confirmationDialog(
            viewModel.linkTarget?.text ?? "",
            isPresented: Binding(get: { viewModel.linkTarget != nil },
                                 set: { if !$0 { viewModel.linkTarget = nil } }),
            titleVisibility: .visible
        ) {
            Button(loc("新しいタブで開く", "Open in new tab")) { viewModel.openLinkInNewTab() }
            Button(loc("リンクをコピー", "Copy link")) { viewModel.copyLink() }
            Button(loc("リンク先をダウンロード", "Download linked file")) { viewModel.downloadLink() }
            Button(loc("キャンセル", "Cancel"), role: .cancel) { viewModel.linkTarget = nil }
        }
    }

    // MARK: - App lock (Face ID)

    private var lockScreen: some View {
        ZStack {
            Rectangle().fill(.ultraThinMaterial).ignoresSafeArea()
            VStack(spacing: 16) {
                Image(systemName: "lock.fill")
                    .font(.system(size: 40))
                Text(loc("GameBrowserはロックされています", "GameBrowser is locked"))
                    .font(.system(size: 15, weight: .medium))
                Button(loc("ロック解除", "Unlock")) { unlock() }
                    .buttonStyle(.borderedProminent)
            }
            .foregroundStyle(.primary)
        }
    }

    private func unlock() {
        guard !unlocking else { return }
        unlocking = true
        let context = LAContext()
        var error: NSError?
        guard context.canEvaluatePolicy(.deviceOwnerAuthentication, error: &error) else {
            // No passcode set on the device — don't lock the user out.
            isLocked = false
            unlocking = false
            return
        }
        context.evaluatePolicy(.deviceOwnerAuthentication,
                               localizedReason: loc("GameBrowserのロックを解除", "Unlock GameBrowser")) { success, _ in
            Task { @MainActor in
                if success { isLocked = false }
                unlocking = false
            }
        }
    }

    /// Closes the find bar and clears its query/selection — also called when
    /// switching/closing/opening tabs so a stale search doesn't linger over
    /// a page it was never run against.
    private func dismissFindBar() {
        guard showFindBar else { return }
        showFindBar = false
        findQuery = ""
        viewModel.clearFindSelection()
    }

    /// Small floating controls shown in immersive mode.
    private var immersiveExitButton: some View {
        HStack(spacing: 8) {
            floatingButton(icon: viewModel.gameFocused
                           ? "rectangle.compress.vertical" : "gamecontroller.fill") {
                viewModel.toggleGameFocus()
            }
            floatingButton(icon: "circle.grid.cross.fill") {
                viewModel.padVisible.toggle()
            }
            floatingButton(icon: "keyboard") {
                viewModel.keyboardVisible.toggle()
            }
            floatingButton(icon: "arrow.down.right.and.arrow.up.left") {
                viewModel.immersive = false
            }
        }
        .padding(10)
    }

    /// One-tap "instant replay": saves the last ~15s of play to Photos.
    private var highlightButton: some View {
        Button { viewModel.saveHighlight() } label: {
            HStack(spacing: 6) {
                Image(systemName: highlightIcon)
                if viewModel.highlightSaveState == .saved {
                    Text(loc("保存済み", "Saved"))
                        .font(.system(size: 11, weight: .semibold))
                } else if viewModel.highlightSaveState == .failed {
                    Text(loc("失敗", "Failed"))
                        .font(.system(size: 11, weight: .semibold))
                }
            }
            .font(.system(size: 13, weight: .semibold))
            .foregroundStyle(highlightColor)
            .padding(.horizontal, viewModel.highlightSaveState == .idle ? 9 : 11)
            .frame(height: 30)
            .background(.black.opacity(0.45), in: Capsule())
            .overlay(Capsule().stroke(.white.opacity(0.2), lineWidth: 0.5))
        }
        .disabled(viewModel.highlightSaveState == .saving)
        .padding(10)
        .animation(.easeInOut(duration: 0.2), value: viewModel.highlightSaveState)
    }

    /// The page's own animation rate — a slow game and a slow connection look
    /// the same on screen otherwise.
    private var fpsBadge: some View {
        Text(viewModel.fps > 0 ? "\(viewModel.fps) FPS" : "— FPS")
            .font(.system(size: 11, weight: .bold, design: .monospaced))
            .foregroundStyle(fpsColor)
            .padding(.horizontal, 8)
            .frame(height: 30)
            .background(.black.opacity(0.45), in: Capsule())
            .overlay(Capsule().stroke(.white.opacity(0.2), lineWidth: 0.5))
            .padding(.vertical, 10)
            .padding(.leading, 10)
    }

    private var fpsColor: Color {
        switch viewModel.fps {
        case 0: return .white.opacity(0.6)
        case ..<25: return .red
        case ..<50: return .yellow
        default: return .green
        }
    }

    private var highlightIcon: String {
        switch viewModel.highlightSaveState {
        case .idle: return "video.badge.checkmark"
        case .saving: return "hourglass"
        case .saved: return "checkmark.circle.fill"
        case .failed: return "xmark.circle.fill"
        }
    }

    private var highlightColor: Color {
        switch viewModel.highlightSaveState {
        case .saved: return .green
        case .failed: return .red
        default: return .white.opacity(0.9)
        }
    }

    private func floatingButton(icon: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: icon)
                .font(.system(size: 13, weight: .semibold))
                .foregroundStyle(.white.opacity(0.9))
                .padding(9)
                .background(.black.opacity(0.45), in: Circle())
                .overlay(Circle().stroke(.white.opacity(0.2), lineWidth: 0.5))
        }
    }

    // MARK: - Scroll buttons

    /// Up / down scroll buttons on the right edge. Holding scrolls smoothly
    /// with acceleration; releasing decelerates to a stop.
    private var scrollStrip: some View {
        VStack(spacing: 8) {
            ScrollRepeatButton(
                icon: "chevron.up",
                onPress: {
                    viewModel.hapticSelection()
                    viewModel.startSmoothScroll(direction: -1)
                },
                onRelease: { viewModel.endSmoothScroll() }
            )
            ScrollRepeatButton(
                icon: "chevron.down",
                onPress: {
                    viewModel.hapticSelection()
                    viewModel.startSmoothScroll(direction: 1)
                },
                onRelease: { viewModel.endSmoothScroll() }
            )
        }
        .padding(.trailing, 4)
    }

    // MARK: - Top toolbar

    private var toolbar: some View {
        HStack(spacing: 10) {
            Button(action: viewModel.goBack) {
                Image(systemName: "chevron.left")
            }
            .disabled(!viewModel.canGoBack)

            Button(action: viewModel.goForward) {
                Image(systemName: "chevron.right")
            }
            .disabled(!viewModel.canGoForward)

            urlBar

            Button(action: viewModel.isLoading ? { viewModel.webView.stopLoading() } : viewModel.reload) {
                Image(systemName: viewModel.isLoading ? "xmark" : "arrow.clockwise")
            }

            Button(action: viewModel.toggleBookmark) {
                Image(systemName: viewModel.isCurrentPageBookmarked ? "star.fill" : "star")
                    .foregroundStyle(viewModel.isCurrentPageBookmarked ? Color.yellow : Color.white)
            }

            Button { showTabs = true } label: {
                ZStack {
                    Image(systemName: "square.on.square")
                    Text("\(viewModel.tabs.count)")
                        .font(.system(size: 9, weight: .bold))
                        .offset(y: 1)
                }
            }

            Menu {
                // Grouped because a ViewBuilder takes at most ten children.
                Group {
                    if let url = viewModel.currentURL {
                        ShareLink(item: url) {
                            Label(loc("共有", "Share"), systemImage: "square.and.arrow.up")
                        }
                        Button {
                            UIPasteboard.general.string = url.absoluteString
                        } label: {
                            Label(loc("リンクをコピー", "Copy link"), systemImage: "doc.on.doc")
                        }
                    }
                    Button {
                        showFindBar = true
                        findFieldFocused = true
                    } label: {
                        Label(loc("ページ内検索", "Find in page"), systemImage: "magnifyingglass")
                    }
                    Button { showHistory = true } label: {
                        Label(loc("履歴", "History"), systemImage: "clock")
                    }
                    Button { showBookmarks = true } label: {
                        Label(loc("ブックマーク", "Bookmarks"), systemImage: "book")
                    }
                    Button { viewModel.showDownloads = true } label: {
                        Label(viewModel.activeDownloads > 0
                              ? loc("ダウンロード (\(viewModel.activeDownloads)件)",
                                    "Downloads (\(viewModel.activeDownloads))")
                              : loc("ダウンロード", "Downloads"),
                              systemImage: "arrow.down.circle")
                    }
                    Button { viewModel.translatePage() } label: {
                        Label(loc("ページを翻訳", "Translate page"), systemImage: "character.bubble")
                    }
                    .disabled(viewModel.currentURL == nil)
                    Button { viewModel.showProfiles = true } label: {
                        Label(loc("コントロール設定", "Controls"), systemImage: "gamecontroller")
                    }
                    if viewModel.highlightsEnabled {
                        Button { viewModel.saveHighlight() } label: {
                            Label(loc("ハイライトを保存(直近15秒)", "Save highlight (last 15s)"), systemImage: "video.badge.checkmark")
                        }
                    }
                }
                Divider()
                Group {
                    Button { viewModel.toggleGameFocus() } label: {
                        Label(viewModel.gameFocused
                              ? loc("ゲーム全画面を解除", "Exit game fullscreen")
                              : loc("ゲームだけ全画面", "Fullscreen the game"),
                              systemImage: viewModel.gameFocused
                              ? "arrow.down.right.and.arrow.up.left" : "gamecontroller.fill")
                    }
                    Button {
                        viewModel.desktopMode.toggle()
                    } label: {
                        Label(viewModel.desktopMode ? loc("モバイル版サイトを表示", "Show mobile site") : loc("PC版サイトを表示", "Show desktop site"),
                              systemImage: viewModel.desktopMode ? "iphone" : "desktopcomputer")
                    }
                    Button {
                        viewModel.showScrollButtons.toggle()
                    } label: {
                        Label(viewModel.showScrollButtons ? loc("スクロールボタンを隠す", "Hide scroll buttons") : loc("スクロールボタンを表示", "Show scroll buttons"),
                              systemImage: "chevron.up.chevron.down")
                    }
                    Button { viewModel.resetZoom() } label: {
                        Label(loc("ズームをリセット", "Reset zoom"),
                              systemImage: "arrow.up.left.and.down.right.magnifyingglass")
                    }
                    Button { viewModel.goHome() } label: {
                        Label(loc("ホーム", "Home"), systemImage: "house")
                    }
                }
                Divider()
                Button { showSettings = true } label: {
                    Label(loc("設定", "Settings"), systemImage: "gearshape")
                }
            } label: {
                ZStack(alignment: .topTrailing) {
                    Image(systemName: "ellipsis.circle")
                    // A download running behind a closed menu is otherwise
                    // completely invisible.
                    if viewModel.activeDownloads > 0 {
                        Circle()
                            .fill(Color.cyan)
                            .frame(width: 7, height: 7)
                            .offset(x: 3, y: -2)
                    }
                }
            }
        }
        .font(.system(size: 16, weight: .medium))
        .tint(.white)
        .padding(.horizontal, 12)
        .padding(.vertical, 6)
        .background(.ultraThinMaterial)
    }

    /// Compact URL bar: shows just the domain when idle; tap to edit the
    /// full URL, like standard mobile browsers.
    private var urlBar: some View {
        HStack(spacing: 6) {
            Image(systemName: viewModel.currentURL?.scheme == "https" ? "lock.fill" : "globe")
                .font(.system(size: 11))
                .foregroundStyle(.secondary)

            // The text field stays in the hierarchy at all times — focusing a
            // field that isn't rendered yet silently fails. When idle it is
            // invisible and the domain label is shown on top.
            TextField(loc("URLまたは検索語を入力", "Enter URL or search"), text: $viewModel.urlText)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .keyboardType(.webSearch)
                .submitLabel(.go)
                .focused($urlFieldFocused)
                .onSubmit {
                    viewModel.submitURL()
                    urlFieldFocused = false
                }
                .font(.system(size: 14))
                .opacity(urlFieldFocused ? 1 : 0)
                .overlay {
                    if !urlFieldFocused {
                        Text(viewModel.currentURL?.host ?? (viewModel.urlText.isEmpty ? loc("検索またはURLを入力", "Search or enter URL") : viewModel.urlText))
                            .font(.system(size: 14))
                            .foregroundStyle(viewModel.currentURL == nil ? .secondary : .primary)
                            .lineLimit(1)
                            .frame(maxWidth: .infinity)
                            .contentShape(Rectangle())
                            .onTapGesture { urlFieldFocused = true }
                    }
                }

            if viewModel.isLoading {
                ProgressView().scaleEffect(0.6)
            }
        }
        .padding(.horizontal, 10)
        .frame(height: 34)
        .frame(maxWidth: .infinity)
        .background(Color.white.opacity(urlFieldFocused ? 0.16 : 0.10),
                    in: RoundedRectangle(cornerRadius: 10))
        .animation(.easeInOut(duration: 0.15), value: urlFieldFocused)
    }

    // MARK: - Autofill UI

    private var credentialSavePrompt: some View {
        HStack(spacing: 10) {
            Image(systemName: "key.fill")
                .font(.system(size: 13))
                .foregroundStyle(.cyan)
            Text(loc("パスワードを保存しますか?", "Save this password?"))
                .font(.system(size: 13, weight: .medium))
            Spacer()
            Button(loc("保存", "Save")) { viewModel.savePendingCredential() }
                .font(.system(size: 13, weight: .semibold))
            Button(loc("しない", "Never")) { viewModel.pendingCredential = nil }
                .font(.system(size: 13))
                .foregroundStyle(.secondary)
        }
        .tint(.white)
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
        .background(.ultraThinMaterial)
    }

    private var autofillBar: some View {
        HStack(spacing: 8) {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 6) {
                    ForEach(viewModel.autofillSuggestions) { credential in
                        Button {
                            viewModel.fill(credential)
                        } label: {
                            Label(credential.username.isEmpty ? credential.domain : credential.username,
                                  systemImage: "key.fill")
                                .font(.system(size: 13, weight: .medium))
                                .lineLimit(1)
                                .padding(.horizontal, 10)
                                .frame(height: 30)
                                .background(Color.cyan.opacity(0.22), in: Capsule())
                        }
                    }
                    if viewModel.cardSuggestionVisible {
                        Button {
                            viewModel.fillCard()
                        } label: {
                            Label(viewModel.paymentCard.maskedNumber, systemImage: "creditcard.fill")
                                .font(.system(size: 13, weight: .medium))
                                .padding(.horizontal, 10)
                                .frame(height: 30)
                                .background(Color.cyan.opacity(0.22), in: Capsule())
                        }
                    }
                }
            }
            Button {
                viewModel.dismissAutofill()
            } label: {
                Image(systemName: "xmark.circle.fill")
                    .foregroundStyle(.secondary)
            }
        }
        .tint(.white)
        .padding(.horizontal, 12)
        .padding(.vertical, 6)
        .background(.ultraThinMaterial)
    }

    // MARK: - Find in page

    private var findBar: some View {
        HStack(spacing: 10) {
            Image(systemName: "magnifyingglass")
                .font(.system(size: 12))
                .foregroundStyle(.secondary)
            TextField(loc("ページ内を検索", "Find in page"), text: $findQuery)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .submitLabel(.search)
                .focused($findFieldFocused)
                .onSubmit { viewModel.findInPage(findQuery) }
                .font(.system(size: 14))
            Button { viewModel.findInPage(findQuery, forward: false) } label: {
                Image(systemName: "chevron.up")
            }
            Button { viewModel.findInPage(findQuery) } label: {
                Image(systemName: "chevron.down")
            }
            Button(loc("完了", "Done")) { dismissFindBar() }
                .font(.system(size: 13, weight: .medium))
        }
        .font(.system(size: 14, weight: .medium))
        .tint(.white)
        .padding(.horizontal, 12)
        .padding(.vertical, 7)
        .background(.ultraThinMaterial)
    }

    // MARK: - History

    private var historySheet: some View {
        NavigationStack {
            List {
                ForEach(viewModel.history.reversed()) { entry in
                    Button {
                        if let url = URL(string: entry.url) {
                            viewModel.webView.load(URLRequest(url: url))
                        }
                        showHistory = false
                    } label: {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(entry.title)
                                .font(.system(size: 15, weight: .medium))
                                .foregroundStyle(.primary)
                                .lineLimit(1)
                            HStack {
                                Text(entry.url)
                                    .lineLimit(1)
                                Spacer()
                                Text(entry.date, style: .relative)
                            }
                            .font(.system(size: 12))
                            .foregroundStyle(.secondary)
                        }
                    }
                }
                if viewModel.history.isEmpty {
                    Text(loc("履歴はまだありません", "No history yet"))
                        .foregroundStyle(.secondary)
                        .font(.system(size: 14))
                }
            }
            .navigationTitle(loc("履歴", "History"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(loc("消去", "Clear"), role: .destructive) { viewModel.clearHistory() }
                        .disabled(viewModel.history.isEmpty)
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button(loc("完了", "Done")) { showHistory = false }
                }
            }
        }
        .presentationDetents([.medium, .large])
    }

    private var progressBar: some View {
        GeometryReader { geo in
            Capsule()
                .fill(LinearGradient(colors: [.cyan, .blue],
                                     startPoint: .leading, endPoint: .trailing))
                .frame(width: geo.size.width * viewModel.progress)
                .opacity(viewModel.isLoading ? 1 : 0)
                .animation(.linear(duration: 0.15), value: viewModel.progress)
                .animation(.easeOut(duration: 0.3), value: viewModel.isLoading)
        }
        .frame(height: 2.5)
    }

    // MARK: - Bottom control bar

    private var controlBar: some View {
        HStack(spacing: 0) {
            controlButton(
                icon: viewModel.inputMode.icon,
                label: viewModel.inputMode.label,
                active: viewModel.cursorMode
            ) {
                let all = BrowserViewModel.InputMode.allCases
                let next = (viewModel.inputMode.rawValue + 1) % all.count
                viewModel.inputMode = all[next]
            }

            controlButton(
                icon: "cursorarrow.click.2",
                label: loc("左クリック", "L-click"),
                active: false
            ) {
                viewModel.click()
            }
            .disabled(!viewModel.cursorMode)

            controlButton(
                icon: "cursorarrow.rays",
                label: loc("右クリック", "R-click"),
                active: false
            ) {
                viewModel.click(button: 2)
            }
            .disabled(!viewModel.cursorMode)

            controlButton(
                icon: "hand.point.up.left.fill",
                label: loc("ドラッグ", "Drag"),
                active: viewModel.dragLocked
            ) {
                viewModel.toggleDragLock()
            }
            .disabled(!viewModel.cursorMode)

            controlButton(
                icon: "dpad",
                label: loc("スティック", "Stick"),
                active: viewModel.joystickVisible
            ) {
                withAnimation(.easeInOut(duration: 0.2)) {
                    viewModel.joystickVisible.toggle()
                }
            }

            // Tap: show/hide the custom pad. Long press: open the editor.
            controlButton(
                icon: "circle.grid.cross.fill",
                label: loc("パッド", "Pads"),
                active: viewModel.padVisible
            ) {
                viewModel.padVisible.toggle()
                if viewModel.padVisible && viewModel.activeProfile == nil {
                    viewModel.showProfiles = true
                }
            }
            .simultaneousGesture(
                LongPressGesture(minimumDuration: 0.45).onEnded { _ in
                    viewModel.hapticMedium()
                    viewModel.showProfiles = true
                }
            )

            controlButton(
                icon: "keyboard",
                label: loc("キーボード", "Keyboard"),
                active: viewModel.keyboardVisible
            ) {
                viewModel.keyboardVisible.toggle()
            }

            controlButton(
                icon: "keyboard.fill",
                label: loc("フルキー", "Full keys"),
                active: viewModel.fullKeyboard
            ) {
                viewModel.fullKeyboard.toggle()
                if viewModel.fullKeyboard { viewModel.keyboardVisible = true }
            }

            controlButton(
                icon: "arrow.up.left.and.arrow.down.right",
                label: loc("全画面", "Fullscreen"),
                active: false
            ) {
                viewModel.immersive = true
            }
        }
        .padding(.vertical, 4)
        .padding(.bottom, 2)
        .background(.ultraThinMaterial)
    }

    private func controlButton(icon: String, label: String, active: Bool,
                               action: @escaping () -> Void) -> some View {
        Button {
            viewModel.hapticLight()
            action()
        } label: {
            VStack(spacing: 3) {
                Image(systemName: icon)
                    .font(.system(size: 17, weight: .medium))
                    .frame(height: 20)
                Text(label)
                    .font(.system(size: 9, weight: .medium))
            }
            .foregroundStyle(active ? Color.cyan : Color.white.opacity(0.85))
            .frame(maxWidth: .infinity)
            .padding(.vertical, 5)
            .background(
                RoundedRectangle(cornerRadius: 10)
                    .fill(Color.cyan.opacity(active ? 0.14 : 0))
                    .padding(.horizontal, 3)
            )
        }
        .animation(.easeInOut(duration: 0.15), value: active)
    }

    // MARK: - Tabs

    private var tabsSheet: some View {
        NavigationStack {
            ScrollView {
                LazyVGrid(columns: [GridItem(.adaptive(minimum: 150), spacing: 12)], spacing: 12) {
                    ForEach(Array(viewModel.tabs.enumerated()), id: \.element.id) { index, tab in
                        TabCard(
                            tab: tab,
                            isActive: index == viewModel.activeTabIndex,
                            select: {
                                viewModel.selectTab(index)
                                showTabs = false
                                dismissFindBar()
                            },
                            close: {
                                withAnimation(.easeInOut(duration: 0.15)) {
                                    viewModel.closeTab(index)
                                }
                                dismissFindBar()
                            }
                        )
                    }

                    // New tab card
                    Button {
                        viewModel.newTab()
                        showTabs = false
                        dismissFindBar()
                    } label: {
                        VStack(spacing: 8) {
                            Image(systemName: "plus")
                                .font(.system(size: 26, weight: .medium))
                            Text(loc("新しいタブ", "New tab"))
                                .font(.system(size: 12, weight: .medium))
                        }
                        .foregroundStyle(.secondary)
                        .frame(maxWidth: .infinity)
                        .frame(height: 130)
                        .background(Color.primary.opacity(0.06), in: RoundedRectangle(cornerRadius: 14))
                        .overlay(
                            RoundedRectangle(cornerRadius: 14)
                                .strokeBorder(style: StrokeStyle(lineWidth: 1.5, dash: [5]))
                                .foregroundStyle(.secondary.opacity(0.4))
                        )
                    }
                }
                .padding(14)
            }
            .navigationTitle(loc("タブ", "Tabs") + " (\(viewModel.tabs.count))")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button(loc("完了", "Done")) { showTabs = false }
                }
            }
            .onAppear { viewModel.snapshotActiveTab() }
        }
        .presentationDetents([.medium, .large])
    }

    // MARK: - Bookmarks


    private var bookmarksSheet: some View {
        NavigationStack {
            List {
                if viewModel.bookmarks.isEmpty {
                    Text(loc("ブックマークはまだありません。\nツールバーの ★ で追加できます。", "No bookmarks yet.\nTap ★ in the toolbar to add one."))
                        .foregroundStyle(.secondary)
                        .font(.system(size: 14))
                }
                ForEach(viewModel.bookmarks) { bookmark in
                    Button {
                        viewModel.open(bookmark)
                        showBookmarks = false
                    } label: {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(bookmark.title)
                                .font(.system(size: 15, weight: .medium))
                                .foregroundStyle(.primary)
                                .lineLimit(1)
                            Text(bookmark.url)
                                .font(.system(size: 12))
                                .foregroundStyle(.secondary)
                                .lineLimit(1)
                        }
                    }
                }
                .onDelete { viewModel.bookmarks.remove(atOffsets: $0) }
                .onMove { viewModel.bookmarks.move(fromOffsets: $0, toOffset: $1) }
            }
            .navigationTitle(loc("ブックマーク", "Bookmarks"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { EditButton() }
                ToolbarItem(placement: .confirmationAction) {
                    Button(loc("完了", "Done")) { showBookmarks = false }
                }
            }
        }
        .presentationDetents([.medium, .large])
    }

}

/// Thumbnail card in the tab switcher grid.
struct TabCard: View {
    @ObservedObject var tab: BrowserViewModel.Tab
    let isActive: Bool
    let select: () -> Void
    let close: () -> Void

    var body: some View {
        Button(action: select) {
            VStack(spacing: 0) {
                ZStack {
                    if let snapshot = tab.snapshot {
                        Image(uiImage: snapshot)
                            .resizable()
                            .aspectRatio(contentMode: .fill)
                            .allowsHitTesting(false)
                    } else {
                        Rectangle().fill(Color.primary.opacity(0.06))
                        Image(systemName: "globe")
                            .font(.system(size: 24))
                            .foregroundStyle(.secondary)
                    }
                }
                .frame(height: 96)
                .clipped()

                HStack(spacing: 6) {
                    Text(tab.title)
                        .font(.system(size: 12, weight: .medium))
                        .foregroundStyle(.primary)
                        .lineLimit(1)
                    Spacer(minLength: 0)
                }
                .padding(.horizontal, 10)
                .frame(height: 34)
                .background(.thinMaterial)
            }
            .clipShape(RoundedRectangle(cornerRadius: 14))
            .overlay(
                RoundedRectangle(cornerRadius: 14)
                    .strokeBorder(isActive ? Color.cyan : Color.primary.opacity(0.12),
                                  lineWidth: isActive ? 2 : 1)
            )
            // The .fill snapshot overflows its frame; without an explicit
            // content shape the overflow steals taps from neighboring cards.
            .contentShape(RoundedRectangle(cornerRadius: 14))
        }
        .buttonStyle(.plain)
        .contextMenu {
            Button {
                UIPasteboard.general.string = tab.urlString
            } label: {
                Label(loc("リンクをコピー", "Copy link"), systemImage: "doc.on.doc")
            }
            Button(role: .destructive, action: close) {
                Label(loc("タブを閉じる", "Close tab"), systemImage: "xmark")
            }
        }
        .overlay(alignment: .topTrailing) {
            Button(action: close) {
                Image(systemName: "xmark")
                    .font(.system(size: 10, weight: .bold))
                    .foregroundStyle(.white)
                    .padding(6)
                    .background(.black.opacity(0.55), in: Circle())
            }
            .padding(6)
        }
    }
}

/// Round button that reports press and release, for hold-to-scroll controls.
struct ScrollRepeatButton: View {
    let icon: String
    let onPress: () -> Void
    let onRelease: () -> Void

    @State private var pressed = false

    var body: some View {
        Image(systemName: icon)
            .font(.system(size: 15, weight: .bold))
            .foregroundStyle(.white.opacity(pressed ? 1 : 0.75))
            .frame(width: 40, height: 40)
            .background(.black.opacity(pressed ? 0.65 : 0.35), in: Circle())
            .overlay(Circle().stroke(.white.opacity(0.25), lineWidth: 0.5))
            .contentShape(Circle())
            .gesture(
                DragGesture(minimumDistance: 0)
                    .onChanged { _ in
                        guard !pressed else { return }
                        pressed = true
                        onPress()
                    }
                    .onEnded { _ in
                        pressed = false
                        onRelease()
                    }
            )
    }
}
