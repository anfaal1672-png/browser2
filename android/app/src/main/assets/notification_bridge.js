// Web Notification API polyfill, ported from the iOS app's InputBridge.swift
// `NotificationShim` (window.Notification is unavailable in WKWebView, same
// as in Android's WebView). Intercepts `new Notification(title, {body})` and
// `Notification.requestPermission()` and forwards them to native code, which
// shows a real OS notification / triggers the system permission prompt
// instead (see NotificationBridge.kt and BrowserViewModel's
// postWebNotification/requestNotificationPermission).
//
// Kept in its own bridge object/file (window.__gbNotify), same as
// autofill_bridge.js, so it can be injected independently of input_bridge.js
// without any race on window.__gb's own setup. Messages are posted the same
// way input_bridge.js does — window.AndroidBridge.postMessage(JSON.stringify(obj))
// — so both scripts' messages land in the same JsBridge.postMessage handler,
// which dispatches on the `type` field ('notification' / 'notificationPermission').
(function () {
    if (window.__gbNotify) { return; }

    function post(obj) {
        try { window.AndroidBridge.postMessage(JSON.stringify(obj)); } catch (e) {}
    }

    const NotificationShim = function (title, options) {
        options = options || {};
        this.title = title;
        this.body = options.body || '';
        post({
            type: 'notification',
            title: String(title),
            body: String(options.body || ''),
        });
        // No real notification handle to back these with; match the iOS
        // shim's no-op/never-firing surface so callers that touch them
        // (rather than just constructing and forgetting) don't throw.
        this.close = function () {};
        this.onclick = null;
        this.onshow = null;
        this.onerror = null;
        this.onclose = null;
    };

    // Reported as already granted, matching the iOS shim: native code is the
    // real gatekeeper (the app's webNotificationsEnabled setting plus the
    // actual OS permission), so a page checking `Notification.permission`
    // before calling `new Notification(...)` isn't short-circuited here —
    // it always gets to try, and native code silently no-ops if it
    // shouldn't actually deliver.
    NotificationShim.permission = 'granted';

    NotificationShim.requestPermission = function (callback) {
        post({ type: 'notificationPermission' });
        const result = Promise.resolve('granted');
        if (callback) { result.then(callback); }
        return result;
    };

    window.Notification = NotificationShim;
    window.__gbNotify = true;
})();
