import SwiftUI

struct ContentView: View {
    @StateObject private var viewModel = BrowserViewModel()
    @FocusState private var urlFieldFocused: Bool
    @State private var showSettings = false
    @State private var showBookmarks = false
    @State private var showTabs = false
    @State private var showHistory = false
    @State private var showFindBar = false
    @State private var findQuery = ""
    @FocusState private var findFieldFocused: Bool

    var body: some View {
        VStack(spacing: 0) {
            if !viewModel.immersive {
                toolbar
                progressBar
                if showFindBar { findBar }
            }

            GeometryReader { geo in
                ZStack {
                    WebViewContainer(viewModel: viewModel)

                    if viewModel.cursorMode {
                        TrackpadView(viewModel: viewModel)
                        if !viewModel.pointerLocked && !viewModel.pageHidesCursor {
                            CursorView(
                                position: viewModel.cursorPosition,
                                pressed: viewModel.dragLocked || viewModel.mouseButtonDown
                            )
                            .opacity(viewModel.cursorFaded ? 0.35 : 1)
                            .animation(.easeInOut(duration: 0.4), value: viewModel.cursorFaded)
                        }
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
            }
            .clipped()

            if viewModel.showTextInput {
                textInputBar
            }

            if viewModel.keyboardVisible {
                VirtualKeyboardView(viewModel: viewModel)
                    .transition(.move(edge: .bottom).combined(with: .opacity))
            }

            if !viewModel.immersive {
                controlBar
            }
        }
        .background(Color.black.ignoresSafeArea())
        .animation(.easeInOut(duration: 0.2), value: viewModel.keyboardVisible)
        .animation(.easeInOut(duration: 0.2), value: viewModel.fullKeyboard)
        .animation(.easeInOut(duration: 0.25), value: viewModel.immersive)
        .sheet(isPresented: $showSettings) { settingsSheet }
        .sheet(isPresented: $showBookmarks) { bookmarksSheet }
        .sheet(isPresented: $showTabs) { tabsSheet }
        .sheet(isPresented: $showHistory) { historySheet }
    }

    /// Small floating controls shown in immersive mode.
    private var immersiveExitButton: some View {
        HStack(spacing: 8) {
            floatingButton(icon: "keyboard") {
                viewModel.keyboardVisible.toggle()
            }
            floatingButton(icon: "arrow.down.right.and.arrow.up.left") {
                viewModel.immersive = false
            }
        }
        .padding(10)
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

            Button { showBookmarks = true } label: {
                Image(systemName: "book")
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
                if let url = viewModel.currentURL {
                    ShareLink(item: url) {
                        Label("共有", systemImage: "square.and.arrow.up")
                    }
                    Button {
                        UIPasteboard.general.string = url.absoluteString
                    } label: {
                        Label("リンクをコピー", systemImage: "doc.on.doc")
                    }
                }
                Button {
                    showFindBar = true
                    findFieldFocused = true
                } label: {
                    Label("ページ内検索", systemImage: "magnifyingglass")
                }
                Button { showHistory = true } label: {
                    Label("履歴", systemImage: "clock")
                }
                Divider()
                Button {
                    viewModel.desktopMode.toggle()
                } label: {
                    Label(viewModel.desktopMode ? "モバイル版サイトを表示" : "PC版サイトを表示",
                          systemImage: viewModel.desktopMode ? "iphone" : "desktopcomputer")
                }
                Button {
                    viewModel.showScrollButtons.toggle()
                } label: {
                    Label(viewModel.showScrollButtons ? "スクロールボタンを隠す" : "スクロールボタンを表示",
                          systemImage: "chevron.up.chevron.down")
                }
                Button { viewModel.goHome() } label: {
                    Label("ホーム", systemImage: "house")
                }
                Divider()
                Button { showSettings = true } label: {
                    Label("設定", systemImage: "gearshape")
                }
            } label: {
                Image(systemName: "ellipsis.circle")
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
            TextField("URLまたは検索語を入力", text: $viewModel.urlText)
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
                        Text(viewModel.currentURL?.host ?? (viewModel.urlText.isEmpty ? "検索またはURLを入力" : viewModel.urlText))
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

    // MARK: - Native-IME text input (Japanese etc.)

    @State private var imeText = ""
    @FocusState private var imeFieldFocused: Bool

    /// Input bar backed by the native iOS keyboard, so Japanese romaji→kanji
    /// conversion works. Committed text is inserted into the page's focused field.
    private var textInputBar: some View {
        HStack(spacing: 10) {
            Image(systemName: "character.textbox.ja")
                .font(.system(size: 13))
                .foregroundStyle(.secondary)
            TextField("日本語入力(確定で送信)", text: $imeText)
                .focused($imeFieldFocused)
                .submitLabel(.send)
                .onSubmit {
                    viewModel.insertText(imeText)
                    imeText = ""
                    imeFieldFocused = true   // keep typing
                }
                .font(.system(size: 14))
            Button("送信") {
                viewModel.insertText(imeText)
                imeText = ""
            }
            .disabled(imeText.isEmpty)
            Button("閉じる") {
                imeText = ""
                viewModel.showTextInput = false
            }
        }
        .font(.system(size: 13, weight: .medium))
        .tint(.white)
        .padding(.horizontal, 12)
        .padding(.vertical, 7)
        .background(.ultraThinMaterial)
        .onAppear { imeFieldFocused = true }
    }

    // MARK: - Find in page

    private var findBar: some View {
        HStack(spacing: 10) {
            Image(systemName: "magnifyingglass")
                .font(.system(size: 12))
                .foregroundStyle(.secondary)
            TextField("ページ内を検索", text: $findQuery)
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
            Button("完了") {
                showFindBar = false
                findQuery = ""
                viewModel.clearFindSelection()
            }
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
                    Text("履歴はまだありません")
                        .foregroundStyle(.secondary)
                        .font(.system(size: 14))
                }
            }
            .navigationTitle("履歴")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("消去", role: .destructive) { viewModel.clearHistory() }
                        .disabled(viewModel.history.isEmpty)
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("完了") { showHistory = false }
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
                label: "左クリック",
                active: false
            ) {
                viewModel.click()
            }
            .disabled(!viewModel.cursorMode)

            controlButton(
                icon: "cursorarrow.rays",
                label: "右クリック",
                active: false
            ) {
                viewModel.click(button: 2)
            }
            .disabled(!viewModel.cursorMode)

            controlButton(
                icon: "hand.point.up.left.fill",
                label: "ドラッグ",
                active: viewModel.dragLocked
            ) {
                viewModel.toggleDragLock()
            }
            .disabled(!viewModel.cursorMode)

            controlButton(
                icon: "dpad",
                label: "スティック",
                active: viewModel.joystickVisible
            ) {
                withAnimation(.easeInOut(duration: 0.2)) {
                    viewModel.joystickVisible.toggle()
                }
            }

            controlButton(
                icon: "keyboard",
                label: "キーボード",
                active: viewModel.keyboardVisible
            ) {
                viewModel.keyboardVisible.toggle()
            }

            controlButton(
                icon: "keyboard.fill",
                label: "フルキー",
                active: viewModel.fullKeyboard
            ) {
                viewModel.fullKeyboard.toggle()
                if viewModel.fullKeyboard { viewModel.keyboardVisible = true }
            }

            controlButton(
                icon: "arrow.up.left.and.arrow.down.right",
                label: "全画面",
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
                            },
                            close: {
                                withAnimation(.easeInOut(duration: 0.15)) {
                                    viewModel.closeTab(index)
                                }
                            }
                        )
                    }

                    // New tab card
                    Button {
                        viewModel.newTab()
                        showTabs = false
                    } label: {
                        VStack(spacing: 8) {
                            Image(systemName: "plus")
                                .font(.system(size: 26, weight: .medium))
                            Text("新しいタブ")
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
            .navigationTitle("タブ (\(viewModel.tabs.count))")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("完了") { showTabs = false }
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
                    Text("ブックマークはまだありません。\nツールバーの ★ で追加できます。")
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
            .navigationTitle("ブックマーク")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { EditButton() }
                ToolbarItem(placement: .confirmationAction) {
                    Button("完了") { showBookmarks = false }
                }
            }
        }
        .presentationDetents([.medium, .large])
    }

