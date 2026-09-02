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

    /// A two-finger gesture is classified once and then kept: a pinch that
    /// keeps flipping into a scroll halfway through is unusable.
    private enum TwoFingerMode { case undecided, scroll, zoom }
    private var twoFingerMode: TwoFingerMode = .undecided
    private var startDistance: CGFloat = 0
    private var startMidpoint: CGPoint = .zero

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
                viewModel.hapticMedium()
                viewModel.mouseDown()
                return
            }
            scheduleLongPress()
        } else if activeTouches.count == 2 {
            cancelLongPress()
            isTwoFingerGesture = true
            twoFingerMoved = false
            twoFingerMode = .undecided
            if lastPoints.count >= 2 {
                startDistance = hypot(lastPoints[0].x - lastPoints[1].x,
                                      lastPoints[0].y - lastPoints[1].y)
                startMidpoint = CGPoint(x: (lastPoints[0].x + lastPoints[1].x) / 2,
                                        y: (lastPoints[0].y + lastPoints[1].y) / 2)
            }
        }
    }

    override func touchesMoved(_ touches: Set<UITouch>, with event: UIEvent?) {
        guard let viewModel else { return }
        let points = activeTouches.map { $0.location(in: self) }
        defer { lastPoints = points }
        guard points.count == lastPoints.count, !points.isEmpty else { return }

        if activeTouches.count >= 2 {
            let spread = hypot(points[0].x - points[1].x, points[0].y - points[1].y)
            let lastSpread = hypot(lastPoints[0].x - lastPoints[1].x,
                                   lastPoints[0].y - lastPoints[1].y)
            let mid = CGPoint(x: (points[0].x + points[1].x) / 2,
                              y: (points[0].y + points[1].y) / 2)

            if twoFingerMode == .undecided {
                let pinched = abs(spread - startDistance)
                let travelled = hypot(mid.x - startMidpoint.x, mid.y - startMidpoint.y)
                if pinched > 20 {
                    twoFingerMode = .zoom
                } else if travelled > 10 {
                    twoFingerMode = .scroll
                }
            }

            // Average delta of both fingers.
            var dx: CGFloat = 0, dy: CGFloat = 0
            for i in 0..<points.count {
                dx += points[i].x - lastPoints[i].x
                dy += points[i].y - lastPoints[i].y
            }
            dx /= CGFloat(points.count)
            dy /= CGFloat(points.count)
            if abs(dx) + abs(dy) > 0.5 || abs(spread - lastSpread) > 0.5 { twoFingerMoved = true }

            switch twoFingerMode {
            case .zoom:
                // Pinch: the web view never sees these touches, so zooming is
                // driven from here.
                if lastSpread > 1 { viewModel.pinchZoom(by: spread / lastSpread, at: mid) }
            case .scroll:
                viewModel.scroll(dx: -dx * scrollSpeed, dy: -dy * scrollSpeed)
            case .undecided:
                break
            }
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
        let wasTwoFingerGesture = isTwoFingerGesture
        activeTouches.removeAll { touches.contains($0) }
        lastPoints = activeTouches.map { $0.location(in: self) }
        cancelLongPress()

        guard activeTouches.isEmpty else {
            // A multi-finger gesture is losing fingers before fully ending
            // (e.g. releasing one of two scrolling fingers). Don't let the
            // remaining finger's movement resolve into a stray tap/click
            // when it eventually lifts too — start it over as a fresh,
            // non-tappable gesture instead of carrying over stale state.
            if wasTwoFingerGesture {
                isTwoFingerGesture = false
                twoFingerMoved = false
                twoFingerMode = .undecided
                touchStartTime = .distantPast
                totalMovement = 0
            }
            return
        }

        if isDragging {
            isDragging = false
            viewModel.mouseUp()
        } else if isTwoFingerGesture {
            if !twoFingerMoved { viewModel.click(button: 2) }   // two-finger tap → right click
            isTwoFingerGesture = false
            twoFingerMode = .undecided
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
                viewModel.hapticMedium()
                viewModel.click(button: 2)
            } else {
                self.isDragging = true
                viewModel.hapticMedium()
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
