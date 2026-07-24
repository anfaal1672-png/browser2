package com.anfaal.gamebrowser

import android.content.Context
import android.hardware.input.InputManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import kotlin.math.abs

/**
 * Maps a physical game controller to the virtual input bridge, mirroring
 * GamepadController.swift:
 *  - left stick / d-pad  -> WASD held keys
 *  - right stick         -> cursor movement
 *  - A -> Space, B -> Shift, X -> E, Y -> Q, L1 -> R, R1 -> F, Start -> Escape
 *  - R2 -> left mouse button (hold to drag), L2 -> right click (on press)
 *
 * iOS discovers controllers through the GameController framework and polls
 * them on a CADisplayLink. Android has no framework-level controller object:
 * a physical pad instead shows up as a generic [InputDevice] delivering
 * [KeyEvent]s (buttons/d-pad) and [MotionEvent]s (analog sticks/triggers,
 * source [InputDevice.SOURCE_JOYSTICK]) through the activity's normal
 * dispatch pipeline. So this class exposes [handleKeyEvent] and
 * [handleGenericMotionEvent] for an Activity to forward events into (see
 * MainActivity's dispatchKeyEvent/dispatchGenericMotionEvent), and since
 * there's no CADisplayLink to keep polling stick deflection while it's held
 * (Android does not guarantee a steady stream of MotionEvents for a stick
 * pinned at a constant position), it runs its own small poll loop for
 * continuous cursor movement, scaled by real elapsed time between ticks --
 * the same spirit as the frame-duration scaling fix in GamepadController.tick().
 *
 * Axis-direction note: iOS's GameController framework reports stick-up as
 * +1 (so GamepadController.swift negates ry before feeding the screen-space
 * cursor). Android's convention is the opposite and already screen-aligned:
 * for both AXIS_Y (left stick) and AXIS_RZ (right stick), -1 means up/left
 * and +1 means down/right (see the "Game controllers" section of the Android
 * input docs), so no negation is needed here.
 */
