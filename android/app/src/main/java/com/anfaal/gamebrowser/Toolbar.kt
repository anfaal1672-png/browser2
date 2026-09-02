package com.anfaal.gamebrowser

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.CloseFullscreen
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Gamepad
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled.PhoneIphone
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.ZoomOutMap
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Top toolbar: back/forward, the address pill, the tab count and the overflow
 * menu. Mirrors ContentView.swift's `toolbar` control for control, including
 * the private-tab accent and the reload/stop control folded into the pill —
 * the bar used to carry seven separate controls plus the field, which left
 * every target too small to hit.
 */
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
    val context = LocalContext.current
    // Violet whenever the tab on screen is private, so the mode is readable at
    // a glance from the chrome rather than from a badge alone.
    val accent = if (viewModel.isPrivateTab) GB.privateAccent else GB.accent

    Column(modifier = modifier.background(GB.bg.copy(alpha = 0.92f))) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = GB.Space.s, vertical = GB.Space.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(GB.Space.xs),
        ) {
            ToolbarButton(Icons.Filled.ChevronLeft, loc("戻る", "Back"), viewModel.canGoBack) {
                viewModel.goBack()
            }
            ToolbarButton(Icons.Filled.ChevronRight, loc("進む", "Forward"), viewModel.canGoForward) {
                viewModel.goForward()
            }

            UrlBar(viewModel, accent, Modifier.weight(1f))

            // Tab count in an outlined square, same as the iOS toolbar's.
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clickable(onClick = onTabsClick),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(21.dp)
                        .border(1.6.dp, GB.text.copy(alpha = 0.85f), RoundedCornerShape(7.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "${viewModel.tabManager.tabs.size}",
                        color = GB.text,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }

            OverflowMenu(
                viewModel = viewModel,
                context = context,
                accent = accent,
                onBookmarksClick = onBookmarksClick,
                onHistoryClick = onHistoryClick,
                onFindClick = onFindClick,
                onSettingsClick = onSettingsClick,
            )
        }
        ProgressBar(viewModel, accent)
        // The hairline sits on whichever edge faces the page.
        if (!viewModel.toolbarOnBottom) GBDivider()
    }
}

/** Toolbar glyph with a real 38dp target. */
@Composable
private fun ToolbarButton(
    icon: ImageVector,
    description: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(38.dp)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = description,
            tint = if (enabled) GB.text else GB.textFaint,
            modifier = Modifier.size(20.dp),
        )
    }
}

/**
 * The address pill: mode glyph, domain (full URL while editing), and the
 * reload/stop control folded in. Shows just the host when idle and the whole
 * URL once tapped, like every other mobile browser.
 */
