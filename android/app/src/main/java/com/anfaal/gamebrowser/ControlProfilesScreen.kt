package com.anfaal.gamebrowser

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Gamepad
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.delay
import java.util.Locale

/**
 * The controls screen: pick a layout, tune it for this game, pin it to a site,
 * and re-bind a physical controller. Mirrors ControlProfilesView.swift.
 */
@Composable
fun ControlProfilesScreen(
    viewModel: BrowserViewModel,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var renamingId by remember { mutableStateOf<String?>(null) }
    var draftName by remember { mutableStateOf("") }
    var note by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(note) {
        if (note != null) {
            delay(2000)
            note = null
        }
    }

    GBSheet(
        title = loc("コントロール", "Controls"),
        onDismiss = onDismiss,
        modifier = modifier,
        subtitle = loc(
            "画面上のボタンを自由に配置できます",
            "Place your own buttons anywhere on screen",
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = GB.Space.m),
            verticalArrangement = Arrangement.spacedBy(GB.Space.m),
        ) {
            ProfilesCard(
                viewModel = viewModel,
                onRename = { id, name ->
                    renamingId = id
                    draftName = name
                },
                onArrange = {
                    viewModel.padVisible = true
                    viewModel.setPadEditMode(true)
                    onDismiss()
                },
            )
            TuningCard(viewModel) { note = it }
            SiteCard(viewModel)
            GamepadCard(viewModel)
            if (note != null) {
                Text(note.orEmpty(), style = GB.Type.caption.copy(color = GB.accent))
            }
            Box(Modifier.height(GB.Space.xl))
        }
    }

    if (renamingId != null) {
        RenameDialog(
            name = draftName,
            onNameChange = { draftName = it },
            onCancel = { renamingId = null },
            onSave = {
                renamingId?.let { viewModel.renameProfile(it, draftName) }
                viewModel.saveProfilesNow()
                renamingId = null
            },
        )
    }
}

@Composable
private fun ProfilesCard(
    viewModel: BrowserViewModel,
    onRename: (String, String) -> Unit,
    onArrange: () -> Unit,
) {
    GBCard(
        icon = Icons.Filled.Layers,
        tint = GB.accent,
        title = loc("プロファイル", "Profiles"),
    ) {
        if (viewModel.profiles.isEmpty()) {
            Text(
                loc("プロファイルがありません。プリセットを追加してください。",
                    "No profiles yet. Add the presets to get started."),
                style = GB.Type.caption,
            )
        }
        for (profile in viewModel.profiles) {
            val active = profile.id == viewModel.activeProfileId
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(GB.Radius.small))
                    .background(if (active) GB.accent.copy(alpha = 0.14f) else Color.Transparent)
                    .clickable { viewModel.activateProfile(profile.id) }
                    .padding(horizontal = GB.Space.s, vertical = GB.Space.s),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(GB.Space.xs),
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(if (active) GB.accent else GB.textFaint),
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        profile.name,
                        style = GB.Type.rowTitle,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        loc("${profile.buttons.size}個のボタン", "${profile.buttons.size} buttons"),
                        style = GB.Type.caption,
                    )
                }
                IconAction(Icons.Filled.Edit, loc("名前を変更", "Rename")) {
                    onRename(profile.id, profile.name)
                }
                IconAction(Icons.Filled.ContentCopy, loc("複製", "Duplicate")) {
                    viewModel.duplicateProfile(profile.id)
                }
                IconAction(Icons.Filled.Delete, loc("削除", "Delete"), GB.danger) {
                    viewModel.deleteProfile(profile.id)
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(GB.Space.s)) {
            GBQuietButton(
                title = loc("新規", "New"),
                icon = Icons.Filled.Add,
                modifier = Modifier.weight(1f),
                onClick = { viewModel.createProfile() },
            )
            GBQuietButton(
                title = loc("プリセット追加", "Add presets"),
                icon = Icons.Filled.Layers,
                modifier = Modifier.weight(1f),
                onClick = { viewModel.addPresetProfiles() },
            )
        }
        GBPrimaryButton(
            title = loc("ボタンを配置する", "Arrange buttons"),
            icon = Icons.Filled.Tune,
            enabled = viewModel.activeProfileId != null,
            onClick = onArrange,
        )
        PadToggleRow(
            loc("ボタンを表示", "Show buttons"),
            viewModel.padVisible,
        ) { viewModel.padVisible = it }
    }
}

