package com.anfaal.gamebrowser

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/**
 * The active profile's buttons, drawn over the page. Mirrors
 * ControlPadView.swift.
 *
 * Positions are fractions of this overlay's own size, so a layout survives
 * rotation and moving between devices - the same reason the model stores them
 * that way.
 */
@Composable
fun ControlPadOverlay(viewModel: BrowserViewModel, modifier: Modifier = Modifier) {
    val profile = viewModel.activeProfile ?: return
    val editing = viewModel.padEditing
    val density = LocalDensity.current

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val widthPx = with(density) { maxWidth.toPx() }
        val heightPx = with(density) { maxHeight.toPx() }

        for (button in profile.buttons) {
            key(button.id) {
                PadButtonView(
                    viewModel = viewModel,
                    button = button,
                    editing = editing,
                    // Arranging is a precision job, so the buttons are always
                    // fully opaque in edit mode however dim the profile is.
                    opacity = if (editing) 1f else profile.padOpacity,
                    selected = viewModel.selectedPadButton == button.id,
                    widthPx = widthPx,
                    heightPx = heightPx,
                )
            }
        }

        if (editing) {
            EditModeBar(
                viewModel,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = GB.Space.s),
            )
        }
    }
}

@Composable
private fun PadButtonView(
    viewModel: BrowserViewModel,
    button: PadButton,
    editing: Boolean,
    opacity: Float,
    selected: Boolean,
    widthPx: Float,
    heightPx: Float,
) {
    val density = LocalDensity.current
    val sizeDp = button.size.dp
    val sizePx = with(density) { sizeDp.toPx() }
    val (r, g, b) = PadPalette.colors[PadPalette.clampIndex(button.tint)]
    val tint = Color(r, g, b)
    val latched = button.id in viewModel.padLatched
    var pressed by remember { mutableStateOf(false) }
    val active = pressed || latched

    val offsetX = (button.x * widthPx - sizePx / 2f).roundToInt()
    val offsetY = (button.y * heightPx - sizePx / 2f).roundToInt()

    Box(
        modifier = Modifier
            .offset { IntOffset(offsetX, offsetY) }
            .size(sizeDp)
            .clip(CircleShape)
            .background(
                if (active) tint.copy(alpha = 0.55f * opacity) else tint.copy(alpha = 0.22f * opacity),
            )
            .border(
                width = if (selected) 2.5.dp else 1.5.dp,
                color = if (selected) GB.text else tint.copy(alpha = 0.8f * opacity),
                shape = CircleShape,
            )
            .then(
                if (editing) {
                    Modifier.pointerInput(button.id) {
                        detectDragGestures(
                            onDragStart = {
                                viewModel.selectedPadButton = button.id
                            },
                            onDragEnd = { viewModel.saveProfilesNow() },
                        ) { change, drag ->
                            change.consume()
                            val current = viewModel.activeProfile
                                ?.buttons?.firstOrNull { it.id == button.id } ?: return@detectDragGestures
                            viewModel.movePadButton(
                                button.id,
                                current.x + drag.x / widthPx,
                                current.y + drag.y / heightPx,
                            )
                        }
                    }.pointerInput(button.id) {
                        detectTapGestures(onTap = {
                            viewModel.selectedPadButton = button.id
                            viewModel.showPadInspector = true
                        })
                    }
                } else {
                    Modifier.pointerInput(button.id, button.sticky, button.turbo) {
                        detectTapGestures(
                            onPress = {
                                pressed = true
                                viewModel.padPress(button)
                                tryAwaitRelease()
                                pressed = false
                                viewModel.padRelease(button)
                            },
                        )
                    }
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = button.displayLabel,
            color = GB.text.copy(alpha = opacity),
            fontSize = if (button.displayLabel.length > 3) 11.sp else 15.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            textAlign = TextAlign.Center,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        if (button.turbo && !editing) {
            Text(
                "T",
                color = tint,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.TopEnd).padding(5.dp),
            )
        }
    }
}

/** Arrange-mode toolbar: add a button, tune the selected one, and finish. */
@Composable
private fun EditModeBar(viewModel: BrowserViewModel, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(GB.Radius.pill))
            .background(GB.bgDeep.copy(alpha = 0.94f))
            .border(0.5.dp, GB.borderStrong, RoundedCornerShape(GB.Radius.pill))
            .padding(horizontal = GB.Space.s, vertical = GB.Space.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(GB.Space.s),
    ) {
        BarAction(loc("追加", "Add"), Icons.Filled.Add) { viewModel.addPadButton() }
        BarAction(
            loc("設定", "Edit"),
            Icons.Filled.Tune,
            enabled = viewModel.selectedPadButton != null,
        ) {
            viewModel.showPadInspector = true
        }
        BarAction(loc("完了", "Done"), Icons.Filled.Check, tint = GB.success) {
            viewModel.setPadEditMode(false)
            viewModel.saveProfilesNow()
        }
    }
}

@Composable
private fun BarAction(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    enabled: Boolean = true,
    tint: Color = GB.accent,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = GB.Space.xs, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (enabled) tint else GB.textFaint,
            modifier = Modifier.size(14.dp),
        )
        Text(
            title,
            color = if (enabled) tint else GB.textFaint,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}