    // MARK: - Settings

    private var settingsSheet: some View {
        NavigationStack {
            Form {
                Section("操作方法") {
                    Picker("スキーム", selection: $viewModel.controlScheme) {
                        ForEach(BrowserViewModel.ControlScheme.allCases, id: \.self) {
                            Text($0.label).tag($0)
                        }
                    }
                    .pickerStyle(.segmented)
                    if viewModel.controlScheme == .quick {
                        Label("タップ後すぐ押し込み: ドラッグ", systemImage: "hand.tap")
                        Label("フリック: カーソルが滑る(慣性)", systemImage: "wind")
                        Label("長押し: 右クリック", systemImage: "cursorarrow.rays")
                    } else {
                        Label("長押し→ドラッグ: ドラッグ&ドロップ", systemImage: "hand.point.up.left")
                        Label("2本指タップ: 右クリック", systemImage: "cursorarrow.rays")
                    }
                }
                .font(.system(size: 13))

                Section("カーソル") {
                    VStack(alignment: .leading) {
                        Text("感度: \(String(format: "%.1f", viewModel.cursorSensitivity))x")
                        Slider(value: $viewModel.cursorSensitivity, in: 0.5...3.0, step: 0.1)
                    }
                    VStack(alignment: .leading) {
                        Text("スクロール速度: \(Int(viewModel.scrollSpeed)) px/秒")
                        Slider(value: $viewModel.scrollSpeed, in: 300...1500, step: 50)
                    }
                }
                Section("ジョイスティック") {
                    Toggle("矢印キーを送信(オフ: WASD)", isOn: $viewModel.joystickUsesArrows)
                }
                Section("フィードバック") {
                    Toggle("触覚フィードバック(振動)", isOn: $viewModel.hapticsEnabled)
                }
                Section("表示") {
                    Toggle("PC版サイトを表示", isOn: $viewModel.desktopMode)
                    Toggle("スクロールボタンを表示", isOn: $viewModel.showScrollButtons)
                }
                Section("ナビゲーション") {
                    Button("ホームに戻る") {
                        viewModel.goHome()
                        showSettings = false
                    }
                }
                Section("プライバシー") {
                    Button("閲覧データを消去(Cookie・キャッシュ)", role: .destructive) {
                        viewModel.clearBrowsingData()
                    }
                }
                Section("操作方法") {
                    Label("1本指ドラッグ: カーソル移動", systemImage: "cursorarrow.motionlines")
                    Label("タップ: 左クリック / 2回: ダブルクリック", systemImage: "cursorarrow.click")
                    Label("長押し→ドラッグ: ドラッグ&ドロップ", systemImage: "hand.point.up.left")
                    Label("2本指ドラッグ: スクロール", systemImage: "arrow.up.and.down")
                    Label("2本指タップ: 右クリック", systemImage: "cursorarrow.rays")
                    Label("⇧/CTRLキーはタップで固定", systemImage: "keyboard")
                }
                .font(.system(size: 14))
            }
            .navigationTitle("設定")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("完了") { showSettings = false }
                }
            }
        }
        .presentationDetents([.medium])
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
                Label("リンクをコピー", systemImage: "doc.on.doc")
            }
            Button(role: .destructive, action: close) {
                Label("タブを閉じる", systemImage: "xmark")
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
