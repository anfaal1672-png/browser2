// Ported from the iOS app's InputBridge.swift. Synthesizes trusted-looking
// pointer/mouse/wheel/keyboard events so desktop-oriented browser games
// respond to the virtual cursor and keys.
//
// Platform gap vs. iOS: WKWebView can inject this same script into every
// frame (including cross-origin iframes) via WKUserScript(forMainFrameOnly:
// false). Android's public WebView API has no equivalent — evaluateJavascript
// only ever runs in the main frame's context, so games embedded in a
// cross-origin <iframe> won't receive synthetic input here. Same-document
// (main-frame) games work fully.
(function () {
    if (window.__gb) { return; }

    const state = {
        x: 0, y: 0,
        buttons: 0,
        lastTarget: null,
        lastDownTarget: null,
        captured: null,
        dnd: null,
        suppressKeyboard: false,
        compLen: 0,
        compTarget: null,   // element compLen applies to
        styleTarget: null,  // element the cursor style was last sampled from
        styleAt: 0,         // when that sample was taken (ms)
    };

    function toPage(x, y) {
        const v = window.visualViewport;
        if (!v) { return { x: x, y: y }; }
        return { x: x / v.scale + v.offsetLeft, y: y / v.scale + v.offsetTop };
    }

    function scaleFactor() {
        return window.visualViewport ? window.visualViewport.scale : 1;
    }

    function targetAt(x, y) {
        let el = document.elementFromPoint(x, y);
        while (el && el.shadowRoot) {
            const inner = el.shadowRoot.elementFromPoint(x, y);
            if (!inner || inner === el) { break; }
            el = inner;
        }
        return el || document.documentElement;
    }

    function post(obj) {
        try { window.AndroidBridge.postMessage(JSON.stringify(obj)); } catch (e) {}
    }

    function common(x, y, extra) {
        return Object.assign({
            bubbles: true, cancelable: true, composed: true, view: window,
            clientX: x, clientY: y,
            screenX: x + (window.screenX || 0), screenY: y + (window.screenY || 0),
            buttons: state.buttons,
        }, extra || {});
    }

    function firePointer(type, target, x, y, button, extra) {
        try {
            target.dispatchEvent(new PointerEvent(type, common(x, y, Object.assign({
                pointerId: 1, pointerType: 'mouse', isPrimary: true, button: button,
                width: 1, height: 1, pressure: state.buttons ? 0.5 : 0,
            }, extra))));
        } catch (e) {}
    }

    function fireMouse(type, target, x, y, button, extra) {
        target.dispatchEvent(new MouseEvent(type, common(x, y, Object.assign({
            button: button, detail: (type === 'dblclick') ? 2 : 1,
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

    try {
        const proto = Element.prototype;
        const origSet = proto.setPointerCapture;
        const origRel = proto.releasePointerCapture;
        const origHas = proto.hasPointerCapture;
        proto.setPointerCapture = function (id) {
            if (id === 1) {
                if (state.buttons) {
                    state.captured = this;
                    try { origSet.call(this, id); } catch (e) {}
                }
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

    function fireDrag(type, target, x, y, dt) {
        try {
            const ev = new DragEvent(type, common(x, y, { dataTransfer: dt }));
            return !target.dispatchEvent(ev);
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

    function placeCaret(el, clientX, clientY) {
        try {
            if (el.isContentEditable) {
                const range = document.caretRangeFromPoint(clientX, clientY);
                if (range) {
                    const sel = window.getSelection();
                    sel.removeAllRanges();
                    sel.addRange(range);
                }
                return;
            }
            if (el.tagName !== 'INPUT' && el.tagName !== 'TEXTAREA') { return; }

            const style = getComputedStyle(el);
            const canvas = state.measureCanvas || (state.measureCanvas = document.createElement('canvas'));
            const ctx = canvas.getContext('2d');
            ctx.font = style.fontWeight + ' ' + style.fontSize + ' ' + style.fontFamily;

            const rect = el.getBoundingClientRect();
            const x = clientX - rect.left - parseFloat(style.paddingLeft || 0)
                - parseFloat(style.borderLeftWidth || 0) + el.scrollLeft;

            let text = el.value;
            let lineStart = 0;
            if (el.tagName === 'TEXTAREA') {
                let lineHeight = parseFloat(style.lineHeight);
                if (!lineHeight || isNaN(lineHeight)) { lineHeight = parseFloat(style.fontSize) * 1.2; }
                const y = clientY - rect.top - parseFloat(style.paddingTop || 0)
                    - parseFloat(style.borderTopWidth || 0) + el.scrollTop;
                const lines = el.value.split('\n');
                const row = Math.max(0, Math.min(lines.length - 1, Math.floor(y / lineHeight)));
                for (let r = 0; r < row; r++) { lineStart += lines[r].length + 1; }
                text = lines[row];
            }

            let best = 0, bestDist = Infinity;
            for (let i = 0; i <= text.length; i++) {
                const dist = Math.abs(ctx.measureText(text.slice(0, i)).width - x);
                if (dist < bestDist) { bestDist = dist; best = i; }
            }
            el.setSelectionRange(lineStart + best, lineStart + best);
        } catch (e) {}
    }

    function moveCaret(el, key, shift) {
        const len = el.value.length;
        let start = el.selectionStart, end = el.selectionEnd;
        if (start === null) { return; }
        const delta = (key === 'ArrowLeft') ? -1 : 1;
        if (shift) {
            el.setSelectionRange(start, Math.max(start, Math.min(len, end + delta)));
        } else if (start !== end) {
            const pos = delta < 0 ? start : end;
            el.setSelectionRange(pos, pos);
        } else {
            const pos = Math.max(0, Math.min(len, start + delta));
            el.setSelectionRange(pos, pos);
        }
    }

    // Nearest scrollable ancestor, so wheel scroll acts on an in-page panel
    // (menu, chat log, inventory) instead of always moving the whole page.
    function scrollableAncestor(el, horizontal) {
        let node = el;
        while (node && node !== document.body && node !== document.documentElement) {
            const style = getComputedStyle(node);
            const overflow = horizontal ? style.overflowX : style.overflowY;
            const scrollable = overflow === 'auto' || overflow === 'scroll';
            const hasRoom = horizontal
                ? node.scrollWidth > node.clientWidth
                : node.scrollHeight > node.clientHeight;
            if (scrollable && hasRoom) { return node; }
            node = node.parentElement;
        }
        return null;
    }

    const bridge = {
        move: function (x, y, dx, dy) {
            const p = toPage(x, y); x = p.x; y = p.y;
            const s = scaleFactor();
            dx = (dx || 0) / s; dy = (dy || 0) / s;
            state.x = x; state.y = y;
            const locked = document.pointerLockElement;
            const target = locked || state.captured || targetAt(x, y);
            const extra = { movementX: dx || 0, movementY: dy || 0 };
            if (!locked && !state.captured) { hoverTransition(target, x, y); }
            firePointer('pointermove', target, x, y, -1, extra);
            fireMouse('mousemove', target, x, y, 0, extra);
            updateDnd(x, y);
            // getComputedStyle forces a style recalc and this runs on every
            // pointer move (one per touch sample), so sample it when the
            // hovered element changes and at most every 100ms otherwise.
            const now = (window.performance && performance.now)
                ? performance.now() : Date.now();
            if (target !== state.styleTarget || now - state.styleAt > 100) {
                state.styleTarget = target;
                state.styleAt = now;
                post({ type: 'cursorstyle', style: getComputedStyle(target).cursor || 'auto' });
            }
        },

        down: function (x, y, button) {
            const p = toPage(x, y); x = p.x; y = p.y;
            state.x = x; state.y = y;
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
                    if (state.suppressKeyboard &&
                        (tag === 'input' || tag === 'textarea' || target.isContentEditable)) {
                        target.setAttribute('inputmode', 'none');
                        target.setAttribute('data-gb-imode', '1');
                    }
                    target.focus({ preventScroll: true });
                    placeCaret(target, x, y);
                }
            }
        },

        up: function (x, y, button, clickCount) {
            const p = toPage(x, y); x = p.x; y = p.y;
            state.x = x; state.y = y;
            state.buttons &= ~(button === 2 ? 2 : button === 1 ? 4 : 1);

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
            const ev = new WheelEvent('wheel', common(x, y, { deltaX: dx, deltaY: dy, deltaMode: 0 }));
            const notCancelled = target.dispatchEvent(ev);
            if (notCancelled) {
                const vScroll = dy && scrollableAncestor(target, false);
                const hScroll = dx && scrollableAncestor(target, true);
                if (vScroll) { vScroll.scrollTop += dy; }
                if (hScroll) { hScroll.scrollLeft += dx; }
                if (!vScroll && !hScroll) { window.scrollBy(dx, dy); }
            }
        },

        key: function (type, key, code, keyCode, mods) {
            mods = mods || {};
            if (type === 'keydown' && key === 'Escape' && document.pointerLockElement) {
                try { document.exitPointerLock(); } catch (e) {}
            }
            const target = document.activeElement || document.body;
            const ev = new KeyboardEvent(type, {
                bubbles: true, cancelable: true, composed: true, view: window,
                key: key, code: code,
                shiftKey: !!mods.shift, ctrlKey: !!mods.ctrl,
                altKey: !!mods.alt, metaKey: !!mods.meta, repeat: !!mods.repeat,
            });
            Object.defineProperty(ev, 'keyCode', { get: function () { return keyCode; } });
            Object.defineProperty(ev, 'which', { get: function () { return keyCode; } });
            const notCancelled = target.dispatchEvent(ev);
            // A modifier combo is a shortcut, not typing: Ctrl+A in a game's
            // chat box has to select all, not insert an "a" — easy to hit,
            // since the virtual keyboard's CTRL/ALT are sticky and stay held
            // across the following keypress.
            if (type === 'keydown' && notCancelled && key.length === 1 &&
                !mods.ctrl && !mods.alt && !mods.meta) {
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
            if (type === 'keydown' && notCancelled && (key === 'ArrowLeft' || key === 'ArrowRight')) {
                const el = document.activeElement;
                if (el && (el.tagName === 'INPUT' || el.tagName === 'TEXTAREA')) {
                    moveCaret(el, key, !!mods.shift);
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

        insertText: function (text) {
            const el = document.activeElement;
            if (el && (el.tagName === 'INPUT' || el.tagName === 'TEXTAREA')) {
                const start = el.selectionStart, end = el.selectionEnd;
                if (start !== null) { el.setRangeText(text, start, end, 'end'); } else { el.value += text; }
                el.dispatchEvent(new InputEvent('input', { bubbles: true, data: text, inputType: 'insertText' }));
                el.dispatchEvent(new Event('change', { bubbles: true }));
            } else if (el && el.isContentEditable) {
                document.execCommand('insertText', false, text);
            }
        },

        // Live IME composition: replaces the previous uncommitted text in the
        // focused field with `text` (typed inline, like a real IME). `commit`
        // finalizes it. Ported from InputBridge.swift's setComposition (minus
        // iframe forwarding, which this port doesn't support at all — see the
        // file header).
        setComposition: function (text, commit) {
            const el = document.activeElement;
            if (el && (el.tagName === 'INPUT' || el.tagName === 'TEXTAREA')) {
                let end = el.selectionEnd;
                if (end === null || end === undefined) { end = el.value.length; }
                const start = Math.max(0, end - (state.compLen || 0));
                el.setRangeText(text, start, end, 'end');
                el.dispatchEvent(new InputEvent('input', { bubbles: true, data: text, inputType: 'insertText' }));
                state.compLen = commit ? 0 : text.length;
                if (commit) { el.dispatchEvent(new Event('change', { bubbles: true })); }
            } else if (el && el.isContentEditable) {
                const sel = window.getSelection();
                for (let i = 0; i < (state.compLen || 0); i++) {
                    sel.modify('extend', 'backward', 'character');
                }
                document.execCommand('insertText', false, text);
                state.compLen = commit ? 0 : text.length;
            }
        },

        setSuppressKeyboard: function (on) {
            state.suppressKeyboard = !!on;
            if (!on) {
                document.querySelectorAll('[data-gb-imode]').forEach(function (el) {
                    el.removeAttribute('inputmode');
                    el.removeAttribute('data-gb-imode');
                });
            }
        },

        isPointerLocked: function () { return !!document.pointerLockElement; },
    };

    window.__gb = bridge;

    // IME composition length (state.compLen) is per-element; without this,
    // clicking a different field mid-composition leaves the stale count
    // around, so the next keystroke there deletes/overwrites that field's
    // own existing text instead of starting a fresh composition.
    document.addEventListener('focusin', function (e) {
        if (state.compTarget !== e.target) {
            state.compLen = 0;
            state.compTarget = e.target;
        }
    }, true);

    document.addEventListener('pointerlockchange', function () {
        post({ type: 'pointerlock', locked: !!document.pointerLockElement });
    });
})();
