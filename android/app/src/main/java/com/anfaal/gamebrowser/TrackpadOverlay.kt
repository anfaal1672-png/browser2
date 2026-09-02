package com.anfaal.gamebrowser

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.hypot

private const val TAP_MAX_DURATION_MS = 300L
private const val TAP_MAX_MOVEMENT = 10f
private const val LONG_PRESS_DELAY_MS = 450L
private const val SCROLL_SPEED = 2.2f

/**
 * How far the fingers must spread (or the pair must travel) before a
 * two-finger gesture commits to being a pinch or a scroll. Classifying once
 * and staying with it is what stops a scroll from drifting into a zoom
 * halfway down a page - the same rule the iOS trackpad uses.
 */
private const val PINCH_SLOP = 24f
private const val PAN_SLOP = 18f

/**
 * Full-area touch surface layered over the WebView when in cursor mode.
 * Ported from TrackpadUIView.swift's "classic" control scheme: 1-finger
 * drag moves the cursor, tap clicks, long-press holds the left button
 * (drag), 2-finger drag scrolls, 2-finger tap right-clicks.
 *
 * Long-press is detected the same way Compose's own detectTapGestures does:
 * `withTimeoutOrNull` around `awaitPointerEvent` inside the gesture's
 * `AwaitPointerEventScope` — a plain `launch`/`coroutineScope` can't call
 * those restricted-suspension functions from a nested coroutine.
 */
@Composable
fun TrackpadOverlay(viewModel: BrowserViewModel, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                awaitEachGesture {
                    var totalMovement = 0f
                    var isDragging = false
                    var isTwoFingerGesture = false
                    var twoFingerMoved = false
                    var startDistance = 0f
                    var lastDistance = 0f
                    var panTravelled = 0f
                    var twoFingerClassified = false
                    var isPinch = false
                    var longPressResolved = false
                    var tapEligible = true

                    awaitFirstDown(requireUnconsumed = false).consume()
                    val touchStartTime = System.currentTimeMillis()

                    while (true) {
                        val event = if (!longPressResolved) {
                            val remaining = LONG_PRESS_DELAY_MS - (System.currentTimeMillis() - touchStartTime)
                            if (remaining <= 0) null else withTimeoutOrNull(remaining) {
                                awaitPointerEvent(PointerEventPass.Main)
                            }
                        } else {
                            awaitPointerEvent(PointerEventPass.Main)
                        }

                        if (event == null) {
                            // Long-press delay elapsed with nothing disqualifying it.
                            longPressResolved = true
                            if (totalMovement <= TAP_MAX_MOVEMENT && !isTwoFingerGesture) {
                                isDragging = true
                                viewModel.mouseDown()
                            }
                            continue
                        }

                        val pressedChanges = event.changes.filter { it.pressed }

                        if (pressedChanges.size >= 2 && !isTwoFingerGesture) {
                            longPressResolved = true
                            isTwoFingerGesture = true
                            twoFingerMoved = false
                            startDistance = 0f
                            panTravelled = 0f
                            twoFingerClassified = false
                            isPinch = false
                        }

                        if (isTwoFingerGesture && pressedChanges.size >= 2) {
                            var dx = 0f
                            var dy = 0f
                            for (change: PointerInputChange in pressedChanges) {
                                val delta = change.positionChange()
                                dx += delta.x
                                dy += delta.y
                                change.consume()
                            }
                            dx /= pressedChanges.size
                            dy /= pressedChanges.size

                            val a = pressedChanges[0].position
                            val b = pressedChanges[1].position
                            val distance = hypot(b.x - a.x, b.y - a.y)
                            if (startDistance <= 0f) {
                                startDistance = distance
                                lastDistance = distance
                            }
                            panTravelled += abs(dx) + abs(dy)
                            val spread = abs(distance - startDistance)

                            // Decide once, then stay decided for the rest of
                            // the gesture: whichever measure crosses its slop
                            // first is what the fingers were doing.
                            if (!twoFingerClassified) {
                                if (spread > PINCH_SLOP) {
                                    twoFingerClassified = true
                                    isPinch = true
                                } else if (panTravelled > PAN_SLOP) {
                                    twoFingerClassified = true
                                }
                            }

                            if (twoFingerClassified) {
                                twoFingerMoved = true
                                if (isPinch) {
                                    if (lastDistance > 0f) {
                                        viewModel.pinchZoom(distance / lastDistance)
                                    }
                                } else {
                                    viewModel.scroll(-dx * SCROLL_SPEED, -dy * SCROLL_SPEED)
                                }
                            }
                            lastDistance = distance
                        } else if (pressedChanges.size == 1) {
                            val change = pressedChanges[0]
                            val delta = change.positionChange()
                            totalMovement += abs(delta.x) + abs(delta.y)
                            if (totalMovement > TAP_MAX_MOVEMENT) {
                                longPressResolved = true
                                tapEligible = false
                            }
                            viewModel.moveCursor(delta.x, delta.y)
                            change.consume()
                        }

                        if (pressedChanges.isEmpty()) {
                            when {
                                isDragging -> {
                                    isDragging = false
                                    viewModel.mouseUp()
                                }
                                isTwoFingerGesture -> {
                                    if (!twoFingerMoved) viewModel.click(button = 2)
                                }
                                tapEligible &&
                                    System.currentTimeMillis() - touchStartTime < TAP_MAX_DURATION_MS -> {
                                    if (viewModel.dragLocked) viewModel.toggleDragLock() else viewModel.click()
                                }
                            }
                            break
                        }

                        // A multi-finger gesture losing fingers before fully ending
                        // (e.g. one of two scrolling fingers lifts) shouldn't let the
                        // remaining finger resolve into a stray tap/click later.
                        if (isTwoFingerGesture && pressedChanges.size < 2) {
                            isTwoFingerGesture = false
                            twoFingerMoved = false
                            tapEligible = false
                            longPressResolved = true
                        }
                    }
                }
            },
    )
}
