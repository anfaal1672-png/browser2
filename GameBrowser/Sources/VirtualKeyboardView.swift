import SwiftUI

/// On-screen keyboard optimised for PC browser games.
/// Compact mode shows a gamepad-style layout (WASD + arrows + common game keys);
/// full mode shows a complete QWERTY keyboard. Keys fire keydown on press and
/// keyup on release so games with held-key movement work correctly.
struct VirtualKeyboardView: View {
    @ObservedObject var viewModel: BrowserViewModel

    /// Natural size of the gamepad layout; it is scaled down to fit narrow screens.
    private static let gamepadSize = CGSize(width: 540, height: 85)

    var body: some View {
        VStack(spacing: 6) {
            if viewModel.imeActive {
                imeBar
            }
            if viewModel.fullKeyboard {
                fullKeyboard
            } else {
                scaledGamepad
            }
        }
        .padding(.horizontal, 8)
        .padding(.vertical, 8)
        .background(.ultraThinMaterial)
    }

    // MARK: - Gamepad layout

    /// Shrinks the fixed-size gamepad layout to fit the available width.
    private var scaledGamepad: some View {
        GeometryReader { geo in
            let scale = min(1, geo.size.width / Self.gamepadSize.width)
            gamepadLayout
                .frame(width: Self.gamepadSize.width, height: Self.gamepadSize.height)
                .scaleEffect(scale, anchor: .top)
                .frame(width: geo.size.width, height: geo.size.height, alignment: .top)
        }
        .frame(height: Self.gamepadSize.height * estimatedScale)
    }

    /// Height reservation for the scaled layout, derived from the window width.
    private var estimatedScale: CGFloat {
        let width = (UIApplication.shared.connectedScenes
            .compactMap { ($0 as? UIWindowScene)?.keyWindow }
            .first?.bounds.width) ?? UIScreen.main.bounds.width
        return min(1, (width - 16) / Self.gamepadSize.width)
    }

    private var gamepadLayout: some View {
        HStack(alignment: .center, spacing: 14) {
            // WASD cluster
            VStack(spacing: 5) {
                KeyButton(key: InputBridge.letter("w"), viewModel: viewModel)
                HStack(spacing: 5) {
                    KeyButton(key: InputBridge.letter("a"), viewModel: viewModel)
                    KeyButton(key: InputBridge.letter("s"), viewModel: viewModel)
                    KeyButton(key: InputBridge.letter("d"), viewModel: viewModel)
                }
            }

            // Common game keys
            VStack(spacing: 5) {
                HStack(spacing: 5) {
                    KeyButton(key: InputBridge.shift, viewModel: viewModel, sticky: true, width: 62)
                    KeyButton(key: InputBridge.letter("e"), viewModel: viewModel)
                    KeyButton(key: InputBridge.letter("q"), viewModel: viewModel)
                    KeyButton(key: InputBridge.letter("r"), viewModel: viewModel)
                    KeyButton(key: InputBridge.letter("f"), viewModel: viewModel)
                }
                HStack(spacing: 5) {
                    KeyButton(key: InputBridge.escape, viewModel: viewModel, width: 62)
                    KeyButton(key: InputBridge.space, viewModel: viewModel, width: 84)
                    KeyButton(key: InputBridge.enter, viewModel: viewModel, width: 52)
                    imeKey
                }
            }

            // Arrow cluster
            VStack(spacing: 5) {
                KeyButton(key: InputBridge.arrowUp, viewModel: viewModel)
                HStack(spacing: 5) {
                    KeyButton(key: InputBridge.arrowLeft, viewModel: viewModel)
                    KeyButton(key: InputBridge.arrowDown, viewModel: viewModel)
                    KeyButton(key: InputBridge.arrowRight, viewModel: viewModel)
                }
            }
        }
        .frame(maxWidth: .infinity)
    }

    // MARK: - Full QWERTY

