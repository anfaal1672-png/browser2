package com.anfaal.gamebrowser

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BackHand
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * The shapes the virtual cursor can take, mirroring CursorView.swift's
 * private `Shape` enum. Unlike SF Symbols (a single system icon font with a
 * consistent optical weight), Android's closest built-in equivalent is
 * Material Icons (`material-icons-extended`), so each case below carries a
 * concrete [ImageVector] rather than a symbol name string.
 */
sealed class CursorShape {
    /** Default / auto: the classic arrow pointer, hotspot at its top-left tip. */
    data object Arrow : CursorShape()

    /** CSS `cursor: pointer` (links/buttons). Hotspot near the fingertip, like the desktop link cursor. */
    data class PointerHand(val icon: ImageVector = Icons.Filled.TouchApp) : CursorShape()

    /** A centered, optionally-rotated Material icon standing in for an SF Symbol glyph. */
    data class Symbol(val icon: ImageVector, val rotationDegrees: Float = 0f) : CursorShape()
}

/**
 * Maps a CSS `cursor` keyword (as reported by the JS bridge's
 * `getComputedStyle(target).cursor`, surfaced on `viewModel.cursorStyle`) to
 * a [CursorShape]. Ported case-for-case from CursorView.swift's `shape`
 * computed property; the SF Symbol -> Material Icon choices are:
 *
 *  - text.cursor                               -> TextFields
 *  - hourglass                                 -> HourglassEmpty
 *  - plus                                      -> Add
 *  - arrow.up.and.down.and.arrow.left.and.right -> OpenWith
 *  - hand.raised / hand.raised.fill            -> PanTool / BackHand
 *  - circle.slash                              -> Block
 *  - questionmark.circle                       -> HelpOutline
 *  - plus.magnifyingglass / minus.magnifyingglass -> ZoomIn / ZoomOut
 *  - arrow.up.and.down                         -> SwapVert (plain, or rotated +-45 deg)
 *  - arrow.left.and.right                      -> SwapHoriz
 *  - plus.square.on.square                     -> ContentCopy
 *  - arrow.up.right.square                     -> OpenInNew
 *  - list.bullet.rectangle                     -> Menu
 *  - hand.point.up.left.fill (pointer)         -> TouchApp
 */
fun cursorShapeFor(style: String): CursorShape = when (style) {
    "pointer" -> CursorShape.PointerHand()
    "text", "vertical-text" -> CursorShape.Symbol(Icons.Filled.TextFields)
    "wait", "progress" -> CursorShape.Symbol(Icons.Filled.HourglassEmpty)
    "crosshair", "cell" -> CursorShape.Symbol(Icons.Filled.Add)
    "move", "all-scroll" -> CursorShape.Symbol(Icons.Filled.OpenWith)
    "grab" -> CursorShape.Symbol(Icons.Filled.PanTool)
    "grabbing" -> CursorShape.Symbol(Icons.Filled.BackHand)
    "not-allowed", "no-drop" -> CursorShape.Symbol(Icons.Filled.Block)
    "help" -> CursorShape.Symbol(Icons.Filled.HelpOutline)
    "zoom-in" -> CursorShape.Symbol(Icons.Filled.ZoomIn)
    "zoom-out" -> CursorShape.Symbol(Icons.Filled.ZoomOut)
    "ns-resize", "n-resize", "s-resize", "row-resize" -> CursorShape.Symbol(Icons.Filled.SwapVert)
    "ew-resize", "e-resize", "w-resize", "col-resize" -> CursorShape.Symbol(Icons.Filled.SwapHoriz)
    "nwse-resize", "nw-resize", "se-resize" -> CursorShape.Symbol(Icons.Filled.SwapVert, rotationDegrees = 45f)
    "nesw-resize", "ne-resize", "sw-resize" -> CursorShape.Symbol(Icons.Filled.SwapVert, rotationDegrees = -45f)
    "copy" -> CursorShape.Symbol(Icons.Filled.ContentCopy)
    "alias" -> CursorShape.Symbol(Icons.Filled.OpenInNew)
    "context-menu" -> CursorShape.Symbol(Icons.Filled.Menu)
    else -> CursorShape.Arrow
}

