import SwiftUI

/// On-screen analog joystick that emits held key events (WASD or arrows)
/// based on the stick direction — 8-way, with proper keydown/keyup diffing.
struct JoystickView: View {
    @ObservedObject var viewModel: BrowserViewModel

    @State private var offset: CGSize = .zero
    @State private var heldKeys: Set<InputBridge.Key> = []
    @State private var repeatTimer: Timer?
    @State private var moveDragStart: CGSize? = nil

    private let radius: CGFloat = 58
    private let knobRadius: CGFloat = 26
    // Hysteresis: a direction engages above `pressThreshold` and only
    // releases below `releaseThreshold`, so keys don't flicker at the edge.
    private let pressThreshold: CGFloat = 0.42
    private let releaseThreshold: CGFloat = 0.26

    var body: some View {
        VStack(spacing: 4) {
            moveHandle
            stick
        }
        .offset(viewModel.joystickOffset)
        .onAppear { viewModel.ensureJoystickOnScreen() }
    }

    /// Grip above the stick: drag to reposition the whole joystick.
    private var moveHandle: some View {
        Image(systemName: "line.3.horizontal")
            .font(.system(size: 11, weight: .bold))
            .foregroundStyle(.white.opacity(0.5))
            .frame(width: 44, height: 22)
            .background(.black.opacity(0.3), in: Capsule())
            .overlay(Capsule().stroke(.white.opacity(0.2), lineWidth: 0.5))
            .gesture(
                // Global space: the handle moves with the drag, so local
                // coordinates would feed back into themselves and jitter.
                DragGesture(coordinateSpace: .global)
                    .onChanged { value in
                        if moveDragStart == nil { moveDragStart = viewModel.joystickOffset }
                        let start = moveDragStart ?? .zero
                        viewModel.joystickOffset = viewModel.clampJoystick(CGSize(
                            width: start.width + value.translation.width,
                            height: start.height + value.translation.height
                        ))
                    }
                    .onEnded { _ in moveDragStart = nil }
            )
    }

    private var stick: some View {
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
                    // Vector from the stick center to the finger — using the
                    // absolute location (not the drag translation) so the stick
                    // tracks the thumb correctly wherever the touch started.
                    let dx = value.location.x - radius
                    let dy = value.location.y - radius
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

        // Per-axis hysteresis: engaged directions stay on until the stick
        // returns well past the release threshold.
        func axis(_ value: CGFloat, negative: InputBridge.Key, positive: InputBridge.Key) {
            let negHeld = heldKeys.contains(negative)
            let posHeld = heldKeys.contains(positive)
            if value < -(negHeld ? releaseThreshold : pressThreshold) { wanted.insert(negative) }
            if value > (posHeld ? releaseThreshold : pressThreshold) { wanted.insert(positive) }
        }
        axis(y, negative: keys.up, positive: keys.down)
        axis(x, negative: keys.left, positive: keys.right)

        guard wanted != heldKeys else { return }
        for key in heldKeys.subtracting(wanted) { viewModel.keyUp(key) }
        for key in wanted.subtracting(heldKeys) { viewModel.keyDown(key) }
        heldKeys = wanted
        viewModel.hapticSelection()
        updateRepeat()
    }

    /// OS-style auto-repeat while directions are held (repeat=true keydowns).
    private func updateRepeat() {
        repeatTimer?.invalidate()
        repeatTimer = nil
        guard !heldKeys.isEmpty else { return }
        let vm = viewModel
        repeatTimer = Timer.scheduledTimer(withTimeInterval: 0.4, repeats: false) { _ in
            Task { @MainActor in
                self.repeatTimer = Timer.scheduledTimer(withTimeInterval: 0.07, repeats: true) { _ in
                    Task { @MainActor in
                        for key in self.heldKeys { vm.repeatKey(key) }
                    }
                }
            }
        }
    }
}