@Composable
private fun UrlBar(viewModel: BrowserViewModel, accent: Color, modifier: Modifier = Modifier) {
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }

    Row(
        modifier = modifier
            .height(36.dp)
            .clip(RoundedCornerShape(GB.Radius.small))
            .background(if (focused) GB.surfaceHigh else GB.surface)
            .border(
                1.dp,
                if (focused) accent.copy(alpha = 0.6f) else GB.border,
                RoundedCornerShape(GB.Radius.small),
            )
            .padding(start = GB.Space.s, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(GB.Space.xs),
    ) {
        Icon(
            modeGlyph(viewModel),
            contentDescription = null,
            tint = if (viewModel.isPrivateTab) GB.privateAccent else GB.textDim,
            modifier = Modifier.size(13.dp),
        )

        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            // The field stays in the hierarchy at all times - focusing one that
            // isn't composed yet silently does nothing. When idle it is
            // invisible and the domain label is drawn on top of it.
            BasicTextField(
                value = viewModel.urlText,
                onValueChange = { viewModel.urlText = it },
                singleLine = true,
                textStyle = GB.Type.body,
                cursorBrush = SolidColor(accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(
                    onGo = {
                        viewModel.submitUrl()
                        keyboardController?.hide()
                    },
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
                    .onFocusChanged { focused = it.isFocused },
            )
            if (!focused) {
                Text(
                    text = idleUrlText(viewModel),
                    style = GB.Type.body.copy(
                        color = if (viewModel.currentUrl == null) GB.textDim else GB.text,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable { focusRequester.requestFocus() },
                )
            }
        }

        Box(
            modifier = Modifier
                .size(26.dp)
                .clickable {
                    if (viewModel.isLoading) viewModel.webView?.stopLoading() else viewModel.reload()
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (viewModel.isLoading) Icons.Filled.Close else Icons.Filled.Refresh,
                contentDescription = if (viewModel.isLoading) loc("停止", "Stop") else loc("再読み込み", "Reload"),
                tint = GB.textDim,
                modifier = Modifier.size(15.dp),
            )
        }
    }
}

private fun modeGlyph(viewModel: BrowserViewModel): ImageVector = when {
    viewModel.isPrivateTab -> Icons.Filled.PanTool
    viewModel.currentUrl?.startsWith("https") == true -> Icons.Filled.Lock
    else -> Icons.Filled.Public
}

private fun idleUrlText(viewModel: BrowserViewModel): String {
    val host = try {
        viewModel.currentUrl?.let { android.net.Uri.parse(it).host }
    } catch (e: Exception) {
        null
    }
    if (!host.isNullOrEmpty()) return host
    if (viewModel.urlText.isNotEmpty()) return viewModel.urlText
    return if (viewModel.isPrivateTab) {
        loc("プライベートタブ", "Private tab")
    } else {
        loc("検索またはURLを入力", "Search or enter URL")
    }
}

@Composable
private fun ProgressBar(viewModel: BrowserViewModel, accent: Color) {
    if (!viewModel.isLoading) return
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(2.5.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(viewModel.progress.coerceIn(0f, 1f))
                .fillMaxSize()
                .background(Brush.horizontalGradient(listOf(accent, GB.accentDeep))),
        )
    }
}

@Composable
private fun OverflowMenu(
    viewModel: BrowserViewModel,
    context: Context,
    accent: Color,
    onBookmarksClick: () -> Unit,
    onHistoryClick: () -> Unit,
    onFindClick: () -> Unit,
    onSettingsClick: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clickable { open = true },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.MoreVert,
                contentDescription = loc("メニュー", "More"),
                tint = GB.text,
                modifier = Modifier.size(20.dp),
            )
            if (viewModel.activeDownloads > 0) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 9.dp, end = 8.dp)
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(GB.accent),
                )
            }
        }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            modifier = Modifier.background(GB.bg),
        ) {
            MenuItem(loc("共有", "Share"), Icons.Filled.Share, accent, viewModel.currentUrl != null) {
                open = false
                shareUrl(context, viewModel.currentUrl)
            }
            MenuItem(loc("リンクをコピー", "Copy link"), Icons.Filled.ContentCopy, accent, viewModel.currentUrl != null) {
                open = false
                copyUrl(context, viewModel.currentUrl)
            }
            MenuItem(loc("ページ内検索", "Find in page"), Icons.Filled.Search, accent) {
                open = false
                onFindClick()
            }
            MenuItem(loc("履歴", "History"), Icons.Filled.History, accent) {
                open = false
                onHistoryClick()
            }
            MenuItem(
                if (viewModel.isCurrentPageBookmarked) loc("ブックマークを削除", "Remove bookmark")
                else loc("ブックマークに追加", "Add bookmark"),
                if (viewModel.isCurrentPageBookmarked) Icons.Filled.Star else Icons.Filled.StarBorder,
                accent,
                viewModel.currentUrl != null,
            ) {
                open = false
                viewModel.toggleBookmark()
            }
            MenuItem(loc("ブックマーク", "Bookmarks"), Icons.Filled.Book, accent) {
                open = false
                onBookmarksClick()
            }
            MenuItem(loc("プライベートタブを開く", "New private tab"), Icons.Filled.PanTool, GB.privateAccent) {
                open = false
                viewModel.newPrivateTab()
            }
            MenuItem(
                if (viewModel.activeDownloads > 0) {
                    loc("ダウンロード (${viewModel.activeDownloads}件)", "Downloads (${viewModel.activeDownloads})")
                } else {
                    loc("ダウンロード", "Downloads")
                },
                Icons.Filled.Download,
                accent,
            ) {
                open = false
                viewModel.showDownloads = true
            }
            MenuItem(loc("ページを翻訳", "Translate page"), Icons.Filled.Translate, accent, viewModel.currentUrl != null) {
                open = false
                viewModel.translatePage()
            }
            MenuItem(loc("コントロール設定", "Controls"), Icons.Filled.Gamepad, accent) {
                open = false
                viewModel.showProfiles = true
            }
            if (viewModel.highlightsEnabled) {
                MenuItem(loc("ハイライトを保存(直近15秒)", "Save highlight (last 15s)"), Icons.Filled.Videocam, accent) {
                    open = false
                    viewModel.saveHighlight()
                }
            }

            GBDivider(Modifier.padding(vertical = 4.dp))

            MenuItem(
                if (viewModel.gameFocused) loc("ゲーム全画面を解除", "Exit game fullscreen")
                else loc("ゲームだけ全画面", "Fullscreen the game"),
                if (viewModel.gameFocused) Icons.Filled.CloseFullscreen else Icons.Filled.SportsEsports,
                accent,
            ) {
                open = false
                viewModel.toggleGameFocus()
            }
            MenuItem(
                if (viewModel.desktopMode) loc("モバイル版サイトを表示", "Show mobile site")
                else loc("PC版サイトを表示", "Show desktop site"),
                if (viewModel.desktopMode) Icons.Filled.PhoneIphone else Icons.Filled.DesktopWindows,
                accent,
            ) {
                open = false
                viewModel.desktopMode = !viewModel.desktopMode
            }
            MenuItem(
                if (viewModel.showScrollButtons) loc("スクロールボタンを隠す", "Hide scroll buttons")
                else loc("スクロールボタンを表示", "Show scroll buttons"),
                Icons.Filled.SwapVert,
                accent,
            ) {
                open = false
                viewModel.showScrollButtons = !viewModel.showScrollButtons
            }
            MenuItem(loc("ズームをリセット", "Reset zoom"), Icons.Filled.ZoomOutMap, accent) {
                open = false
                viewModel.resetZoom()
            }
            MenuItem(loc("ホーム", "Home"), Icons.Filled.Home, accent) {
                open = false
                viewModel.goHome()
            }

            GBDivider(Modifier.padding(vertical = 4.dp))

            MenuItem(loc("設定", "Settings"), Icons.Filled.Settings, accent) {
                open = false
                onSettingsClick()
            }
        }
    }
}