    private var fullKeyboard: some View {
        VStack(spacing: 5) {
            keyRow(["1", "2", "3", "4", "5", "6", "7", "8", "9", "0"].map(InputBridge.digit))
            keyRow("qwertyuiop".map { InputBridge.letter(String($0)) })
            keyRow("asdfghjkl".map { InputBridge.letter(String($0)) })
            HStack(spacing: 5) {
                KeyButton(key: InputBridge.shift, viewModel: viewModel, sticky: true, width: 58)
                ForEach("zxcvbnm".map { InputBridge.letter(String($0)) }, id: \.self) {
                    KeyButton(key: $0, viewModel: viewModel, flexible: true)
                }
                KeyButton(key: InputBridge.backspace, viewModel: viewModel, width: 58)
            }
            HStack(spacing: 5) {
                KeyButton(key: InputBridge.escape, viewModel: viewModel, width: 54)
                KeyButton(key: InputBridge.ctrl, viewModel: viewModel, sticky: true, width: 54)
                KeyButton(key: InputBridge.tab, viewModel: viewModel, width: 44)
                KeyButton(key: InputBridge.space, viewModel: viewModel, flexible: true)
                imeKey
                KeyButton(key: InputBridge.enter, viewModel: viewModel, width: 58)
            }
        }
    }

    // MARK: - Built-in romaji IME

    /// Composition + kanji candidate bar shown above the keys while the IME is on.
    private var imeBar: some View {
        HStack(spacing: 8) {
            // Composition display: converted kana + pending romaji.
            HStack(spacing: 0) {
                Text(viewModel.imeKana)
                    .foregroundStyle(.white)
                Text(viewModel.imePending)
                    .foregroundStyle(.secondary)
                if viewModel.imeComposition.isEmpty {
                    Text("ローマ字で入力…")
                        .foregroundStyle(.secondary)
                }
            }
            .font(.system(size: 15))
            .lineLimit(1)
            .padding(.horizontal, 10)
            .frame(height: 34)
            .frame(minWidth: 110, alignment: .leading)
            .background(Color.white.opacity(0.1), in: RoundedRectangle(cornerRadius: 8))

            // Kanji candidates from the conversion API.
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 6) {
                    if !viewModel.imeComposition.isEmpty {
                        candidateChip(viewModel.imeComposition)
                    }
                    ForEach(viewModel.imeCandidates, id: \.self) { candidate in
                        candidateChip(candidate)
                    }
                }
            }

            Button {
                viewModel.imeActive = false
            } label: {
                Image(systemName: "xmark.circle.fill")
                    .font(.system(size: 16))
                    .foregroundStyle(.secondary)
            }
        }
        .padding(.horizontal, 4)
    }

    private func candidateChip(_ text: String) -> some View {
        Button {
            viewModel.imeSelectCandidate(text)
        } label: {
            Text(text)
                .font(.system(size: 14, weight: .medium))
                .foregroundStyle(.white)
                .padding(.horizontal, 10)
                .frame(height: 30)
                .background(Color.cyan.opacity(0.22), in: Capsule())
                .overlay(Capsule().stroke(Color.cyan.opacity(0.4), lineWidth: 0.5))
        }
    }

    /// Toggles the built-in romaji IME (switches to the full layout for letters).
    private var imeKey: some View {
        Button {
            viewModel.hapticLight()
            viewModel.imeActive.toggle()
            if viewModel.imeActive { viewModel.fullKeyboard = true }
        } label: {
            Text("あ")
                .font(.system(size: 15, weight: .semibold, design: .rounded))
                .foregroundStyle(viewModel.imeActive ? Color.black : Color.cyan)
                .frame(width: 40, height: 40)
                .background(
                    RoundedRectangle(cornerRadius: 8)
                        .fill(viewModel.imeActive ? Color.cyan : Color.white.opacity(0.14))
                )
                .overlay(
                    RoundedRectangle(cornerRadius: 8)
                        .stroke(Color.cyan.opacity(0.5), lineWidth: 0.5)
                )
        }
    }

    private func keyRow(_ keys: [InputBridge.Key]) -> some View {
        HStack(spacing: 5) {
            ForEach(keys, id: \.self) {
                KeyButton(key: $0, viewModel: viewModel, flexible: true)
            }
        }
    }
}

