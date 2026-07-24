package com.anfaal.gamebrowser

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** A single on-screen key: press = keydown, release = keyup, with OS-style auto-repeat. */
@Composable
fun KeyButton(
    key: GbKey,
    viewModel: BrowserViewModel,
    modifier: Modifier = Modifier,
    width: androidx.compose.ui.unit.Dp = 44.dp,
) {
    var pressed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var repeatJob: Job? by remember { mutableStateOf(null) }

    Column(
        modifier = modifier
            .width(width)
            .height(44.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (pressed) Color.White else Color.White.copy(alpha = 0.14f))
            .pointerInput(key) {
                detectTapGestures(
                    onPress = {
                        pressed = true
                        viewModel.keyDown(key)
                        repeatJob = scope.launch {
                            delay(400)
                            while (true) {
                                viewModel.repeatKey(key)
                                delay(70)
                            }
                        }
                        try {
                            awaitRelease()
                        } finally {
                            pressed = false
                            repeatJob?.cancel()
                            repeatJob = null
                            viewModel.keyUp(key)
                        }
                    },
                )
            },
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = key.label,
            color = if (pressed) Color.Black else Color.White,
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp,
        )
    }
}

/** Gamepad-style layout: WASD, common game keys, and an arrow cluster. */
@Composable
fun GamepadKeyboard(viewModel: BrowserViewModel, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.55f))
            .padding(horizontal = 8.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            KeyButton(GbKey.letter("w"), viewModel)
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                KeyButton(GbKey.letter("a"), viewModel)
                KeyButton(GbKey.letter("s"), viewModel)
                KeyButton(GbKey.letter("d"), viewModel)
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                KeyButton(GbKey.letter("e"), viewModel)
                KeyButton(GbKey.letter("q"), viewModel)
                KeyButton(GbKey.letter("r"), viewModel)
                KeyButton(GbKey.letter("f"), viewModel)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                KeyButton(GbKey.escape, viewModel, width = 60.dp)
                KeyButton(GbKey.space, viewModel, width = 84.dp)
                KeyButton(GbKey.enter, viewModel, width = 52.dp)
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            KeyButton(GbKey.arrowUp, viewModel)
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                KeyButton(GbKey.arrowLeft, viewModel)
                KeyButton(GbKey.arrowDown, viewModel)
                KeyButton(GbKey.arrowRight, viewModel)
            }
        }
    }
}
