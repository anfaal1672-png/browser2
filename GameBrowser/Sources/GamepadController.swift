import Foundation
import GameController
import UIKit

/// Maps a physical game controller (MFi / DualShock / Xbox over Bluetooth)
/// to the virtual input bridge:
///  - left stick / d-pad → WASD held keys
///  - right stick        → cursor movement
///  - A → Space, B → Shift, X → E, Y → Q, L1 → R, R1 → F, Menu → Escape
///  - R2 → left mouse button (hold to drag), L2 → right click
@MainActor
final class GamepadController {
    private weak var viewModel: BrowserViewModel?
    private var displayLink: CADisplayLink?
    private var heldKeys: Set<InputBridge.Key> = []
    private var leftMouseHeld = false
    private var rightTriggerWasPressed = false
    private var leftTriggerWasPressed = false

    private let stickThreshold: Float = 0.35
    private let cursorSpeed: CGFloat = 11

    init(viewModel: BrowserViewModel) {
        self.viewModel = viewModel

        NotificationCenter.default.addObserver(
            forName: .GCControllerDidConnect, object: nil, queue: .main
        ) { [weak self] _ in
            Task { @MainActor in self?.startPolling() }
        }
        NotificationCenter.default.addObserver(
            forName: .GCControllerDidDisconnect, object: nil, queue: .main
        ) { [weak self] _ in
            Task { @MainActor in self?.stopPollingIfIdle() }
        }
        if !GCController.controllers().isEmpty { startPolling() }
    }

    private func startPolling() {
        guard displayLink == nil else { return }
        let link = CADisplayLink(target: self, selector: #selector(tick))
        link.add(to: .main, forMode: .common)
        displayLink = link
    }

    private func stopPollingIfIdle() {
        guard GCController.controllers().isEmpty else { return }
        releaseEverything()
        displayLink?.invalidate()
        displayLink = nil
    }

    @objc private func tick() {
        guard let viewModel,
              let pad = (GCController.current ?? GCController.controllers().first)?.extendedGamepad
        else { return }

        // Right stick moves the cursor. cursorSpeed was tuned per-tick at
        // 60Hz; CADisplayLink fires at the display's native rate, so on a
        // 120Hz ProMotion device this would otherwise move the cursor twice
        // as fast as intended — scale by the actual frame duration.
        let frameScale = CGFloat((displayLink?.duration ?? 1.0 / 60.0) * 60)
        let rx = CGFloat(pad.rightThumbstick.xAxis.value)
        let ry = CGFloat(pad.rightThumbstick.yAxis.value)
        if abs(rx) > 0.12 || abs(ry) > 0.12 {
            viewModel.moveCursor(by: CGSize(width: rx * cursorSpeed * frameScale,
                                            height: -ry * cursorSpeed * frameScale))
        }

        // Left stick + d-pad → movement keys.
        var wanted: Set<InputBridge.Key> = []
        let lx = pad.leftThumbstick.xAxis.value
        let ly = pad.leftThumbstick.yAxis.value
        if ly > stickThreshold || pad.dpad.up.isPressed { wanted.insert(InputBridge.letter("w")) }
        if ly < -stickThreshold || pad.dpad.down.isPressed { wanted.insert(InputBridge.letter("s")) }
        if lx < -stickThreshold || pad.dpad.left.isPressed { wanted.insert(InputBridge.letter("a")) }
        if lx > stickThreshold || pad.dpad.right.isPressed { wanted.insert(InputBridge.letter("d")) }

        // Face / shoulder buttons → common game keys.
        if pad.buttonA.isPressed { wanted.insert(InputBridge.space) }
        if pad.buttonB.isPressed { wanted.insert(InputBridge.shift) }
        if pad.buttonX.isPressed { wanted.insert(InputBridge.letter("e")) }
        if pad.buttonY.isPressed { wanted.insert(InputBridge.letter("q")) }
        if pad.leftShoulder.isPressed { wanted.insert(InputBridge.letter("r")) }
        if pad.rightShoulder.isPressed { wanted.insert(InputBridge.letter("f")) }
        if pad.buttonMenu.isPressed { wanted.insert(InputBridge.escape) }

        for key in heldKeys.subtracting(wanted) { viewModel.keyUp(key) }
        for key in wanted.subtracting(heldKeys) { viewModel.keyDown(key) }
        heldKeys = wanted

        // R2: hold = left mouse button held (aim/drag), release = mouse up.
        let rightPressed = pad.rightTrigger.isPressed
        if rightPressed != rightTriggerWasPressed {
            rightTriggerWasPressed = rightPressed
            if rightPressed {
                viewModel.mouseDown()
                leftMouseHeld = true
            } else if leftMouseHeld {
                viewModel.mouseUp()
                leftMouseHeld = false
            }
        }

        // L2: right click on press.
        let leftPressed = pad.leftTrigger.isPressed
        if leftPressed != leftTriggerWasPressed {
            leftTriggerWasPressed = leftPressed
            if leftPressed { viewModel.click(button: 2) }
        }
    }

    private func releaseEverything() {
        guard let viewModel else { return }
        for key in heldKeys { viewModel.keyUp(key) }
        heldKeys = []
        if leftMouseHeld {
            viewModel.mouseUp()
            leftMouseHeld = false
        }
        // Without this, a trigger still physically held across a disconnect/
        // reconnect (e.g. a momentary Bluetooth drop) reads as "unchanged"
        // on the next tick and never fires mouseDown()/click() again until
        // the user fully releases and re-presses it.
        rightTriggerWasPressed = false
        leftTriggerWasPressed = false
    }
}
