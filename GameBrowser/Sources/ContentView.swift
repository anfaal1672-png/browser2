import SwiftUI

struct ContentView: View {
    @StateObject private var viewModel = BrowserViewModel()
    @FocusState private var urlFieldFocused: Bool
    @State private var showSettings = false
    @State private var showBookmarks = false

    var body: some View {
        VStack(spacing: 0) {
            if !viewModel.immersive {
                toolbar
                progressBar
            }

            GeometryReader { geo in
                ZStack {
                    WebViewContainer(viewModel: viewModel)

                    if viewModel.cursorMode {
                        TrackpadView(viewModel: viewModel)
                        if !viewModel.pointerLocked {
                            CursorView(
                                position: viewModel.cursorPosition,
                                pressed: viewModel.dragLocked
                            )
                        }
                    }
                }
                .onAppear { viewModel.webViewSize = geo.size }
                .onChange(of: geo.size) { _, newSize in viewModel.webViewSize = newSize }
                .overlay(alignment: .topTrailing) {
                    if viewModel.immersive { immersiveExitButton }
                }
            }
            .clipped()

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

            HStack(spacing: 6) {
                Image(systemName: viewModel.currentURL?.scheme == "https" ? "lock.fill" : "globe")
                    .font(.system(size: 11))
                    .foregroundStyle(.secondary)

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

                if viewModel.isLoading {
                    ProgressView().scaleEffect(0.6)
                }
            }
            .padding(.horizontal, 10)
            .frame(height: 34)
            .background(Color.white.opacity(0.12), in: RoundedRectangle(cornerRadius: 10))

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

            Button { showSettings = true } label: {
                Image(systemName: "gearshape")
            }
        }
        .font(.system(size: 16, weight: .medium))
        .tint(.white)
        .padding(.horizontal, 12)
        .padding(.vertical, 6)
        .background(.ultraThinMaterial)
    }

    private var progressBar: some View {
        GeometryReader { geo in
            Rectangle()
                .fill(Color.cyan)
                .frame(width: geo.size.width * viewModel.progress)
                .opacity(viewModel.isLoading ? 1 : 0)
                .animation(.linear(duration: 0.15), value: viewModel.progress)
        }
        .frame(height: 2)
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
                UIImpactFeedbackGenerator(style: .light).impactOccurred()
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
        Button(action: action) {
            VStack(spacing: 3) {
                Image(systemName: icon)
                    .font(.system(size: 17, weight: .medium))
                Text(label)
                    .font(.system(size: 9, weight: .medium))
            }
            .foregroundStyle(active ? Color.cyan : Color.white.opacity(0.85))
            .frame(maxWidth: .infinity)
            .padding(.vertical, 5)
        }
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
                Section("カーソル") {
                    VStack(alignment: .leading) {
                        Text("感度: \(String(format: "%.1f", viewModel.cursorSensitivity))x")
                        Slider(value: $viewModel.cursorSensitivity, in: 0.5...3.0, step: 0.1)
                    }
                }
                Section("ナビゲーション") {
                    Button("ホームに戻る") {
                        viewModel.goHome()
                        showSettings = false
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
