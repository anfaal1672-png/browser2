package com.anfaal.gamebrowser

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tab
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Top toolbar: back/forward/reload + URL bar + tabs + settings. */
@Composable
fun BrowserToolbar(
    viewModel: BrowserViewModel,
    onTabsClick: () -> Unit = {},
    onSettingsClick: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val keyboardController = LocalSoftwareKeyboardController.current
    androidx.compose.foundation.layout.Column(modifier = modifier.background(Color.Black.copy(alpha = 0.75f))) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            IconButton(onClick = { viewModel.goBack() }, enabled = viewModel.canGoBack) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
            }
            IconButton(onClick = { viewModel.goForward() }, enabled = viewModel.canGoForward) {
                Icon(Icons.Filled.ArrowForward, contentDescription = "Forward", tint = Color.White)
            }

            TextField(
                value = viewModel.urlText,
                onValueChange = { viewModel.urlText = it },
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp)
                    .clip(RoundedCornerShape(10.dp)),
                singleLine = true,
                placeholder = { Text("URL または 検索語", color = Color.White.copy(alpha = 0.5f), fontSize = 13.sp) },
                textStyle = TextStyle(color = Color.White, fontSize = 13.sp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.White.copy(alpha = 0.16f),
                    unfocusedContainerColor = Color.White.copy(alpha = 0.10f),
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    cursorColor = Color.Cyan,
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(
                    onGo = {
                        viewModel.submitUrl()
                        keyboardController?.hide()
                    },
                ),
            )

            IconButton(onClick = { viewModel.reload() }) {
                Icon(Icons.Filled.Refresh, contentDescription = "Reload", tint = Color.White)
            }

            Box {
                IconButton(onClick = onTabsClick) {
                    Icon(Icons.Filled.Tab, contentDescription = "Tabs", tint = Color.White)
                }
                Text(
                    text = "${viewModel.tabManager.tabs.size}",
                    color = Color.White,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 4.dp, end = 4.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.Cyan.copy(alpha = 0.85f))
                        .padding(horizontal = 4.dp, vertical = 1.dp),
                )
            }

            IconButton(onClick = onSettingsClick) {
                Icon(Icons.Filled.Settings, contentDescription = "Settings", tint = Color.White)
            }
        }
        if (viewModel.isLoading) {
            LinearProgressIndicator(
                progress = { viewModel.progress },
                modifier = Modifier.fillMaxWidth().height(2.5.dp),
                color = Color.Cyan,
                trackColor = Color.Transparent,
            )
        }
    }
}

/** Bottom control bar: mode toggle, click/right-click/drag, keyboard toggle. */
@Composable
fun ControlBar(viewModel: BrowserViewModel, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.75f))
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        ControlButton("クリック", active = false, onClick = { viewModel.click() })
        ControlButton("右クリック", active = false, onClick = { viewModel.click(button = 2) })
        ControlButton("ドラッグ", active = viewModel.dragLocked, onClick = { viewModel.toggleDragLock() })
        ControlButton("スティック", active = viewModel.joystickVisible, onClick = { viewModel.joystickVisible = !viewModel.joystickVisible })
        ControlButton("キーボード", active = viewModel.keyboardVisible, onClick = { viewModel.keyboardVisible = !viewModel.keyboardVisible })
        ControlButton(
            "フルキー",
            active = viewModel.fullKeyboard,
            onClick = {
                viewModel.fullKeyboard = !viewModel.fullKeyboard
                if (viewModel.fullKeyboard) viewModel.keyboardVisible = true
            },
        )
    }
}

@Composable
private fun ControlButton(label: String, active: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (active) Color.Cyan.copy(alpha = 0.18f) else Color.Transparent)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(
            text = label,
            color = if (active) Color.Cyan else Color.White.copy(alpha = 0.85f),
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.pointerInput(onClick) {
                detectTapGestures(onTap = { onClick() })
            },
        )
    }
}
