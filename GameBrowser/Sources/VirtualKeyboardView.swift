import SwiftUI

/// On-screen keyboard optimised for PC browser games.
/// Compact mode shows a gamepad-style layout (WASD + arrows + common game keys);
/// full mode shows a complete QWERTY keyboard. Keys fire keydown on press and
/// keyup on release so games with held-key movement work correctly.
struct VirtualKeyboardView: View {
    @ObservedObject var viewModel: BrowserViewModel

    /// Natural size of the gamepad layout; it is scaled down to fit narrow screens.
    private static let gamepadSize = CGSize(width: 540, height: 85)
    @State private var availableWidth: CGFloat = 0

    var body: some View {
        VStack(spacing: 6) {
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
        let scale = availableWidth > 0
            ? min(1, availableWidth / Self.gamepadSize.width) : 1

        return gamepadLayout
            .frame(width: Self.gamepadSize.width, height: Self.gamepadSize.height)
            .scaleEffect(scale)
            .frame(maxWidth: .infinity)
            .frame(height: Self.gamepadSize.height * scale)
            .background(
                GeometryReader { geo in
                    Color.clear
                        .onAppear { availableWidth = geo.size.width }
                        .onChange(of: geo.size.width) { _, w in availableWidth = w }
                }
            )
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
                    KeyButton(key: InputBridge.space, viewModel: viewModel, width: 128)
                    KeyButton(key: InputBridge.enter, viewModel: viewModel, width: 52)
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
                KeyButton(key: InputBridge.enter, viewModel: viewModel, width: 58)
            }
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
            .contentShape(Rectangle())
            .gesture(
                DragGesture(minimumDistance: 0)
                    .onChanged { _ in
                        guard !touchDown else { return }
                        touchDown = true
                        UIImpactFeedbackGenerator(style: .light).impactOccurred()
                        if sticky {
                            if viewModel.pressedKeys.contains(key) {
                                viewModel.keyUp(key)
                            } else {
                                viewModel.keyDown(key)
                            }
                        } else {
                            viewModel.keyDown(key)
                        }
                    }
                    .onEnded { _ in
                        touchDown = false
                        if !sticky { viewModel.keyUp(key) }
                    }
            )
    }
}
