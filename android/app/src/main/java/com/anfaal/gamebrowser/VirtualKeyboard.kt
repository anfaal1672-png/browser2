package com.anfaal.gamebrowser

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Top-level on-screen keyboard host: the romaji-IME candidate bar (when
 * active) above either the compact gamepad layout or the full QWERTY layout.
 * Mirrors VirtualKeyboardView.swift's body.
 */
@Composable
fun VirtualKeyboardHost(viewModel: BrowserViewModel, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .background(GB.bgDeep.copy(alpha = 0.9f))
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (viewModel.imeActive) {
            ImeBar(viewModel)
        }
        if (viewModel.fullKeyboard) {
            FullKeyboard(viewModel)
        } else {
            GamepadKeyboard(viewModel)
        }
    }
}

/** Composition + kanji candidate bar shown above the keys while the IME is on. */
@Composable
private fun ImeBar(viewModel: BrowserViewModel) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (viewModel.imeComposition.isNotEmpty()) {
                CandidateChip(viewModel.imeComposition) {
                    viewModel.imeSelectCandidate(viewModel.imeComposition)
                }
            }
            for (candidate in viewModel.imeCandidates) {
                CandidateChip(candidate) { viewModel.imeSelectCandidate(candidate) }
            }
        }
        Box(
            modifier = Modifier
                .clip(CircleShape)
                .pointerInput(Unit) { detectTapGestures(onTap = { viewModel.imeActive = false }) }
                .padding(4.dp),
        ) {
            Icon(
                Icons.Filled.Close,
                contentDescription = "Close IME",
                tint = GB.textDim,
                modifier = Modifier.width(16.dp),
            )
        }
    }
}

@Composable
private fun CandidateChip(text: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(GB.accent.copy(alpha = 0.22f))
            .pointerInput(text) { detectTapGestures(onTap = { onClick() }) }
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(text, color = GB.text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

/**
 * A single on-screen key. Press = keydown, release = keyup, with OS-style
 * auto-repeat. [sticky] keys (Shift/Ctrl) toggle on tap and stay held until
 * tapped again. When the built-in romaji IME is active, non-sticky keys feed
 * [BrowserViewModel]'s IME functions instead of the page directly, mirroring
 * KeyButton's `imeActive` branch on iOS.
 *
 * Pass `modifier = Modifier.weight(1f)` (and `flexible = true`, to skip the
 * fixed [width]) when placing a key inside a Row that should divide the
 * remaining space evenly, matching the full-QWERTY rows' flexible keys.
 */
@Composable
fun KeyButton(
    key: GbKey,
    viewModel: BrowserViewModel,
    modifier: Modifier = Modifier,
    sticky: Boolean = false,
    flexible: Boolean = false,
    width: Dp = 44.dp,
) {
    var pressed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var repeatJob: Job? by remember { mutableStateOf(null) }
    val isActive = pressed || (sticky && viewModel.pressedKeys.contains(key))

    Column(
        modifier = modifier
            .let { if (flexible) it else it.width(width) }
            .height(44.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (isActive) GB.text else GB.surfaceHigh)
            .pointerInput(key, sticky) {
                detectTapGestures(
                    onPress = {
                        pressed = true
                        viewModel.hapticLight()
                        val useIme = viewModel.imeActive && !sticky
                        if (useIme) {
                            var imeAction: (() -> Unit)? = null
                            when (key) {
                                GbKey.backspace -> imeAction = { viewModel.imeBackspace() }
                                GbKey.enter -> viewModel.imeConfirm()
                                GbKey.space -> viewModel.imeSpace()
                                GbKey.escape -> viewModel.imeActive = false
                                else -> if (key.key.length == 1) imeAction = { viewModel.imeType(key.key) }
                            }
                            val action = imeAction
                            if (action != null) {
                                action()
                                repeatJob = scope.launch {
                                    delay(400)
                                    while (true) {
                                        delay(70)
                                        action()
                                    }
                                }
                            }
                        } else if (sticky) {
                            if (viewModel.pressedKeys.contains(key)) viewModel.keyUp(key) else viewModel.keyDown(key)
                        } else {
                            viewModel.keyDown(key)
                            repeatJob = scope.launch {
                                delay(400)
                                while (true) {
                                    viewModel.repeatKey(key)
                                    delay(70)
                                }
                            }
                        }
                        try {
                            awaitRelease()
                        } finally {
                            pressed = false
                            repeatJob?.cancel()
                            repeatJob = null
                            if (!useIme && !sticky) viewModel.keyUp(key)
                        }
                    },
                )
            },
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = key.label,
            color = if (isActive) Color.Black else Color.White,
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp,
        )
    }
}

/** Toggles the built-in romaji IME (switches to the full layout for letters). Mirrors VirtualKeyboardView.swift's imeKey. */
@Composable
private fun ImeToggleKey(viewModel: BrowserViewModel) {
    Box(
        modifier = Modifier
            .width(40.dp)
            .height(44.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (viewModel.imeActive) GB.accent else GB.surfaceHigh)
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = {
                        viewModel.hapticLight()
                        viewModel.imeActive = !viewModel.imeActive
                        if (viewModel.imeActive) viewModel.fullKeyboard = true
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "あ",
            color = if (viewModel.imeActive) GB.bgDeep else GB.accent,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/**
 * Gamepad-style layout: a number row, WASD, common game keys, and an arrow
 * cluster. Mirrors VirtualKeyboardView.swift's `gamepadLayout`, including the
 * shrink-to-fit: the layout has a fixed natural size (540x130), which is wider
 * than most phones, so it is drawn at that size and scaled down to whatever
 * width there is - the same thing `scaledGamepad` does with GeometryReader.
 */
@Composable
fun GamepadKeyboard(viewModel: BrowserViewModel, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val scale = minOf(1f, maxWidth / GAMEPAD_WIDTH)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(GAMEPAD_HEIGHT * scale),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier
                    .width(GAMEPAD_WIDTH)
                    .height(GAMEPAD_HEIGHT)
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        transformOrigin = TransformOrigin(0.5f, 0f)
                    },
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                // Browser games overwhelmingly bind 1-9 to weapon/item/skill
                // slots, which the gamepad layout had no way to send at all -
                // reaching them meant switching to full QWERTY mid-fight.
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    for (d in listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0")) {
                        KeyButton(GbKey.digit(d), viewModel)
                    }
                }
                GamepadClusters(viewModel)
            }
        }
    }
}