@Composable
private fun TuningCard(viewModel: BrowserViewModel, onNote: (String) -> Unit) {
    GBCard(
        icon = Icons.Filled.Tune,
        tint = Color(0xFFBF5AF2),
        title = loc("このプロファイル", "This profile"),
    ) {
        val profile = viewModel.activeProfile
        if (profile == null) {
            Text(
                loc("プロファイルを選ぶと編集できます", "Select a profile to tune it"),
                style = GB.Type.caption,
            )
            return@GBCard
        }

        Column {
            PadToggleRow(
                loc("開いたらゲームを全画面にする", "Fullscreen the game on open"),
                profile.autoFocusGame,
            ) { viewModel.setAutoFocusGame(it) }
            Text(
                loc(
                    "サイト割り当てと組み合わせると、開くだけで遊べる状態になります",
                    "With a site assignment, opening the game is the whole setup",
                ),
                style = GB.Type.caption.copy(color = GB.textFaint),
            )
        }

        GBDivider()
        TuningSlider(
            title = loc("ボタンの濃さ", "Pad opacity"),
            value = profile.padOpacity,
            range = 0.25f..1f,
            steps = 14,
            display = "${(profile.padOpacity * 100).toInt()}%",
            onValueChange = { viewModel.setPadOpacity(it) },
            onFinished = { viewModel.saveProfilesNow() },
        )

        PadToggleRow(
            loc("カーソル感度をこのゲーム用に上書き", "Override cursor speed for this game"),
            profile.cursorSensitivity != null,
        ) { on ->
            viewModel.setProfileSensitivity(if (on) viewModel.cursorSensitivity else null)
            viewModel.saveProfilesNow()
        }
        val sensitivity = profile.cursorSensitivity
        if (sensitivity != null) {
            TuningSlider(
                title = loc("カーソル感度", "Cursor speed"),
                value = sensitivity,
                range = 0.5f..4.0f,
                steps = 34,
                display = String.format(Locale.US, "%.1fx", sensitivity),
                onValueChange = { viewModel.setProfileSensitivity(it) },
                onFinished = { viewModel.saveProfilesNow() },
            )
        }

        GBDivider()
        GBQuietButton(
            title = loc("この設定を初期値に戻す", "Reset these to defaults"),
            icon = Icons.Filled.Restore,
            onClick = {
                viewModel.resetProfileTuning()
                viewModel.saveProfilesNow()
                onNote(loc("初期値に戻しました", "Reset to defaults"))
            },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(GB.Space.s)) {
            GBQuietButton(
                title = loc("コピー", "Copy"),
                icon = Icons.Filled.ContentCopy,
                modifier = Modifier.weight(1f),
                onClick = {
                    onNote(
                        if (viewModel.copyActiveProfile()) loc("コピーしました", "Copied")
                        else loc("コピーできませんでした", "Couldn't copy"),
                    )
                },
            )
            GBQuietButton(
                title = loc("貼り付けて追加", "Paste"),
                icon = Icons.Filled.ContentPaste,
                modifier = Modifier.weight(1f),
                onClick = {
                    onNote(
                        if (viewModel.pasteProfile()) loc("読み込みました", "Imported")
                        else loc(
                            "クリップボードにプロファイルがありません",
                            "No profile on the clipboard",
                        ),
                    )
                },
            )
        }
    }
}

@Composable
private fun SiteCard(viewModel: BrowserViewModel) {
    GBCard(
        icon = Icons.Filled.Public,
        tint = Color(0xFFFF9F0A),
        title = loc("このサイト", "This site"),
    ) {
        val host = try {
            viewModel.currentUrl?.let { android.net.Uri.parse(it).host }
        } catch (e: Exception) {
            null
        }
        if (host.isNullOrEmpty()) {
            Text(loc("ページを開くと設定できます", "Open a page to pin a profile to it"),
                style = GB.Type.caption)
            return@GBCard
        }
        Text(host, style = GB.Type.label.copy(color = GB.text), maxLines = 1)
        val pinnedName = viewModel.siteProfileName(host)
        PadToggleRow(
            loc("このサイトで自動的に使う", "Use this profile here automatically"),
            viewModel.siteProfileId(host) != null &&
                viewModel.siteProfileId(host) == viewModel.activeProfileId,
        ) { viewModel.assignCurrentProfileToSite(it) }
        if (pinnedName != null) {
            Text(
                loc("割り当て済み: $pinnedName", "Assigned: $pinnedName"),
                style = GB.Type.caption.copy(color = GB.textFaint),
            )
        }
    }
}