@Composable
private fun MenuItem(
    title: String,
    icon: ImageVector,
    accent: Color,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = {
            Text(
                title,
                style = GB.Type.body.copy(color = if (enabled) GB.text else GB.textFaint),
            )
        },
        leadingIcon = {
            Icon(
                icon,
                contentDescription = null,
                tint = if (enabled) accent else GB.textFaint,
                modifier = Modifier.size(18.dp),
            )
        },
        enabled = enabled,
        onClick = onClick,
    )
}

/** Bottom control bar: mode toggle, click/right-click/drag, keyboard toggle. */
@Composable
fun ControlBar(viewModel: BrowserViewModel, modifier: Modifier = Modifier) {
    val accent = if (viewModel.isPrivateTab) GB.privateAccent else GB.accent
    Column(modifier = modifier.fillMaxWidth().background(GB.bg.copy(alpha = 0.92f))) {
        GBDivider()
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = GB.Space.xs),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ControlButton(
                if (viewModel.cursorMode) loc("マウス", "Mouse") else loc("タッチ", "Touch"),
                active = viewModel.cursorMode,
                accent = accent,
                onClick = { viewModel.cursorMode = !viewModel.cursorMode },
            )
            ControlButton(loc("クリック", "Click"), active = false, accent = accent, enabled = viewModel.cursorMode) {
                viewModel.click()
            }
            ControlButton(loc("右クリック", "Right"), active = false, accent = accent, enabled = viewModel.cursorMode) {
                viewModel.click(button = 2)
            }
            ControlButton(loc("ドラッグ", "Drag"), active = viewModel.dragLocked, accent = accent, enabled = viewModel.cursorMode) {
                viewModel.toggleDragLock()
            }
            ControlButton(loc("スティック", "Stick"), active = viewModel.joystickVisible, accent = accent) {
                viewModel.joystickVisible = !viewModel.joystickVisible
            }
            ControlButton(loc("キーボード", "Keys"), active = viewModel.keyboardVisible, accent = accent) {
                viewModel.keyboardVisible = !viewModel.keyboardVisible
            }
            ControlButton(loc("フルキー", "Full"), active = viewModel.fullKeyboard, accent = accent) {
                viewModel.fullKeyboard = !viewModel.fullKeyboard
                if (viewModel.fullKeyboard) viewModel.keyboardVisible = true
            }
        }
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

/** Find-in-page bar shown above/below the toolbar. Mirrors ContentView.swift's `findBar`. */
@Composable
fun FindBar(
    viewModel: BrowserViewModel,
    query: String,
    onQueryChange: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val keyboardController = LocalSoftwareKeyboardController.current
    val accent = if (viewModel.isPrivateTab) GB.privateAccent else GB.accent
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(GB.bgDeep.copy(alpha = 0.94f))
            .padding(horizontal = GB.Space.s, vertical = GB.Space.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(GB.Space.xs),
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .height(36.dp)
                .clip(RoundedCornerShape(GB.Radius.small))
                .background(GB.surface)
                .padding(horizontal = GB.Space.s),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(GB.Space.xs),
        ) {
            Icon(Icons.Filled.Search, contentDescription = null, tint = GB.textDim, modifier = Modifier.size(14.dp))
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    textStyle = GB.Type.body,
                    cursorBrush = SolidColor(accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { viewModel.findInPage(query) }),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (query.isEmpty()) {
                    Text(loc("ページ内を検索", "Find in page"), style = GB.Type.body.copy(color = GB.textDim))
                }
            }
        }
        ToolbarButton(Icons.Filled.ArrowBack, loc("前へ", "Previous")) {
            viewModel.findInPage(query, forward = false)
        }
        ToolbarButton(Icons.Filled.ArrowForward, loc("次へ", "Next")) {
            viewModel.findInPage(query, forward = true)
        }
        Text(
            text = loc("完了", "Done"),
            color = accent,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .clickable {
                    keyboardController?.hide()
                    onDismiss()
                }
                .padding(horizontal = GB.Space.xs, vertical = 4.dp),
        )
    }
}

@Composable
private fun ControlButton(
    label: String,
    active: Boolean,
    accent: Color,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(GB.Radius.small))
            .background(if (active) accent.copy(alpha = 0.18f) else Color.Transparent)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = GB.Space.s, vertical = GB.Space.xs),
    ) {
        Text(
            text = label,
            color = when {
                !enabled -> GB.textFaint
                active -> accent
                else -> GB.text.copy(alpha = 0.85f)
            },
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}
