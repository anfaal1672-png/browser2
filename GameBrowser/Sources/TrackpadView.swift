import SwiftUI
import UIKit

/// Full-screen transparent trackpad layered over the web view when cursor mode
/// is active. Gestures:
///  - 1 finger drag  → move the virtual cursor (relative, like a laptop trackpad)
///  - 1 finger tap   → left click (double-tap → double click)
///  - long press     → hold left button and drag (drag & drop)
///  - 2 finger drag  → scroll wheel
///  - 2 finger tap   → right click
struct TrackpadView: UIViewRepresentable {
    @ObservedObject var viewModel: BrowserViewModel

    func makeUIView(context: Context) -> TrackpadUIView {
        let view = TrackpadUIView()
        view.viewModel = viewModel
        view.backgroundColor = .clear
        view.isMultipleTouchEnabled = true
        return view
    }

    func updateUIView(_ uiView: TrackpadUIView, context: Context) {
        uiView.viewModel = viewModel
    }
}

final class TrackpadUIView: UIView {
    weak var viewModel: BrowserViewModel?

    private var activeTouches: [UITouch] = []
    private var touchStartTime: Date = .distantPast
    private var totalMovement: CGFloat = 0
    private var isDragging = false          // long-press drag in progress
    private var isTwoFingerGesture = false
    private var twoFingerMoved = false
    private var longPressTimer: Timer?
    private var lastPoints: [CGPoint] = []

    private let tapMaxDuration: TimeInterval = 0.30
    private let tapMaxMovement: CGFloat = 10
    private let longPressDelay: TimeInterval = 0.45
    private let scrollSpeed: CGFloat = 2.2

    // Quick scheme state
    private var lastTapEndTime: Date = .distantPast
    private var lastTapEndPoint: CGPoint = .zero
    private var velocity: CGPoint = .zero
    private var lastMoveTimestamp: TimeInterval = 0
    private var glideTimer: Timer?

    private var isQuick: Bool { viewModel?.controlScheme == .quick }

    override func touchesBegan(_ touches: Set<UITouch>, with event: UIEvent?) {
        stopGlide()
        for touch in touches where !activeTouches.contains(touch) {
            activeTouches.append(touch)
        }
        lastPoints = activeTouches.map { $0.location(in: self) }

        if activeTouches.count == 1 {
            touchStartTime = Date()
            totalMovement = 0
            isTwoFingerGesture = false
            velocity = .zero
            lastMoveTimestamp = touches.first?.timestamp ?? 0

            // Quick scheme: tap-and-a-half — a touch starting right after a
            // tap (near the same spot) begins a drag immediately.
            if isQuick,
               Date().timeIntervalSince(lastTapEndTime) < 0.30,
               let p = lastPoints.first,
               hypot(p.x - lastTapEndPoint.x, p.y - lastTapEndPoint.y) < 60,
               let viewModel {
                isDragging = true
                UIImpactFeedbackGenerator(style: .medium).impactOccurred()
                viewModel.mouseDown()
                return
            }
            scheduleLongPress()
        } else if activeTouches.count == 2 {
            cancelLongPress()
            isTwoFingerGesture = true
            twoFingerMoved = false
        }
    }

    override func touchesMoved(_ touches: Set<UITouch>, with event: UIEvent?) {
        guard let viewModel else { return }
        let points = activeTouches.map { $0.location(in: self) }
        defer { lastPoints = points }
        guard points.count == lastPoints.count, !points.isEmpty else { return }

        if activeTouches.count >= 2 {
            // Two-finger scroll: average delta of both fingers.
            var dx: CGFloat = 0, dy: CGFloat = 0
            for i in 0..<points.count {
                dx += points[i].x - lastPoints[i].x
                dy += points[i].y - lastPoints[i].y
            }
            dx /= CGFloat(points.count)
            dy /= CGFloat(points.count)
            if abs(dx) + abs(dy) > 0.5 { twoFingerMoved = true }
            viewModel.scroll(dx: -dx * scrollSpeed, dy: -dy * scrollSpeed)
        } else {
            let dx = points[0].x - lastPoints[0].x
            let dy = points[0].y - lastPoints[0].y
            totalMovement += abs(dx) + abs(dy)
            if totalMovement > tapMaxMovement { cancelLongPress() }
            viewModel.moveCursor(by: CGSize(width: dx, height: dy))

            // Track velocity for flick momentum (quick scheme).
            if let ts = activeTouches.first?.timestamp {
                let dt = max(ts - lastMoveTimestamp, 0.001)
                lastMoveTimestamp = ts
                velocity = CGPoint(x: dx / dt, y: dy / dt)
            }
        }
    }

    override func touchesEnded(_ touches: Set<UITouch>, with event: UIEvent?) {
        endTouches(touches)
    }

    override func touchesCancelled(_ touches: Set<UITouch>, with event: UIEvent?) {
        endTouches(touches)
    }

    private func endTouches(_ touches: Set<UITouch>) {
        guard let viewModel else { return }
        activeTouches.removeAll { touches.contains($0) }
        lastPoints = activeTouches.map { $0.location(in: self) }
        cancelLongPress()

        guard activeTouches.isEmpty else { return }

        if isDragging {
            isDragging = false
            viewModel.mouseUp()
        } else if isTwoFingerGesture {
            if !twoFingerMoved { viewModel.click(button: 2) }   // two-finger tap → right click
            isTwoFingerGesture = false
        } else if Date().timeIntervalSince(touchStartTime) < tapMaxDuration,
                  totalMovement <= tapMaxMovement {
            if viewModel.dragLocked {
                viewModel.toggleDragLock()                       // tap releases drag-lock
            } else {
                viewModel.click()
                lastTapEndTime = Date()
                lastTapEndPoint = touches.first?.location(in: self) ?? .zero
            }
        } else if isQuick, totalMovement > tapMaxMovement {
            startGlide()                                          // flick momentum
        }
    }

    // MARK: - Long press

    private func scheduleLongPress() {
        cancelLongPress()
        longPressTimer = Timer.scheduledTimer(withTimeInterval: longPressDelay, repeats: false) { [weak self] _ in
            guard let self, let viewModel = self.viewModel,
                  self.activeTouches.count == 1, self.totalMovement <= self.tapMaxMovement
            else { return }
            if self.isQuick {
                // Quick scheme: long-press = right click (drag uses tap-and-a-half).
                UIImpactFeedbackGenerator(style: .medium).impactOccurred()
                viewModel.click(button: 2)
            } else {
                self.isDragging = true
                UIImpactFeedbackGenerator(style: .medium).impactOccurred()
                viewModel.mouseDown()
            }
        }
    }

    private func cancelLongPress() {
        longPressTimer?.invalidate()
        longPressTimer = nil
    }

    // MARK: - Flick momentum (quick scheme)

    private func startGlide() {
        stopGlide()
        var v = velocity
        guard hypot(v.x, v.y) > 350 else { return }   // only real flicks glide
        glideTimer = Timer.scheduledTimer(withTimeInterval: 1.0 / 60, repeats: true) { [weak self] _ in
            guard let self, let viewModel = self.viewModel else { return }
            Task { @MainActor in
                viewModel.moveCursor(by: CGSize(width: v.x / 60, height: v.y / 60))
            }
            v.x *= 0.92
            v.y *= 0.92
            if hypot(v.x, v.y) < 40 { self.stopGlide() }
        }
    }

    private func stopGlide() {
        glideTimer?.invalidate()
        glideTimer = nil
    }
}
