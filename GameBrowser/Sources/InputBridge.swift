import Foundation

/// JavaScript injected at document start. It exposes `window.__gb`, a bridge that
/// synthesizes trusted-looking pointer / mouse / wheel / keyboard events so that
/// desktop-oriented browser games respond to the app's virtual cursor and keys.
enum InputBridge {

    static let script = #"""
    (function () {
        if (window.__gb) { return; }

        const state = {
            x: 0, y: 0,
            buttons: 0,
            lastTarget: null,
            lastDownTarget: null,
            keyFrame: null,   // iframe element that should receive key events
            raw: false,       // true while handling a call forwarded from a parent frame
        };

        // Native code sends coordinates in view points; desktop-mode pages are
        // zoomed out, so convert to CSS/layout-viewport coordinates. Calls
        // forwarded from a parent frame are already in CSS coordinates.
        function toPage(x, y) {
            const v = window.visualViewport;
            if (state.raw || !v) { return { x: x, y: y }; }
            return { x: x / v.scale + v.offsetLeft, y: y / v.scale + v.offsetTop };
        }

        function scaleFactor() {
            return (state.raw || !window.visualViewport) ? 1 : window.visualViewport.scale;
        }

        function targetAt(x, y) {
            let el = document.elementFromPoint(x, y);
            // Descend into shadow DOM so events reach the real element.
            while (el && el.shadowRoot) {
                const inner = el.shadowRoot.elementFromPoint(x, y);
                if (!inner || inner === el) { break; }
                el = inner;
            }
            return el || document.documentElement;
        }

        function isFrame(el) {
            const tag = el && el.tagName;
            return tag === 'IFRAME' || tag === 'FRAME';
        }

        // Forward a bridge call into a child (i)frame, translating coordinates
        // into that frame's viewport. Works cross-origin via postMessage; the
        // same script is injected into every frame, so this recurses naturally.
        function forward(frameEl, fn, x, y, rest) {
            const r = frameEl.getBoundingClientRect();
            const fx = x - (r.left + (frameEl.clientLeft || 0));
            const fy = y - (r.top + (frameEl.clientTop || 0));
            try {
                frameEl.contentWindow.postMessage(
                    { __gbCall: fn, args: [fx, fy].concat(rest || []) }, '*');
            } catch (e) {}
        }

        function common(x, y, extra) {
            return Object.assign({
                bubbles: true,
                cancelable: true,
                composed: true,
                view: window,
                clientX: x,
                clientY: y,
                screenX: x + (window.screenX || 0),
                screenY: y + (window.screenY || 0),
                buttons: state.buttons,
            }, extra || {});
        }

        function firePointer(type, target, x, y, button, extra) {
            try {
                target.dispatchEvent(new PointerEvent(type, common(x, y, Object.assign({
                    pointerId: 1,
                    pointerType: 'mouse',
                    isPrimary: true,
                    button: button,
                    width: 1, height: 1, pressure: state.buttons ? 0.5 : 0,
                }, extra))));
            } catch (e) { /* PointerEvent unsupported */ }
        }

        function fireMouse(type, target, x, y, button, extra) {
            target.dispatchEvent(new MouseEvent(type, common(x, y, Object.assign({
                button: button,
                detail: (type === 'dblclick') ? 2 : 1,
            }, extra))));
        }

        function hoverTransition(target, x, y) {
            const prev = state.lastTarget;
            if (prev === target) { return; }
            if (prev) {
                fireMouse('mouseout', prev, x, y, 0, { relatedTarget: target });
                firePointer('pointerout', prev, x, y, -1, { relatedTarget: target });
            }
            if (target) {
                fireMouse('mouseover', target, x, y, 0, { relatedTarget: prev });
                firePointer('pointerover', target, x, y, -1, { relatedTarget: prev });
            }
            state.lastTarget = target;
        }

        const bridge = {
            move: function (x, y, dx, dy) {
                const p = toPage(x, y); x = p.x; y = p.y;
                const s = scaleFactor();
                dx = (dx || 0) / s; dy = (dy || 0) / s;
                state.x = x; state.y = y;
                const locked = document.pointerLockElement;
                const target = locked || targetAt(x, y);
                // Keep drags anchored to the outer document: only hand the move to
                // an iframe when no button is held in this frame.
                if (!locked && !state.buttons && isFrame(target)) {
                    forward(target, 'move', x, y, [dx, dy]);
                    return;
                }
                const extra = { movementX: dx || 0, movementY: dy || 0 };
                if (!locked) { hoverTransition(target, x, y); }
                firePointer('pointermove', target, x, y, -1, extra);
                fireMouse('mousemove', target, x, y, 0, extra);
            },

            down: function (x, y, button) {
                const p = toPage(x, y); x = p.x; y = p.y;
                state.x = x; state.y = y;
                const lockedEl = document.pointerLockElement;
                if (!lockedEl) {
                    const t = targetAt(x, y);
                    if (isFrame(t)) {
                        state.keyFrame = t;
                        forward(t, 'down', x, y, [button]);
                        return;
                    }
                    state.keyFrame = null;
                }
                state.buttons |= (button === 2 ? 2 : button === 1 ? 4 : 1);
                const locked = document.pointerLockElement;
                const target = locked || targetAt(x, y);
                state.lastDownTarget = target;
                firePointer('pointerdown', target, x, y, button);
                fireMouse('mousedown', target, x, y, button);
                if (!locked && button === 0 && target && typeof target.focus === 'function') {
                    const tag = (target.tagName || '').toLowerCase();
                    if (tag === 'input' || tag === 'textarea' || tag === 'select' ||
                        target.isContentEditable || tag === 'canvas' || tag === 'button' || tag === 'a') {
                        target.focus({ preventScroll: true });
                    }
                }
            },

            up: function (x, y, button, clickCount) {
                const p = toPage(x, y); x = p.x; y = p.y;
                state.x = x; state.y = y;
                // Forward the release only if the press was also forwarded
                // (no button is held in this frame); otherwise finish the local drag.
                if (!document.pointerLockElement && !state.buttons) {
                    const t = targetAt(x, y);
                    if (isFrame(t)) {
                        forward(t, 'up', x, y, [button, clickCount]);
                        return;
                    }
                }
                state.buttons &= ~(button === 2 ? 2 : button === 1 ? 4 : 1);
                const locked = document.pointerLockElement;
                const target = locked || targetAt(x, y);
                firePointer('pointerup', target, x, y, button);
                fireMouse('mouseup', target, x, y, button);
                if (button === 0 && (state.lastDownTarget === target || locked)) {
                    fireMouse('click', target, x, y, 0);
                    if (clickCount === 2) { fireMouse('dblclick', target, x, y, 0); }
                } else if (button === 2) {
                    fireMouse('contextmenu', target, x, y, 2);
                }
            },

            wheel: function (x, y, dx, dy) {
                const p = toPage(x, y); x = p.x; y = p.y;
                const s = scaleFactor();
                dx /= s; dy /= s;
                const target = document.pointerLockElement || targetAt(x, y);
                if (!document.pointerLockElement && isFrame(target)) {
                    forward(target, 'wheel', x, y, [dx, dy]);
                    return;
                }
                const ev = new WheelEvent('wheel', common(x, y, {
                    deltaX: dx, deltaY: dy, deltaMode: 0,
                }));
                const notCancelled = target.dispatchEvent(ev);
                if (notCancelled) { window.scrollBy(dx, dy); }
            },

            key: function (type, key, code, keyCode, mods) {
                mods = mods || {};
                // Route keys to the frame the user last clicked in.
                const kf = state.keyFrame;
                if (kf && kf.isConnected) {
                    try {
                        kf.contentWindow.postMessage(
                            { __gbCall: 'key', args: [type, key, code, keyCode, mods] }, '*');
                        return;
                    } catch (e) {}
                }
                const target = document.activeElement || document.body;
                const ev = new KeyboardEvent(type, {
                    bubbles: true, cancelable: true, composed: true, view: window,
                    key: key, code: code,
                    shiftKey: !!mods.shift, ctrlKey: !!mods.ctrl,
                    altKey: !!mods.alt, metaKey: !!mods.meta,
                    repeat: !!mods.repeat,
                });
                // keyCode / which are read-only on the constructor; patch them on.
                Object.defineProperty(ev, 'keyCode', { get: function () { return keyCode; } });
                Object.defineProperty(ev, 'which', { get: function () { return keyCode; } });
                const notCancelled = target.dispatchEvent(ev);
                // Emulate text entry into editable fields for printable keys.
                if (type === 'keydown' && notCancelled && key.length === 1) {
                    const el = document.activeElement;
                    if (el && (el.tagName === 'INPUT' || el.tagName === 'TEXTAREA')) {
                        const start = el.selectionStart, end = el.selectionEnd;
                        if (start !== null) {
                            el.setRangeText(key, start, end, 'end');
                            el.dispatchEvent(new InputEvent('input', { bubbles: true, data: key, inputType: 'insertText' }));
                        }
                    } else if (el && el.isContentEditable) {
                        document.execCommand('insertText', false, key);
                    }
                }
                if (type === 'keydown' && notCancelled && key === 'Backspace') {
                    const el = document.activeElement;
                    if (el && (el.tagName === 'INPUT' || el.tagName === 'TEXTAREA')) {
                        const start = el.selectionStart, end = el.selectionEnd;
                        if (start !== null) {
                            if (start === end && start > 0) { el.setRangeText('', start - 1, end, 'end'); }
                            else { el.setRangeText('', start, end, 'end'); }
                            el.dispatchEvent(new InputEvent('input', { bubbles: true, inputType: 'deleteContentBackward' }));
                        }
                    }
                }
            },

            isPointerLocked: function () {
                return !!document.pointerLockElement;
            },
        };

        window.__gb = bridge;

        // Receive calls forwarded from a parent frame (iframe support).
        window.addEventListener('message', function (e) {
            const d = e.data;
            if (!d || !d.__gbCall || typeof bridge[d.__gbCall] !== 'function') { return; }
            state.raw = true;
            try { bridge[d.__gbCall].apply(null, d.args || []); }
            finally { state.raw = false; }
        });

        // Notify native code about pointer-lock transitions so the cursor overlay
        // can hide. Message handlers are reachable from subframes too.
        document.addEventListener('pointerlockchange', function () {
            try {
                window.webkit.messageHandlers.gbEvents.postMessage({
                    type: 'pointerlock', locked: !!document.pointerLockElement,
                });
            } catch (e) {}
        });
    })();
    """#

    /// Standard key metadata used by the virtual keyboard.
    struct Key: Hashable {
        let key: String     // KeyboardEvent.key
        let code: String    // KeyboardEvent.code
        let keyCode: Int    // legacy keyCode/which
        let label: String   // what the button shows

        init(_ key: String, _ code: String, _ keyCode: Int, label: String? = nil) {
            self.key = key
            self.code = code
            self.keyCode = keyCode
            self.label = label ?? key.uppercased()
        }
    }

    static func letter(_ c: String) -> Key {
        Key(c, "Key\(c.uppercased())", Int(c.uppercased().unicodeScalars.first!.value))
    }

    static func digit(_ d: String) -> Key {
        Key(d, "Digit\(d)", Int(d.unicodeScalars.first!.value), label: d)
    }

    static let space  = Key(" ", "Space", 32, label: "SPACE")
    static let enter  = Key("Enter", "Enter", 13, label: "⏎")
    static let escape = Key("Escape", "Escape", 27, label: "ESC")
    static let shift  = Key("Shift", "ShiftLeft", 16, label: "⇧")
    static let ctrl   = Key("Control", "ControlLeft", 17, label: "CTRL")
    static let alt    = Key("Alt", "AltLeft", 18, label: "ALT")
    static let tab    = Key("Tab", "Tab", 9, label: "⇥")
    static let backspace = Key("Backspace", "Backspace", 8, label: "⌫")
    static let arrowUp    = Key("ArrowUp", "ArrowUp", 38, label: "▲")
    static let arrowDown  = Key("ArrowDown", "ArrowDown", 40, label: "▼")
    static let arrowLeft  = Key("ArrowLeft", "ArrowLeft", 37, label: "◀")
    static let arrowRight = Key("ArrowRight", "ArrowRight", 39, label: "▶")
}
