package com.anfaal.gamebrowser

import android.webkit.WebView
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** A key the virtual keyboard/joystick can send, mirroring InputBridge.Key on iOS. */
data class GbKey(val key: String, val code: String, val keyCode: Int, val label: String) {
    companion object {
        fun letter(c: String) = GbKey(c, "Key${c.uppercase()}", c.uppercase()[0].code, c.uppercase())
        fun digit(d: String) = GbKey(d, "Digit$d", d[0].code, d)

        val space = GbKey(" ", "Space", 32, "SPACE")
        val enter = GbKey("Enter", "Enter", 13, "\u23CE")
        val escape = GbKey("Escape", "Escape", 27, "ESC")
        val shift = GbKey("Shift", "ShiftLeft", 16, "\u21E7")
        val ctrl = GbKey("Control", "ControlLeft", 17, "CTRL")
        val backspace = GbKey("Backspace", "Backspace", 8, "\u232B")
        val arrowUp = GbKey("ArrowUp", "ArrowUp", 38, "\u25B2")
        val arrowDown = GbKey("ArrowDown", "ArrowDown", 40, "\u25BC")
        val arrowLeft = GbKey("ArrowLeft", "ArrowLeft", 37, "\u25C0")
        val arrowRight = GbKey("ArrowRight", "ArrowRight", 39, "\u25B6")
    }
}

/**
 * Central app state: the active WebView, virtual cursor/keyboard state, and
 * the JS-bridge calls that drive them. Mirrors BrowserViewModel.swift's
 * virtual-mouse/keyboard surface; tabs, IME, ad-block etc. are not yet
 * ported (phase 1 covers the core browsing + input experience only).
 */
class BrowserViewModel : ViewModel() {
    var webView: WebView? = null

    var urlText by mutableStateOf("")
    var currentUrl by mutableStateOf<String?>(null)
    var isLoading by mutableStateOf(false)
    var progress by mutableStateOf(0f)
    var canGoBack by mutableStateOf(false)
    var canGoForward by mutableStateOf(false)

    var cursorPosition by mutableStateOf(Pair(200f, 200f))
    var cursorStyle by mutableStateOf("auto")
    var mouseButtonDown by mutableStateOf(false)
    var dragLocked by mutableStateOf(false)
    var pointerLocked by mutableStateOf(false)
    var pageHidesCursor by mutableStateOf(false)

    var cursorMode by mutableStateOf(true)
    var keyboardVisible by mutableStateOf(false)
    var joystickVisible by mutableStateOf(false)

    var cursorSensitivity by mutableStateOf(1.4f)
    var webViewSize by mutableStateOf(Pair(0f, 0f))

    val pressedKeys = mutableSetOf<GbKey>()

    private var lastClickTimeMs = 0L
    private var lastClickPoint = Pair(0f, 0f)

    private fun js(script: String) {
        webView?.evaluateJavascript(script, null)
    }

    private fun clamp(p: Pair<Float, Float>): Pair<Float, Float> {
        val (w, h) = webViewSize
        return Pair(
            min(max(p.first, 0f), max(w - 1f, 0f)),
            min(max(p.second, 0f), max(h - 1f, 0f)),
        )
    }

    private fun f(v: Float) = "%.1f".format(v)

    fun moveCursor(dxRaw: Float, dyRaw: Float) {
        val speed = hypot(dxRaw.toDouble(), dyRaw.toDouble()).toFloat()
        val accel = min(2.2f, 0.7f + speed / 9f)
        val dx = dxRaw * cursorSensitivity * accel
        val dy = dyRaw * cursorSensitivity * accel
        cursorPosition = clamp(Pair(cursorPosition.first + dx, cursorPosition.second + dy))
        js("window.__gb && __gb.move(${f(cursorPosition.first)}, ${f(cursorPosition.second)}, ${f(dx)}, ${f(dy)})")
    }

    fun mouseDown(button: Int = 0) {
        if (button == 0) mouseButtonDown = true
        js("window.__gb && __gb.down(${f(cursorPosition.first)}, ${f(cursorPosition.second)}, $button)")
    }

    fun mouseUp(button: Int = 0) {
        if (button == 0) mouseButtonDown = false
        val now = System.currentTimeMillis()
        val isDouble = button == 0 && (now - lastClickTimeMs) < 350 &&
            hypot(
                (cursorPosition.first - lastClickPoint.first).toDouble(),
                (cursorPosition.second - lastClickPoint.second).toDouble(),
            ) < 12
        if (button == 0) {
            lastClickTimeMs = now
            lastClickPoint = cursorPosition
        }
        js("window.__gb && __gb.up(${f(cursorPosition.first)}, ${f(cursorPosition.second)}, $button, ${if (isDouble) 2 else 1})")
    }

    fun click(button: Int = 0) {
        mouseDown(button)
        mouseUp(button)
    }

    fun toggleDragLock() {
        dragLocked = !dragLocked
        if (dragLocked) mouseDown() else mouseUp()
    }

    fun scroll(dx: Float, dy: Float) {
        js("window.__gb && __gb.wheel(${f(cursorPosition.first)}, ${f(cursorPosition.second)}, ${f(dx)}, ${f(dy)})")
    }

    fun keyDown(key: GbKey) {
        pressedKeys.add(key)
        sendKey("keydown", key)
    }

    fun keyUp(key: GbKey) {
        pressedKeys.remove(key)
        sendKey("keyup", key)
    }

    fun tapKey(key: GbKey) {
        keyDown(key)
        keyUp(key)
    }

    fun repeatKey(key: GbKey) {
        sendKey("keydown", key, repeating = true)
    }

    fun releaseAllKeys() {
        for (key in pressedKeys.toList()) sendKey("keyup", key)
        pressedKeys.clear()
    }

    private fun jsEscape(text: String) =
        text.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n")

    private fun sendKey(type: String, key: GbKey, repeating: Boolean = false) {
        val shift = pressedKeys.contains(GbKey.shift)
        val ctrl = pressedKeys.contains(GbKey.ctrl)
        var keyValue = key.key
        if (shift && keyValue.length == 1 && keyValue[0].isLetter()) {
            keyValue = keyValue.uppercase()
        }
        js(
            "window.__gb && __gb.key('$type', '${jsEscape(keyValue)}', '${key.code}', ${key.keyCode}, " +
                "{shift:$shift, ctrl:$ctrl, alt:false, repeat:$repeating})"
        )
    }

    fun applyKeyboardSuppression() {
        js("window.__gb && __gb.setSuppressKeyboard($cursorMode)")
    }

    fun submitUrl() {
        val text = urlText.trim()
        if (text.isEmpty()) return
        val url = when {
            text.contains("://") -> text
            text.contains(".") && !text.contains(" ") -> "https://$text"
            else -> "https://www.google.com/search?q=" + java.net.URLEncoder.encode(text, "UTF-8")
        }
        webView?.loadUrl(url)
    }

    fun goBack() = webView?.goBack()
    fun goForward() = webView?.goForward()
    fun reload() = webView?.reload()
    fun goHome() = webView?.loadUrl("https://www.google.com")

    fun handleBridgeMessage(type: String, style: String?) {
        when (type) {
            "cursorstyle" -> {
                val s = style ?: "auto"
                if (cursorStyle != s) cursorStyle = s
                val hidden = s == "none"
                if (pageHidesCursor != hidden) pageHidesCursor = hidden
            }
            "pointerlock" -> {
                // handled by caller passing the locked flag separately
            }
        }
    }
}
