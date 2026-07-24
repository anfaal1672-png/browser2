package com.anfaal.gamebrowser

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

/**
 * Up/down scroll buttons on the trailing edge, shown when [BrowserViewModel.cursorMode]
 * and [BrowserViewModel.showScrollButtons] are both on. Ported from ContentView.swift's
 * `scrollStrip`/`ScrollRepeatButton`: holding smoothly scrolls via
 * [BrowserViewModel.startSmoothScroll]/[BrowserViewModel.endSmoothScroll], releasing stops it.
 */
@Composable
fun ScrollButtons(viewModel: BrowserViewModel, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(end = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ScrollRepeatButton(
            icon = Icons.Filled.KeyboardArrowUp,
            onPress = {
                viewModel.hapticSelection()
                viewModel.startSmoothScroll(-1f)
            },
            onRelease = { viewModel.endSmoothScroll() },
        )
        ScrollRepeatButton(
            icon = Icons.Filled.KeyboardArrowDown,
            onPress = {
                viewModel.hapticSelection()
                viewModel.startSmoothScroll(1f)
            },
            onRelease = { viewModel.endSmoothScroll() },
        )
    }
}

@Composable
private fun ScrollRepeatButton(icon: ImageVector, onPress: () -> Unit, onRelease: () -> Unit) {
    Box(
        modifier = Modifier
            .size(38.dp)
            .background(Color.Black.copy(alpha = 0.45f), CircleShape)
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        onPress()
                        try {
                            awaitRelease()
                        } finally {
                            onRelease()
                        }
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = Color.White.copy(alpha = 0.9f))
    }
}
