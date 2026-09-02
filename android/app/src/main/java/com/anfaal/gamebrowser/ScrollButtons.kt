package com.anfaal.gamebrowser

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

/**
 * Up/down scroll buttons on the trailing edge, shown when [BrowserViewModel.cursorMode]
 * and [BrowserViewModel.showScrollButtons] are both on. Ported from ContentView.swift's
 * `scrollStrip`/`ScrollRepeatButton`: holding scrolls with acceleration via
 * [BrowserViewModel.startSmoothScroll], and releasing glides to a stop through
 * [BrowserViewModel.endSmoothScroll] rather than halting mid-motion.
 */
@Composable
fun ScrollButtons(viewModel: BrowserViewModel, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(end = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ScrollRepeatButton(
            icon = Icons.Filled.KeyboardArrowUp,
            description = loc("上へスクロール", "Scroll up"),
            onPress = {
                viewModel.hapticSelection()
                viewModel.startSmoothScroll(-1f)
            },
            onRelease = { viewModel.endSmoothScroll() },
        )
        ScrollRepeatButton(
            icon = Icons.Filled.KeyboardArrowDown,
            description = loc("下へスクロール", "Scroll down"),
            onPress = {
                viewModel.hapticSelection()
                viewModel.startSmoothScroll(1f)
            },
            onRelease = { viewModel.endSmoothScroll() },
        )
    }
}

@Composable
private fun ScrollRepeatButton(
    icon: ImageVector,
    description: String,
    onPress: () -> Unit,
    onRelease: () -> Unit,
) {
    // Held is the whole interaction here, so the button has to look held -
    // without it there is no way to tell a press that registered from one that
    // slid off the edge. Matches ScrollRepeatButton on iOS.
    var pressed by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = if (pressed) 0.65f else 0.35f))
            .border(0.5.dp, GB.borderStrong, CircleShape)
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        pressed = true
                        onPress()
                        try {
                            awaitRelease()
                        } finally {
                            pressed = false
                            onRelease()
                        }
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = description,
            tint = GB.text.copy(alpha = if (pressed) 1f else 0.75f),
            modifier = Modifier.size(20.dp),
        )
    }
}
