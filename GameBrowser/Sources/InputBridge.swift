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
            captured: null,   // element holding pointer capture for our pointerId
            dnd: null,        // in-progress emulated HTML5 drag-and-drop
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

        // Pointer-capture support: many drag implementations call
        // setPointerCapture and expect subsequent pointer events to be
        // retargeted to the capturing element. Emulate that for our pointer.
        try {
            const proto = Element.prototype;
            const origSet = proto.setPointerCapture;
            const origRel = proto.releasePointerCapture;
            const origHas = proto.hasPointerCapture;
            proto.setPointerCapture = function (id) {
                if (id === 1) {
                    state.captured = this;
                    try { origSet.call(this, id); } catch (e) {}
                    return;
                }
                return origSet.call(this, id);
            };
            proto.releasePointerCapture = function (id) {
                if (id === 1) {
                    state.captured = null;
                    try { origRel.call(this, id); } catch (e) {}
                    return;
                }
                return origRel.call(this, id);
            };
            proto.hasPointerCapture = function (id) {
                if (id === 1) { return state.captured === this; }
                return origHas.call(this, id);
            };
        } catch (e) {}

        // Emulated HTML5 drag-and-drop: synthetic mouse events never trigger
        // the browser's native DnD, so fire drag events ourselves.
        function fireDrag(type, target, x, y, dt) {
            try {
                const ev = new DragEvent(type, common(x, y, { dataTransfer: dt }));
                return !target.dispatchEvent(ev);   // true if preventDefault'ed
            } catch (e) {
                try {
                    const ev = document.createEvent('Event');
                    ev.initEvent(type, true, true);
                    ev.dataTransfer = dt;
                    ev.clientX = x; ev.clientY = y;
                    return !target.dispatchEvent(ev);
                } catch (e2) { return false; }
            }
        }

        function updateDnd(x, y) {
            if (!(state.buttons & 1)) { return; }
            if (!state.dnd && state.lastDownTarget && state.lastDownTarget.closest) {
                const src = state.lastDownTarget.closest('[draggable="true"]');
                if (src) {
                    let dt = null;
                    try { dt = new DataTransfer(); } catch (e) {}
                    state.dnd = { src: src, dt: dt, over: null, canDrop: false };
                    fireDrag('dragstart', src, x, y, dt);
                }
            }
            if (state.dnd) {
                const over = targetAt(x, y);
                if (over !== state.dnd.over) {
                    if (state.dnd.over) { fireDrag('dragleave', state.dnd.over, x, y, state.dnd.dt); }
                    fireDrag('dragenter', over, x, y, state.dnd.dt);
                    state.dnd.over = over;
                }
                state.dnd.canDrop = fireDrag('dragover', over, x, y, state.dnd.dt);
            }
        }

        const bridge = {
            move: function (x, y, dx, dy) {
                const p = toPage(x, y); x = p.x; y = p.y;
                const s = scaleFactor();
                dx = (dx || 0) / s; dy = (dy || 0) / s;
                state.x = x; state.y = y;
                const locked = document.pointerLockElement;
                const target = locked || state.captured || targetAt(x, y);
                // Keep drags anchored to the outer document: only hand the move to
                // an iframe when no button is held in this frame.
                if (!locked && !state.captured && !state.buttons && isFrame(target)) {
                    forward(target, 'move', x, y, [dx, dy]);
                    return;
                }
                const extra = { movementX: dx || 0, movementY: dy || 0 };
                if (!locked && !state.captured) { hoverTransition(target, x, y); }
                firePointer('pointermove', target, x, y, -1, extra);
                fireMouse('mousemove', target, x, y, 0, extra);
                updateDnd(x, y);
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

                // Finish an emulated HTML5 drag-and-drop instead of clicking.
                if (button === 0 && state.dnd) {
                    const dropTarget = targetAt(x, y);
                    if (state.dnd.canDrop) { fireDrag('drop', dropTarget, x, y, state.dnd.dt); }
                    fireDrag('dragend', state.dnd.src, x, y, state.dnd.dt);
                    state.dnd = null;
                    state.captured = null;
                    return;
                }

                const locked = document.pointerLockElement;
                const captured = state.captured;
                const target = locked || captured || targetAt(x, y);
                firePointer('pointerup', target, x, y, button);
                fireMouse('mouseup', target, x, y, button);
                if (captured && button === 0) {
                    // Implicit release at pointerup, per the pointer events spec.
                    state.captured = null;
                    firePointer('lostpointercapture', captured, x, y, -1);
                }
                if (button === 0 && (state.lastDownTarget === target || locked || captured)) {
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
                // Route keys to the frame the user last clicked in; before any
                // click, fall back to the frame under the cursor so iframe
                // games receive keys without needing a click first.
                let kf = state.keyFrame;
                if (!(kf && kf.isConnected)) {
                    const hovered = targetAt(state.x, state.y);
                    kf = isFrame(hovered) ? hovered : null;
                }
                if (kf) {
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
