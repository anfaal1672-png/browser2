import Foundation
import GameController
import UIKit

/// Maps a physical game controller (MFi / DualShock / Xbox over Bluetooth)
/// to the virtual input bridge:
///  - left stick / d-pad → WASD held keys
///  - right stick        → cursor movement
///  - every button and trigger → whatever the active control profile binds it
///    to (a key, a mouse click, a held mouse button, or nothing). The stock
///    mapping — A: Space, B: Shift, X: E, Y: Q, L1: R, R1: F, Menu: Escape,
///    R2: hold left button, L2: right click — is what an unconfigured
///    profile falls back to, so this behaves as before until it's changed.
@MainActor
final class GamepadController {
    private weak var viewModel: BrowserViewModel?
    private var displayLink: CADisplayLink?
    private var heldKeys: Set<InputBridge.Key> = []
    private var leftMouseHeld = false
    /// Slots whose binding is a mouse action, and are currently pressed —
    /// mouse bindings are edge-triggered rather than diffed like keys.
    private var mouseSlotsDown: Set<GamepadSlot> = []

    private let stickThreshold: Float = 0.35
    private let cursorSpeed: CGFloat = 11

    init(viewModel: BrowserViewModel) {
        self.viewModel = viewModel

        NotificationCenter.default.addObserver(
            forName: .GCControllerDidConnect, object: nil, queue: .main
        ) { [weak self] _ in
            Task { @MainActor in
                self?.startPolling()
                self?.reportConnection()
            }
        }
        NotificationCenter.default.addObserver(
            forName: .GCControllerDidDisconnect, object: nil, queue: .main
        ) { [weak self] _ in
            Task { @MainActor in
                self?.stopPollingIfIdle()
                self?.reportConnection()
            }
        }
        reportConnection()
        if !GCController.controllers().isEmpty { startPolling() }
    }

    /// Controller input never touches the screen, so the view model needs to
    /// know a pad is attached to keep the display from sleeping mid-game.
    private func reportConnection() {
        viewModel?.gamepadConnected = !GCController.controllers().isEmpty
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

        // Every mappable button, resolved through the active profile.
        let states: [(GamepadSlot, Bool)] = [
            (.buttonA, pad.buttonA.isPressed),
            (.buttonB, pad.buttonB.isPressed),
            (.buttonX, pad.buttonX.isPressed),
            (.buttonY, pad.buttonY.isPressed),
            (.leftShoulder, pad.leftShoulder.isPressed),
            (.rightShoulder, pad.rightShoulder.isPressed),
            (.menu, pad.buttonMenu.isPressed),
            (.leftTrigger, pad.leftTrigger.isPressed),
            (.rightTrigger, pad.rightTrigger.isPressed),
        ]
        for (slot, pressed) in states {
            let binding = viewModel.gamepadBinding(slot)
            if let key = KeyCatalog.key(binding) {
                if pressed { wanted.insert(key) }
            } else {
                updateMouseBinding(binding, slot: slot, pressed: pressed)
            }
        }

        for key in heldKeys.subtracting(wanted) { viewModel.keyUp(key) }
        for key in wanted.subtracting(heldKeys) { viewModel.keyDown(key) }
        heldKeys = wanted
    }

    /// Mouse bindings fire on the press/release edge instead of being held
    /// like a key: a click is a click, and "hold" keeps the button down for
    /// as long as the physical control is.
    private func updateMouseBinding(_ binding: String, slot: GamepadSlot, pressed: Bool) {
        guard let viewModel else { return }
        let wasDown = mouseSlotsDown.contains(slot)
        guard pressed != wasDown else { return }
        if pressed { mouseSlotsDown.insert(slot) } else { mouseSlotsDown.remove(slot) }

        switch binding {
        case PadKeyName.leftClickHold:
            if pressed {
                viewModel.mouseDown()
                leftMouseHeld = true
            } else if leftMouseHeld {
                viewModel.mouseUp()
                leftMouseHeld = false
            }
        case PadKeyName.leftClick:
            if pressed { viewModel.click() }
        case PadKeyName.rightClick:
            if pressed { viewModel.click(button: 2) }
        default:
            break   // unbound
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
        // Without this, a control still physically held across a disconnect/
        // reconnect (e.g. a momentary Bluetooth drop) reads as "unchanged"
        // on the next tick and never fires mouseDown()/click() again until
        // the user fully releases and re-presses it.
        mouseSlotsDown.removeAll()
    }
}
