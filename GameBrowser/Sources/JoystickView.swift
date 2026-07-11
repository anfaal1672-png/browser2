import SwiftUI

/// On-screen analog joystick that emits held key events (WASD or arrows)
/// based on the stick direction — 8-way, with proper keydown/keyup diffing.
struct JoystickView: View {
    @ObservedObject var viewModel: BrowserViewModel

    @State private var offset: CGSize = .zero
    @State private var heldKeys: Set<InputBridge.Key> = []

    private let radius: CGFloat = 58
    private let knobRadius: CGFloat = 26
    private let threshold: CGFloat = 0.34

    var body: some View {
        ZStack {
            Circle()
                .fill(.black.opacity(0.30))
                .overlay(Circle().stroke(.white.opacity(0.25), lineWidth: 1))
            // Direction hints
            ForEach(0..<4, id: \.self) { i in
                Image(systemName: "chevron.up")
                    .font(.system(size: 10, weight: .bold))
                    .foregroundStyle(.white.opacity(0.35))
                    .offset(y: -radius + 12)
                    .rotationEffect(.degrees(Double(i) * 90))
            }
            Circle()
                .fill(.white.opacity(heldKeys.isEmpty ? 0.35 : 0.6))
                .frame(width: knobRadius * 2, height: knobRadius * 2)
                .offset(offset)
                .animation(.easeOut(duration: 0.1), value: heldKeys.isEmpty)
        }
        .frame(width: radius * 2, height: radius * 2)
        .contentShape(Circle())
        .gesture(
            DragGesture(minimumDistance: 0)
                .onChanged { value in
                    let dx = value.translation.width
                    let dy = value.translation.height
                    let len = max(hypot(dx, dy), 0.001)
                    let clamped = min(len, radius - knobRadius / 2)
                    offset = CGSize(width: dx / len * clamped, height: dy / len * clamped)
                    updateKeys(x: dx / radius, y: dy / radius)
                }
                .onEnded { _ in
                    offset = .zero
                    updateKeys(x: 0, y: 0)
                }
        )
    }

    private var keySet: (up: InputBridge.Key, down: InputBridge.Key,
                         left: InputBridge.Key, right: InputBridge.Key) {
        if viewModel.joystickUsesArrows {
            return (InputBridge.arrowUp, InputBridge.arrowDown,
                    InputBridge.arrowLeft, InputBridge.arrowRight)
        }
        return (InputBridge.letter("w"), InputBridge.letter("s"),
                InputBridge.letter("a"), InputBridge.letter("d"))
    }

    private func updateKeys(x: CGFloat, y: CGFloat) {
        let keys = keySet
        var wanted: Set<InputBridge.Key> = []
        if y < -threshold { wanted.insert(keys.up) }
        if y > threshold { wanted.insert(keys.down) }
        if x < -threshold { wanted.insert(keys.left) }
        if x > threshold { wanted.insert(keys.right) }

        for key in heldKeys.subtracting(wanted) { viewModel.keyUp(key) }
        for key in wanted.subtracting(heldKeys) {
            viewModel.keyDown(key)
            UISelectionFeedbackGenerator().selectionChanged()
        }
        heldKeys = wanted
    }
}
