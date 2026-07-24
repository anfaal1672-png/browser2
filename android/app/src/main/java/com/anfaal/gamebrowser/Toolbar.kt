package com.anfaal.gamebrowser

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
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
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Tab
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Top toolbar: back/forward/reload/bookmark-star + URL bar + tabs + settings + overflow menu. */
@Composable
fun BrowserToolbar(
    viewModel: BrowserViewModel,
    onTabsClick: () -> Unit = {},
    onSettingsClick: () -> Unit = {},
    onBookmarksClick: () -> Unit = {},
    onHistoryClick: () -> Unit = {},
    onFindClick: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val keyboardController = LocalSoftwareKeyboardController.current
    val context = LocalContext.current
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

            IconButton(onClick = { viewModel.toggleBookmark() }, enabled = viewModel.currentUrl != null) {
                Icon(
                    if (viewModel.isCurrentPageBookmarked) Icons.Filled.Star else Icons.Filled.StarBorder,
                    contentDescription = "Bookmark",
                    tint = if (viewModel.isCurrentPageBookmarked) Color.Yellow else Color.White,
                )
            }

            var moreMenuOpen by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { moreMenuOpen = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "More", tint = Color.White)
                }
                DropdownMenu(expanded = moreMenuOpen, onDismissRequest = { moreMenuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(loc("ブックマーク", "Bookmarks")) },
                        leadingIcon = { Icon(Icons.Filled.Star, contentDescription = null) },
                        onClick = { moreMenuOpen = false; onBookmarksClick() },
                    )
                    DropdownMenuItem(
                        text = { Text(loc("ページ内検索", "Find in page")) },
                        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                        onClick = { moreMenuOpen = false; onFindClick() },
                    )
                    DropdownMenuItem(
                        text = { Text(loc("履歴", "History")) },
                        leadingIcon = { Icon(Icons.Filled.History, contentDescription = null) },
                        onClick = { moreMenuOpen = false; onHistoryClick() },
                    )
                    DropdownMenuItem(
                        text = { Text(loc("ページを翻訳", "Translate page")) },
                        leadingIcon = { Icon(Icons.Filled.Translate, contentDescription = null) },
                        enabled = viewModel.currentUrl != null,
                        onClick = { moreMenuOpen = false; viewModel.translatePage() },
                    )
                    if (viewModel.highlightsEnabled) {
                        DropdownMenuItem(
                            text = { Text(loc("ハイライトを保存(直近15秒)", "Save highlight (last 15s)")) },
                            leadingIcon = { Icon(Icons.Filled.Videocam, contentDescription = null) },
                            onClick = { moreMenuOpen = false; viewModel.saveHighlight() },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(loc("共有", "Share")) },
                        leadingIcon = { Icon(Icons.Filled.Share, contentDescription = null) },
                        enabled = viewModel.currentUrl != null,
                        onClick = {
                            moreMenuOpen = false
                            shareUrl(context, viewModel.currentUrl)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(loc("リンクをコピー", "Copy link")) },
                        leadingIcon = { Icon(Icons.Filled.ContentCopy, contentDescription = null) },
                        enabled = viewModel.currentUrl != null,
                        onClick = {
                            moreMenuOpen = false
                            copyUrl(context, viewModel.currentUrl)
                        },
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                if (viewModel.showScrollButtons) loc("スクロールボタンを隠す", "Hide scroll buttons")
                                else loc("スクロールボタンを表示", "Show scroll buttons"),
                            )
                        },
                        leadingIcon = { Icon(Icons.Filled.SwapVert, contentDescription = null) },
                        onClick = { moreMenuOpen = false; viewModel.showScrollButtons = !viewModel.showScrollButtons },
                    )
                    DropdownMenuItem(
                        text = { Text(loc("ホーム", "Home")) },
                        leadingIcon = { Icon(Icons.Filled.Home, contentDescription = null) },
                        onClick = { moreMenuOpen = false; viewModel.goHome() },
                    )
                }
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
        ControlButton(
            if (viewModel.cursorMode) loc("マウス", "Mouse") else loc("タッチ", "Touch"),
            active = viewModel.cursorMode,
            onClick = { viewModel.cursorMode = !viewModel.cursorMode },
        )
        ControlButton("クリック", active = false, enabled = viewModel.cursorMode, onClick = { viewModel.click() })
        ControlButton("右クリック", active = false, enabled = viewModel.cursorMode, onClick = { viewModel.click(button = 2) })
        ControlButton("ドラッグ", active = viewModel.dragLocked, enabled = viewModel.cursorMode, onClick = { viewModel.toggleDragLock() })
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

private fun shareUrl(context: Context, url: String?) {
    if (url == null) return
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, url)
    }
    context.startActivity(Intent.createChooser(intent, null))
}

private fun copyUrl(context: Context, url: String?) {
    if (url == null) return
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("URL", url))
}

/** Find-in-page bar shown above/below the toolbar. Ported from ContentView.swift's `findBar`. */
@Composable
fun FindBar(viewModel: BrowserViewModel, query: String, onQueryChange: (String) -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val keyboardController = LocalSoftwareKeyboardController.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.85f))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(Icons.Filled.Search, contentDescription = null, tint = Color.White.copy(alpha = 0.6f), modifier = Modifier.height(16.dp))
        TextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.weight(1f).height(44.dp),
            singleLine = true,
            placeholder = { Text(loc("ページ内を検索", "Find in page"), color = Color.White.copy(alpha = 0.5f), fontSize = 13.sp) },
            textStyle = TextStyle(color = Color.White, fontSize = 13.sp),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                cursorColor = Color.Cyan,
            ),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { viewModel.findInPage(query) }),
        )
        IconButton(onClick = { viewModel.findInPage(query, forward = false) }) {
            Icon(Icons.Filled.ArrowBack, contentDescription = "Previous", tint = Color.White, modifier = Modifier.height(16.dp))
        }
        IconButton(onClick = { viewModel.findInPage(query, forward = true) }) {
            Icon(Icons.Filled.ArrowForward, contentDescription = "Next", tint = Color.White, modifier = Modifier.height(16.dp))
        }
        Text(
            text = loc("完了", "Done"),
            color = Color.Cyan,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = {
                            keyboardController?.hide()
                            onDismiss()
                        },
                    )
                }
                .padding(horizontal = 6.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun ControlButton(label: String, active: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (active) Color.Cyan.copy(alpha = 0.18f) else Color.Transparent)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(
            text = label,
            color = when {
                !enabled -> Color.White.copy(alpha = 0.3f)
                active -> Color.Cyan
                else -> Color.White.copy(alpha = 0.85f)
            },
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.pointerInput(enabled, onClick) {
                if (enabled) detectTapGestures(onTap = { onClick() })
            },
        )
    }
}