/// A single key. Press = keydown, release = keyup. `sticky` keys (Shift/Ctrl)
/// toggle on tap and stay held until tapped again — needed for one-thumb combos.
struct KeyButton: View {
    let key: InputBridge.Key
    @ObservedObject var viewModel: BrowserViewModel
    var sticky: Bool = false
    var width: CGFloat? = nil
    var flexible: Bool = false

    @State private var touchDown = false
    @State private var repeatTimer: Timer?

    private var isActive: Bool {
        touchDown || (sticky && viewModel.pressedKeys.contains(key))
    }

    var body: some View {
        Text(key.label)
            .font(.system(size: 15, weight: .semibold, design: .rounded))
            .foregroundStyle(isActive ? Color.black : Color.white)
            .frame(width: flexible ? nil : (width ?? 40), height: 40)
            .frame(maxWidth: flexible ? .infinity : nil)
            .background(
                RoundedRectangle(cornerRadius: 8)
                    .fill(isActive ? Color.white : Color.white.opacity(0.14))
            )
            .overlay(
                RoundedRectangle(cornerRadius: 8)
                    .stroke(Color.white.opacity(0.25), lineWidth: 0.5)
            )
            .scaleEffect(isActive ? 0.93 : 1.0)
            .animation(.easeOut(duration: 0.08), value: isActive)
            .contentShape(Rectangle())
            .gesture(
                DragGesture(minimumDistance: 0)
                    .onChanged { _ in
                        guard !touchDown else { return }
                        touchDown = true
                        viewModel.hapticLight()
                        // IME mode: letters feed the romaji buffer instead of the page.
                        if viewModel.imeActive, !sticky {
                            let vm = viewModel
                            let k = key
                            var imeAction: (() -> Void)?
                            switch key {
                            case InputBridge.backspace: imeAction = { vm.imeBackspace() }
                            case InputBridge.enter: vm.imeConfirm()
                            case InputBridge.space: vm.imeSpace()
                            case InputBridge.escape: vm.imeActive = false
                            default:
                                if k.key.count == 1 { imeAction = { vm.imeType(k.key) } }
                            }
                            if let imeAction {
                                imeAction()
                                // Auto-repeat for letters and backspace.
                                repeatTimer = Timer.scheduledTimer(withTimeInterval: 0.4, repeats: false) { _ in
                                    Task { @MainActor in
                                        self.repeatTimer = Timer.scheduledTimer(withTimeInterval: 0.07, repeats: true) { _ in
                                            Task { @MainActor in imeAction() }
                                        }
                                    }
                                }
                            }
                            return
                        }
                        if sticky {
                            if viewModel.pressedKeys.contains(key) {
                                viewModel.keyUp(key)
                            } else {
                                viewModel.keyDown(key)
                            }
                        } else {
                            viewModel.keyDown(key)
                            // OS-style auto-repeat after an initial delay.
                            let vm = viewModel
                            let k = key
                            repeatTimer = Timer.scheduledTimer(withTimeInterval: 0.4, repeats: false) { _ in
                                Task { @MainActor in
                                    guard vm.pressedKeys.contains(k) else { return }
                                    self.repeatTimer = Timer.scheduledTimer(withTimeInterval: 0.07, repeats: true) { _ in
                                        Task { @MainActor in vm.repeatKey(k) }
                                    }
                                }
                            }
                        }
                    }
                    .onEnded { _ in
                        touchDown = false
                        repeatTimer?.invalidate()
                        repeatTimer = nil
                        if !sticky && !viewModel.imeActive { viewModel.keyUp(key) }
                    }
            )
    }
}