class GamepadInput(
    private val viewModel: BrowserViewModel,
    context: Context,
) {
    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val density = appContext.resources.displayMetrics.density
    private val inputManager = appContext.getSystemService(Context.INPUT_SERVICE) as? InputManager

    // --- held-key bookkeeping -------------------------------------------------
    private var movementKeysHeld: Set<GbKey> = emptySet()
    private val buttonKeysHeld = mutableSetOf<GbKey>()

    // --- analog stick / hat / trigger state, refreshed by MotionEvents --------
    private var leftStickX = 0f
    private var leftStickY = 0f
    private var rightStickX = 0f
    private var rightStickY = 0f
    private var hatX = 0f
    private var hatY = 0f
    private var analogLeftTrigger = 0f
    private var analogRightTrigger = 0f

    // --- digital fallback state (some pads expose d-pad/triggers as buttons) --
    private var dpadUpHeld = false
    private var dpadDownHeld = false
    private var dpadLeftHeld = false
    private var dpadRightHeld = false
    private var leftTriggerDigitalHeld = false
    private var rightTriggerDigitalHeld = false

    // --- trigger edge-detection + drag state -----------------------------------
    private var leftMouseHeld = false
    private var rightTriggerWasPressed = false
    private var leftTriggerWasPressed = false

    // --- cursor polling loop ---------------------------------------------------
    private var pollingActive = false
    private var lastTickNanos = 0L

    private val cursorTick = object : Runnable {
        override fun run() {
            val now = SystemClock.elapsedRealtimeNanos()
            val dtNanos = if (lastTickNanos == 0L) NOMINAL_TICK_NANOS else (now - lastTickNanos)
            lastTickNanos = now

            if (abs(rightStickX) > CURSOR_DEADZONE || abs(rightStickY) > CURSOR_DEADZONE) {
                // Normalize elapsed real time to the ~60Hz baseline the speed
                // constant was tuned for, so a delayed/late-firing tick (or a
                // device that batches input differently) still moves the
                // cursor by the "right" amount instead of jumping or crawling.
                val frameScale = dtNanos.toFloat() / NOMINAL_TICK_NANOS.toFloat()
                val speedPx = CURSOR_SPEED_DP * density
                viewModel.moveCursor(
                    rightStickX * speedPx * frameScale,
                    rightStickY * speedPx * frameScale,
                )
                handler.postDelayed(this, TICK_MS)
            } else {
                pollingActive = false
                lastTickNanos = 0L
            }
        }
    }

    private fun ensurePolling() {
        if (pollingActive) return
        if (abs(rightStickX) > CURSOR_DEADZONE || abs(rightStickY) > CURSOR_DEADZONE) {
            pollingActive = true
            lastTickNanos = 0L
            handler.post(cursorTick)
        }
    }

    // --- disconnect handling -----------------------------------------------------

    private val deviceListener = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(deviceId: Int) {}

        override fun onInputDeviceRemoved(deviceId: Int) {
            // A device that just vanished can still show up in
            // getInputDevice() for the removed id, so re-scan everything
            // rather than special-casing deviceId.
            if (!hasAnyGamepadConnected()) releaseEverything()
        }

        override fun onInputDeviceChanged(deviceId: Int) {}
    }

    init {
        inputManager?.registerInputDeviceListener(deviceListener, handler)
    }

    private fun hasAnyGamepadConnected(): Boolean {
        val im = inputManager ?: return false
        return im.inputDeviceIds.any { id ->
            val device = im.getInputDevice(id)
            device != null && !device.isVirtual && isGamepadSource(device.sources)
        }
    }

    private fun isGamepadSource(sources: Int): Boolean {
        return (sources and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
            (sources and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
    }

    /** Releases all keys/mouse state and resets edge-detection flags. Call on
     * disconnect (handled automatically above) and it's safe to call again
     * from onPause/onDestroy for extra safety.
     *
     * Resetting the trigger flags is the important part, mirroring the fix in
     * GamepadController.releaseEverything(): without it, a trigger that's
     * still physically held across a disconnect/reconnect (e.g. a momentary
     * Bluetooth drop) would read as "unchanged" on the next event and never
     * fire mouseDown()/click() again until the user fully releases and
     * re-presses it.
     */
    fun releaseEverything() {
        for (key in movementKeysHeld) viewModel.keyUp(key)
        movementKeysHeld = emptySet()

        for (key in buttonKeysHeld) viewModel.keyUp(key)
        buttonKeysHeld.clear()

        if (leftMouseHeld) {
            viewModel.mouseUp()
            leftMouseHeld = false
        }

        rightTriggerWasPressed = false
        leftTriggerWasPressed = false
        leftTriggerDigitalHeld = false
        rightTriggerDigitalHeld = false
        analogLeftTrigger = 0f
        analogRightTrigger = 0f

        leftStickX = 0f
        leftStickY = 0f
        rightStickX = 0f
        rightStickY = 0f
        hatX = 0f
        hatY = 0f
        dpadUpHeld = false
        dpadDownHeld = false
        dpadLeftHeld = false
        dpadRightHeld = false

        handler.removeCallbacks(cursorTick)
        pollingActive = false
        lastTickNanos = 0L
    }

    /** Unregisters the device listener and stops the poll loop. Call from the hosting Activity's onDestroy. */
    fun dispose() {
        inputManager?.unregisterInputDeviceListener(deviceListener)
        handler.removeCallbacks(cursorTick)
    }

    // --- key events (buttons + digital d-pad) -------------------------------------

    fun handleKeyEvent(event: KeyEvent): Boolean {
        val down = when (event.action) {
            KeyEvent.ACTION_DOWN -> true
            KeyEvent.ACTION_UP -> false
            else -> return false
        }

        // KEYCODE_DPAD_* is shared with plain keyboards/remotes, so only
        // treat it as a gamepad d-pad when it actually came from a
        // gamepad/joystick-class device -- otherwise we'd hijack arrow-key
        // navigation from a connected keyboard.
        val fromGamepad = isGamepadSource((event.device ?: InputDevice.getDevice(event.deviceId))?.sources ?: 0)

        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (!fromGamepad) return false
                dpadUpHeld = down
                recomputeMovementKeys()
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (!fromGamepad) return false
                dpadDownHeld = down
                recomputeMovementKeys()
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (!fromGamepad) return false
                dpadLeftHeld = down
                recomputeMovementKeys()
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (!fromGamepad) return false
                dpadRightHeld = down
                recomputeMovementKeys()
            }
            KeyEvent.KEYCODE_BUTTON_L2 -> {
                leftTriggerDigitalHeld = down
                updateTriggers()
            }
            KeyEvent.KEYCODE_BUTTON_R2 -> {
                rightTriggerDigitalHeld = down
                updateTriggers()
            }
            KeyEvent.KEYCODE_BUTTON_A -> setButtonKey(GbKey.space, down, event.repeatCount)
            KeyEvent.KEYCODE_BUTTON_B -> setButtonKey(GbKey.shift, down, event.repeatCount)
            KeyEvent.KEYCODE_BUTTON_X -> setButtonKey(GbKey.letter("e"), down, event.repeatCount)
            KeyEvent.KEYCODE_BUTTON_Y -> setButtonKey(GbKey.letter("q"), down, event.repeatCount)
            KeyEvent.KEYCODE_BUTTON_L1 -> setButtonKey(GbKey.letter("r"), down, event.repeatCount)
            KeyEvent.KEYCODE_BUTTON_R1 -> setButtonKey(GbKey.letter("f"), down, event.repeatCount)
            KeyEvent.KEYCODE_BUTTON_START -> setButtonKey(GbKey.escape, down, event.repeatCount)
            else -> return false
        }
        return true
    }

    /** ACTION_DOWN/UP -> keyDown/keyUp, de-duplicated against OS auto-repeat and stray duplicate events. */
    private fun setButtonKey(key: GbKey, down: Boolean, repeatCount: Int) {
        if (down) {
            if (repeatCount == 0 && buttonKeysHeld.add(key)) viewModel.keyDown(key)
        } else {
            if (buttonKeysHeld.remove(key)) viewModel.keyUp(key)
        }
    }

    // --- generic motion events (analog sticks, hat switch, analog triggers) ------

    fun handleGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_MOVE) return false
        val sources = event.device?.sources ?: 0
        if ((sources and InputDevice.SOURCE_JOYSTICK) != InputDevice.SOURCE_JOYSTICK) return false

        leftStickX = event.getAxisValue(MotionEvent.AXIS_X)
        leftStickY = event.getAxisValue(MotionEvent.AXIS_Y)
        rightStickX = event.getAxisValue(MotionEvent.AXIS_Z)
        rightStickY = event.getAxisValue(MotionEvent.AXIS_RZ)
        hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X)
        hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y)

        // Different controllers surface L2/R2 on different axes (standard
        // gamepads use AXIS_LTRIGGER/AXIS_RTRIGGER; some, notably several
        // Xbox-layout pads, report them on the legacy AXIS_BRAKE/AXIS_GAS
        // racing-wheel axes instead) -- read both and take whichever is live.
        analogLeftTrigger = maxOf(
            event.getAxisValue(MotionEvent.AXIS_LTRIGGER),
            event.getAxisValue(MotionEvent.AXIS_BRAKE),
        )
        analogRightTrigger = maxOf(
            event.getAxisValue(MotionEvent.AXIS_RTRIGGER),
            event.getAxisValue(MotionEvent.AXIS_GAS),
        )

        recomputeMovementKeys()
        updateTriggers()
        ensurePolling()
        return true
    }

    // --- shared derivation helpers ------------------------------------------------

    private fun recomputeMovementKeys() {
        val wanted = mutableSetOf<GbKey>()
        if (leftStickY < -STICK_THRESHOLD || hatY < -HAT_THRESHOLD || dpadUpHeld) wanted.add(GbKey.letter("w"))
        if (leftStickY > STICK_THRESHOLD || hatY > HAT_THRESHOLD || dpadDownHeld) wanted.add(GbKey.letter("s"))
        if (leftStickX < -STICK_THRESHOLD || hatX < -HAT_THRESHOLD || dpadLeftHeld) wanted.add(GbKey.letter("a"))
        if (leftStickX > STICK_THRESHOLD || hatX > HAT_THRESHOLD || dpadRightHeld) wanted.add(GbKey.letter("d"))

        for (key in movementKeysHeld) if (key !in wanted) viewModel.keyUp(key)
        for (key in wanted) if (key !in movementKeysHeld) viewModel.keyDown(key)
        movementKeysHeld = wanted
    }

    private fun updateTriggers() {
        val rightValue = maxOf(analogRightTrigger, if (rightTriggerDigitalHeld) 1f else 0f)
        val leftValue = maxOf(analogLeftTrigger, if (leftTriggerDigitalHeld) 1f else 0f)

        // R2: hold = left mouse button held (aim/drag), release = mouse up.
        val rightPressed = rightValue > TRIGGER_PRESS_THRESHOLD
        if (rightPressed != rightTriggerWasPressed) {
            rightTriggerWasPressed = rightPressed
            if (rightPressed) {
                viewModel.mouseDown()
                leftMouseHeld = true
            } else if (leftMouseHeld) {
                viewModel.mouseUp()
                leftMouseHeld = false
            }
        }

        // L2: right click on press.
        val leftPressed = leftValue > TRIGGER_PRESS_THRESHOLD
        if (leftPressed != leftTriggerWasPressed) {
            leftTriggerWasPressed = leftPressed
            if (leftPressed) viewModel.click(button = 2)
        }
    }

    private companion object {
        const val STICK_THRESHOLD = 0.35f
        const val HAT_THRESHOLD = 0.5f
        const val CURSOR_DEADZONE = 0.12f
        const val TRIGGER_PRESS_THRESHOLD = 0.4f
        const val CURSOR_SPEED_DP = 11f
        const val TICK_MS = 16L
        const val NOMINAL_TICK_NANOS = 1_000_000_000L / 60L
    }
}
