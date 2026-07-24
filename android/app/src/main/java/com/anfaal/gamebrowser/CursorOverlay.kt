package com.anfaal.gamebrowser

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke

/**
 * The virtual mouse cursor, drawn above the WebView. A simplified version of
 * CursorView.swift: a single arrow shape for now (position + hide-on-lock
 * only). Per-CSS-cursor-keyword shapes (I-beam, resize arrows, pointer hand,
 * ...) are a follow-up, tracked separately from this phase-1 port.
 */
@Composable
fun CursorOverlay(viewModel: BrowserViewModel, modifier: Modifier = Modifier) {
    if (viewModel.pointerLocked || viewModel.pageHidesCursor) return
    val (x, y) = viewModel.cursorPosition
    val pressed = viewModel.mouseButtonDown || viewModel.dragLocked

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
