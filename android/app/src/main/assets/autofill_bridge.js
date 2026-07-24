// Autofill-related JS ported from the iOS app's InputBridge.swift
// (setNativeValue, usernameFieldFor, findField, fillCredentials, fillCard,
// the focusin 'autofillFocus' listener, the submit 'credentialSubmitted'
// listener).
//
// Kept in its own bridge object (window.__gbAutofill) instead of being merged
// into window.__gb / input_bridge.js, so this file can be injected
// independently of (and in either order relative to) that script without any
// race on window.__gb's own setup. Messages are posted the same way
// input_bridge.js does — window.AndroidBridge.postMessage(JSON.stringify(obj))
// — so both scripts' messages land in the same JsBridge.postMessage handler.
(function () {
    if (window.__gbAutofill) { return; }

    function post(obj) {
        try { window.AndroidBridge.postMessage(JSON.stringify(obj)); } catch (e) {}
    }

    // Set a field value in a way frameworks (React etc.) notice.
    function setNativeValue(el, value) {
        try {
            const proto = el.tagName === 'TEXTAREA'
                ? window.HTMLTextAreaElement.prototype
                : window.HTMLInputElement.prototype;
            const setter = Object.getOwnPropertyDescriptor(proto, 'value').set;
            setter.call(el, value);
        } catch (e) { el.value = value; }
        el.dispatchEvent(new Event('input', { bubbles: true }));
        el.dispatchEvent(new Event('change', { bubbles: true }));
    }

    function usernameFieldFor(pwField) {
        const form = pwField.form || document;
        const fields = Array.prototype.slice.call(
            form.querySelectorAll('input[type="text"], input[type="email"], input:not([type])'));
        // Last text/email field appearing before the password field.
        let best = null;
        for (const f of fields) {
            if (pwField.compareDocumentPosition(f) & Node.DOCUMENT_POSITION_PRECEDING) {
                best = f;
            }
        }
        return best || fields[0] || null;
    }

    function findField(autocompletes, nameRegex) {
        for (const ac of autocompletes) {
            const el = document.querySelector('input[autocomplete="' + ac + '"]');
            if (el) { return el; }
        }
        const inputs = document.querySelectorAll('input, select');
        for (const el of inputs) {
            const hint = (el.name || '') + ' ' + (el.id || '') + ' ' + (el.placeholder || '');
            if (nameRegex.test(hint)) { return el; }
        }
        return null;
    }

    const bridge = {
        // Fill username/password into the current login form.
        fillCredentials: function (username, password) {
            const pw = document.querySelector('input[type="password"]');
            if (!pw) { return; }
            setNativeValue(pw, password);
            const user = usernameFieldFor(pw);
            if (user && username) { setNativeValue(user, username); }
        },

        fillCard: function (number, holder, month, year) {
            const num = findField(['cc-number'], /card.?number|cardnum|ccnum|creditcard/i);
            if (num) { setNativeValue(num, number); }
            const name = findField(['cc-name'], /card.?holder|card.?name|ccname/i);
            if (name && holder) { setNativeValue(name, holder); }
            const mm = findField(['cc-exp-month'], /exp.*month|month|mm/i);
            if (mm) { setNativeValue(mm, month); }
            const yy = findField(['cc-exp-year'], /exp.*year|year|yy/i);
            if (yy) { setNativeValue(yy, year); }
        },
    };

    window.__gbAutofill = bridge;

    // Tell native code when a password or card field gains focus.
    document.addEventListener('focusin', function (e) {
        const el = e.target;
        if (!el || el.tagName !== 'INPUT') { return; }
        let kind = null;
        if (el.type === 'password') { kind = 'password'; }
        else {
            const hint = (el.autocomplete || '') + ' ' + (el.name || '') + ' ' + (el.id || '');
            if (/cc-number|card.?number|ccnum|creditcard/i.test(hint)) { kind = 'card'; }
        }
        if (kind) {
            post({ type: 'autofillFocus', kind: kind });
        }
    }, true);

    // Offer to save credentials when a login form is submitted.
    document.addEventListener('submit', function (e) {
        try {
            const form = e.target;
            const pw = form.querySelector('input[type="password"]');
            if (!pw || !pw.value) { return; }
            const user = usernameFieldFor(pw);
            post({
                type: 'credentialSubmitted',
                username: user ? user.value : '',
                password: pw.value,
            });
        } catch (err) {}
    }, true);
})();