/**
 * Draws [shape] at [position] (raw pixel coordinates, matching
 * `viewModel.cursorPosition` / `viewModel.webViewSize`), shrinking slightly
 * while [pressed] -- the same idea as CursorView.swift's `.scaleEffect` /
 * `.animation(.easeOut(duration: 0.08), value: pressed)`, minus the implicit
 * animation (CursorOverlay already redraws every position change as a plain
 * state update, so an explicit animation isn't wired here; add one with
 * `animateFloatAsState` on the scale factor if that polish is wanted later).
 *
 * This is the drop-in replacement for the single-arrow `Canvas` block
 * currently inlined in CursorOverlay.kt -- see that file for how to call it.
 */
@Composable
fun CursorShapesRenderer(
    shape: CursorShape,
    position: Offset,
    pressed: Boolean,
    modifier: Modifier = Modifier,
) {
    when (shape) {
        is CursorShape.Arrow -> ArrowCursor(position = position, pressed = pressed, modifier = modifier)

        is CursorShape.PointerHand -> SymbolCursor(
            icon = shape.icon,
            position = position,
            pressed = pressed,
            // The fingertip hotspot sits toward the upper-left of the glyph,
            // like CursorView.swift's `.position(x: position.x + 7, y: position.y + 10)`
            // (SwiftUI's .position places the view's *center*, so that call
            // is equivalent to offsetting our icon's center by (7dp, 10dp)
            // from the hotspot -- reproduced here the same way).
            centerOffsetDp = DpOffset(7.dp, 10.dp),
            sizeDp = 20.dp,
            modifier = modifier,
        )

        is CursorShape.Symbol -> SymbolCursor(
            icon = shape.icon,
            position = position,
            pressed = pressed,
            rotationDegrees = shape.rotationDegrees,
            sizeDp = 19.dp,
            modifier = modifier,
        )
    }
}

/** Classic arrow pointer, tip at [position]. Identical geometry to the Canvas code CursorOverlay.kt used inline. */
@Composable
private fun ArrowCursor(position: Offset, pressed: Boolean, modifier: Modifier = Modifier) {
    val x = position.x
    val y = position.y
    Canvas(modifier = modifier.fillMaxSize()) {
        val scale = if (pressed) 0.85f else 1f
        val path = Path().apply {
            moveTo(x, y)
            lineTo(x, y + 18f * scale)
            lineTo(x + 5f * scale, y + 13f * scale)
            lineTo(x + 8f * scale, y + 21f * scale)
            lineTo(x + 11f * scale, y + 19f * scale)
            lineTo(x + 8f * scale, y + 12f * scale)
            lineTo(x + 14f * scale, y + 12f * scale)
            close()
        }
        drawPath(path, color = Color.White)
        drawPath(path, color = Color.Black, style = Stroke(width = 1.2f))
    }
}

/**
 * A centered (or hotspot-offset) Material icon cursor. Draws a soft dark
 * copy underneath a white foreground copy -- a cheap stand-in for
 * CursorView.swift's two stacked `.shadow()` modifiers -- so the cursor stays
 * legible over both light and dark page backgrounds, matching the
 * fill-then-stroke trick [ArrowCursor] already uses for the same reason.
 */
@Composable
private fun SymbolCursor(
    icon: ImageVector,
    position: Offset,
    pressed: Boolean,
    modifier: Modifier = Modifier,
    rotationDegrees: Float = 0f,
    centerOffsetDp: DpOffset = DpOffset(0.dp, 0.dp),
    sizeDp: Dp = 19.dp,
) {
    val density = LocalDensity.current
    val scale = if (pressed) 0.85f else 1f

    val sizePx = with(density) { sizeDp.toPx() }
    val centerX = position.x + with(density) { centerOffsetDp.x.toPx() }
    val centerY = position.y + with(density) { centerOffsetDp.y.toPx() }
    val topLeftX = (centerX - sizePx / 2f).roundToInt()
    val topLeftY = (centerY - sizePx / 2f).roundToInt()

    Box(modifier = modifier.fillMaxSize()) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Color.Black.copy(alpha = 0.55f),
            modifier = Modifier
                .offset { IntOffset(topLeftX + 2, topLeftY + 2) }
                .size(sizeDp)
                .graphicsLayer {
                    rotationZ = rotationDegrees
                    scaleX = scale
                    scaleY = scale
                },
        )
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier
                .offset { IntOffset(topLeftX, topLeftY) }
                .size(sizeDp)
                .graphicsLayer {
                    rotationZ = rotationDegrees
                    scaleX = scale
                    scaleY = scale
                },
        )
    }
}
