package com.anfaal.gamebrowser

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/**
 * Settings for one pad button: what it sends, what it looks like, how it
 * behaves. Mirrors ControlPadView.swift's PadButtonInspector.
 */
@Composable
fun PadInspectorScreen(
    viewModel: BrowserViewModel,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val button = viewModel.selectedPadButton?.let { id ->
        viewModel.activeProfile?.buttons?.firstOrNull { it.id == id }
    }

    GBSheet(
        title = loc("ボタンの設定", "Button settings"),
        onDismiss = onDismiss,
        modifier = modifier,
    ) {
        if (button == null) {
            GBEmptyState(
                icon = Icons.Filled.Cancel,
                title = loc("ボタンが選択されていません", "No button selected"),
            )
            return@GBSheet
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = GB.Space.m),
            verticalArrangement = Arrangement.spacedBy(GB.Space.m),
        ) {
            BindingSection(viewModel, button)
            AppearanceSection(viewModel, button)
            BehaviourSection(viewModel, button)
            GBPrimaryButton(
                title = loc("このボタンを削除", "Delete this button"),
                destructive = true,
                onClick = {
                    viewModel.deletePadButton(button.id)
                    onDismiss()
                },
            )
            Box(Modifier.height(GB.Space.xl))
        }
    }
}

@Composable
private fun BindingSection(viewModel: BrowserViewModel, button: PadButton) {
    Column(verticalArrangement = Arrangement.spacedBy(GB.Space.xs)) {
        SectionTitle(loc("送信するキー", "Sends"))
        // Current binding, as removable chips: several keys held together make
        // a combo (Shift+W = sprint).
        val current = currentNames(button)
        Row(horizontalArrangement = Arrangement.spacedBy(GB.Space.xs)) {
            if (current.isEmpty()) {
                Text(
                    loc("未設定 — 下から選んでください", "Nothing bound — pick one below"),
                    style = GB.Type.caption,
                )
            } else {
                for (name in current) {
                    Row(
                        modifier = Modifier
                            .height(30.dp)
                            .clip(CircleShape)
                            .background(GB.accent)
                            .clickable { viewModel.removeBinding(name, from = button.id) }
                            .padding(horizontal = GB.Space.s),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            KeyCatalog.label(name),
                            color = GB.bgDeep,
                            fontSize = 13.sp,
                        )
                        Icon(
                            Icons.Filled.Cancel,
                            contentDescription = loc("削除", "Remove"),
                            tint = GB.bgDeep,
                            modifier = Modifier.size(12.dp),
                        )
                    }
                }
            }
        }

        for (group in KeyCatalog.groups) {
            Text(group.title, style = GB.Type.caption.copy(color = GB.textFaint))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(GB.Space.xs),
            ) {
                for (name in group.names) {
                    Box(
                        modifier = Modifier
                            .height(30.dp)
                            .clip(CircleShape)
                            .background(GB.surfaceHigh)
                            .clickable { viewModel.addBinding(name, to = button.id) }
                            .padding(horizontal = 11.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(KeyCatalog.shortLabel(name), style = GB.Type.label)
                    }
                }
            }
        }
    }
}

private fun currentNames(button: PadButton): List<String> {
    val mouse = button.mouseButton
    if (mouse != null) {
        return listOf(if (mouse == 2) PadKeyName.RIGHT_CLICK else PadKeyName.LEFT_CLICK)
    }
    return button.keys
}

@Composable
private fun AppearanceSection(viewModel: BrowserViewModel, button: PadButton) {
    Column(verticalArrangement = Arrangement.spacedBy(GB.Space.s)) {
        SectionTitle(loc("見た目", "Appearance"))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(GB.Space.s),
        ) {
            Text(loc("ラベル", "Label"), style = GB.Type.body)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(36.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(GB.surface)
                    .padding(horizontal = GB.Space.s),
                contentAlignment = Alignment.CenterStart,
            ) {
                BasicTextField(
                    value = button.label,
                    onValueChange = { new ->
                        viewModel.updatePadButton(button.id) { it.copy(label = new) }
                    },
                    singleLine = true,
                    textStyle = GB.Type.body,
                    cursorBrush = SolidColor(GB.accent),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (button.label.isEmpty()) {
                    Text(button.displayLabel, style = GB.Type.body.copy(color = GB.textDim))
                }
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(GB.Space.s),
        ) {
            Text(loc("大きさ", "Size"), style = GB.Type.body)
            Slider(
                value = button.size,
                onValueChange = { new ->
                    viewModel.updatePadButton(button.id) { it.copy(size = new) }
                },
                onValueChangeFinished = { viewModel.saveProfilesNow() },
                valueRange = 36f..110f,
                steps = 36,
                colors = SliderDefaults.colors(
                    thumbColor = GB.accent,
                    activeTrackColor = GB.accent,
                    inactiveTrackColor = GB.surfaceHigh,
                ),
                modifier = Modifier.weight(1f),
            )
            Text("${button.size.roundToInt()}", style = GB.Type.caption.copy(color = GB.accent))
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PadPalette.colors.forEachIndexed { index, rgb ->
                val (r, g, b) = rgb
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(Color(r, g, b))
                        .border(
                            width = if (button.tint == index) 2.5.dp else 0.dp,
                            color = if (button.tint == index) GB.text else Color.Transparent,
                            shape = CircleShape,
                        )
                        .clickable {
                            viewModel.updatePadButton(button.id) { it.copy(tint = index) }
                            viewModel.saveProfilesNow()
                        },
                )
            }
        }
    }
}

@Composable
private fun BehaviourSection(viewModel: BrowserViewModel, button: PadButton) {
    Column(verticalArrangement = Arrangement.spacedBy(GB.Space.s)) {
        SectionTitle(loc("動作", "Behaviour"))
        PadToggle(
            loc("押しっぱなしで固定(タップで解除)", "Latch when tapped"),
            button.sticky,
        ) { new ->
            viewModel.updatePadButton(button.id) { it.copy(sticky = new) }
            viewModel.saveProfilesNow()
        }
        PadToggle(loc("連打(ターボ)", "Turbo (auto-repeat)"), button.turbo) { new ->
            viewModel.updatePadButton(button.id) { it.copy(turbo = new) }
            viewModel.saveProfilesNow()
        }
    }
}

@Composable
private fun PadToggle(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = GB.Type.body, modifier = Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = GB.bgDeep,
                checkedTrackColor = GB.accent,
                uncheckedThumbColor = GB.text,
                uncheckedTrackColor = GB.surfaceHigh,
                uncheckedBorderColor = Color.Transparent,
                checkedBorderColor = Color.Transparent,
            ),
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = GB.Type.label.copy(color = GB.textDim))
}
