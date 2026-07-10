import SwiftUI

struct ContentView: View {
    @StateObject private var viewModel = BrowserViewModel()
    @FocusState private var urlFieldFocused: Bool
    @State private var showSettings = false
    @State private var showBookmarks = false
    @State private var showTabs = false

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
                .overlay(alignment: .trailing) {
                    if viewModel.cursorMode { scrollStrip }
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
        .sheet(isPresented: $showTabs) { tabsSheet }
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

    // MARK: - Scroll wheel

    /// Points of finger travel per emulated wheel notch.
    private static let wheelNotchDistance: CGFloat = 14
    /// Standard wheel notch delta (WheelEvent.deltaY per click).
    private static let wheelNotchDelta: CGFloat = 120

    @State private var wheelAccumulated: CGFloat = 0
    @State private var wheelLastDrag: CGFloat = 0
    @State private var wheelActive = false

    /// Emulated mouse wheel on the right edge. Dragging rolls the wheel:
    /// each notch fires a WheelEvent with the classic ±120 deltaY, with a
    /// haptic tick per notch — drag down to scroll down, like a real wheel.
    private var scrollStrip: some View {
        VStack(spacing: 5) {
            ForEach(0..<5, id: \.self) { _ in
                RoundedRectangle(cornerRadius: 1)
                    .fill(.white.opacity(wheelActive ? 0.9 : 0.5))
                    .frame(width: 14, height: 2.5)
            }
        }
        .frame(width: 28, height: 96)
        .background(.black.opacity(wheelActive ? 0.55 : 0.3), in: Capsule())
        .overlay(Capsule().stroke(.white.opacity(0.25), lineWidth: 0.5))
        .padding(.trailing, 4)
        .contentShape(Capsule())
        .gesture(
            DragGesture(minimumDistance: 1)
                .onChanged { value in
                    wheelActive = true
                    let delta = value.translation.height - wheelLastDrag
                    wheelLastDrag = value.translation.height
                    wheelAccumulated += delta

                    // Emit whole notches as the finger travels.
                    while abs(wheelAccumulated) >= Self.wheelNotchDistance {
                        let direction: CGFloat = wheelAccumulated > 0 ? 1 : -1
                        viewModel.scroll(dx: 0, dy: direction * Self.wheelNotchDelta)
                        wheelAccumulated -= direction * Self.wheelNotchDistance
                        UISelectionFeedbackGenerator().selectionChanged()
                    }
                }
                .onEnded { _ in
                    wheelLastDrag = 0
                    wheelAccumulated = 0
                    wheelActive = false
                }
        )
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

            Button { showTabs = true } label: {
                ZStack {
                    Image(systemName: "square.on.square")
                    Text("\(viewModel.tabs.count)")
                        .font(.system(size: 9, weight: .bold))
                        .offset(y: 1)
                }
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