@Composable
private fun GamepadCard(viewModel: BrowserViewModel) {
    var editingSlot by remember { mutableStateOf<GamepadSlot?>(null) }
    GBCard(
        icon = Icons.Filled.Gamepad,
        tint = Color(0xFF30D158),
        title = loc("コントローラー", "Controller"),
    ) {
        Text(
            loc(
                "物理コントローラーの各ボタンが送る入力を変更できます。",
                "Re-bind what each button on a physical controller sends.",
            ),
            style = GB.Type.caption,
        )
        for (slot in GamepadSlot.entries) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(GB.Radius.small))
                    .clickable { editingSlot = if (editingSlot == slot) null else slot }
                    .padding(vertical = GB.Space.xs, horizontal = GB.Space.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(slot.label, style = GB.Type.body, modifier = Modifier.weight(1f))
                Text(
                    KeyCatalog.label(viewModel.gamepadBinding(slot)),
                    style = GB.Type.label.copy(color = GB.accent),
                )
            }
            if (editingSlot == slot) {
                for (group in KeyCatalog.groups) {
                    Text(group.title, style = GB.Type.caption.copy(color = GB.textFaint))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(GB.Space.xs),
                    ) {
                        for (name in group.names + listOf(PadKeyName.NONE)) {
                            val chosen = viewModel.gamepadBinding(slot) == name
                            Box(
                                modifier = Modifier
                                    .height(30.dp)
                                    .clip(CircleShape)
                                    .background(if (chosen) GB.accent else GB.surfaceHigh)
                                    .clickable {
                                        viewModel.setGamepadBinding(slot, name)
                                        viewModel.saveProfilesNow()
                                    }
                                    .padding(horizontal = 11.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    KeyCatalog.shortLabel(name),
                                    style = GB.Type.label.copy(
                                        color = if (chosen) GB.bgDeep else GB.text,
                                    ),
                                )
                            }
                        }
                    }
                }
            }
        }
        GBQuietButton(
            title = loc("初期設定に戻す", "Reset mapping"),
            icon = Icons.Filled.Restore,
            onClick = {
                viewModel.resetGamepadMapping()
                viewModel.saveProfilesNow()
            },
        )
    }
}

@Composable
private fun TuningSlider(
    title: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    display: String,
    onValueChange: (Float) -> Unit,
    onFinished: () -> Unit,
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = GB.Type.body, modifier = Modifier.weight(1f))
            Text(display, style = GB.Type.caption.copy(color = GB.accent))
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            onValueChangeFinished = onFinished,
            valueRange = range,
            steps = steps,
            colors = SliderDefaults.colors(
                thumbColor = GB.accent,
                activeTrackColor = GB.accent,
                inactiveTrackColor = GB.surfaceHigh,
            ),
        )
    }
}

@Composable
private fun PadToggleRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
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
private fun IconAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    tint: Color = GB.textDim,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier.size(28.dp).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, tint = tint, modifier = Modifier.size(15.dp))
    }
}

@Composable
private fun RenameDialog(
    name: String,
    onNameChange: (String) -> Unit,
    onCancel: () -> Unit,
    onSave: () -> Unit,
) {
    Dialog(onDismissRequest = onCancel) {
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(GB.Radius.large))
                .background(GB.bg)
                .padding(GB.Space.m),
            verticalArrangement = Arrangement.spacedBy(GB.Space.s),
        ) {
            Text(loc("プロファイル名", "Profile name"), style = GB.Type.heading)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(38.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(GB.surface)
                    .padding(horizontal = GB.Space.s),
                contentAlignment = Alignment.CenterStart,
            ) {
                BasicTextField(
                    value = name,
                    onValueChange = onNameChange,
                    singleLine = true,
                    textStyle = GB.Type.body,
                    cursorBrush = SolidColor(GB.accent),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(GB.Space.s)) {
                GBQuietButton(
                    title = loc("キャンセル", "Cancel"),
                    tint = GB.textDim,
                    modifier = Modifier.weight(1f),
                    onClick = onCancel,
                )
                GBPrimaryButton(
                    title = loc("保存", "Save"),
                    modifier = Modifier.weight(1f),
                    onClick = onSave,
                )
            }
        }
    }
}
