package com.anfaal.gamebrowser

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset

/**
 * The virtual mouse cursor, drawn above the WebView. Shape follows the CSS
 * `cursor` of the element under the pointer, via CursorShapes.kt's full
 * keyword mapping (ported from CursorView.swift).
 */
@Composable
fun CursorOverlay(viewModel: BrowserViewModel, modifier: Modifier = Modifier) {
    if (viewModel.pointerLocked || viewModel.pageHidesCursor) return
    val (x, y) = viewModel.cursorPosition
    val pressed = viewModel.mouseButtonDown || viewModel.dragLocked

    CursorShapesRenderer(
        shape = cursorShapeFor(viewModel.cursorStyle),
        position = Offset(x, y),
        pressed = pressed,
        modifier = modifier.fillMaxSize(),
    )
}