private val GAMEPAD_WIDTH = 540.dp
private val GAMEPAD_HEIGHT = 130.dp

@Composable
private fun GamepadClusters(viewModel: BrowserViewModel) {
    Row(
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
                KeyButton(GbKey.shift, viewModel, sticky = true, width = 62.dp)
                KeyButton(GbKey.letter("e"), viewModel)
                KeyButton(GbKey.letter("q"), viewModel)
                KeyButton(GbKey.letter("r"), viewModel)
                KeyButton(GbKey.letter("f"), viewModel)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                KeyButton(GbKey.escape, viewModel, width = 62.dp)
                KeyButton(GbKey.space, viewModel, width = 84.dp)
                KeyButton(GbKey.enter, viewModel, width = 52.dp)
                ImeToggleKey(viewModel)
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

/** Full QWERTY layout, ported from VirtualKeyboardView.swift's `fullKeyboard`. */
@Composable
private fun FullKeyboard(viewModel: BrowserViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        keyRow(listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0").map { GbKey.digit(it) }, viewModel)
        keyRow("qwertyuiop".map { GbKey.letter(it.toString()) }, viewModel)
        keyRow("asdfghjkl".map { GbKey.letter(it.toString()) }, viewModel)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            KeyButton(GbKey.shift, viewModel, sticky = true, width = 50.dp)
            for (c in "zxcvbnm") {
                KeyButton(GbKey.letter(c.toString()), viewModel, modifier = Modifier.weight(1f), flexible = true)
            }
            KeyButton(GbKey.backspace, viewModel, width = 50.dp)
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            KeyButton(GbKey.escape, viewModel, width = 46.dp)
            KeyButton(GbKey.ctrl, viewModel, sticky = true, width = 46.dp)
            KeyButton(GbKey.tab, viewModel, width = 40.dp)
            KeyButton(GbKey.space, viewModel, modifier = Modifier.weight(1f), flexible = true)
            ImeToggleKey(viewModel)
            KeyButton(GbKey.enter, viewModel, width = 52.dp)
        }
    }
}

@Composable
private fun keyRow(keys: List<GbKey>, viewModel: BrowserViewModel) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        for (k in keys) {
            KeyButton(k, viewModel, modifier = Modifier.weight(1f), flexible = true)
        }
    }
}
