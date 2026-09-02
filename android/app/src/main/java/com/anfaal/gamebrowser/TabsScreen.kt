package com.anfaal.gamebrowser

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Tab switcher grid, matching ContentView.swift's `tabsSheet` + `TabCard`:
 * thumbnail cards (tap to select, X to close) over the app's sheet chrome,
 * with "new tab" and "private" actions underneath.
 *
 * This composable only draws the grid - it does not decide *how* it's
 * presented (full-screen overlay, Dialog, BackHandler, etc.); that's an
 * integration decision for whoever wires it into MainActivity.kt.
 *
 * @param onDismiss called after selecting a tab, opening a new tab, or
 *   closing the sheet - the caller is expected to hide this screen.
 */
@Composable
fun TabsScreen(
    tabManager: TabManager,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Refresh the outgoing tab's thumbnail the moment the grid opens, same as
    // tabsSheet's `.onAppear { viewModel.snapshotActiveTab() }`.
    LaunchedEffect(Unit) {
        tabManager.snapshotActiveTab()
    }

    val privateCount = tabManager.tabs.count { it.isPrivate }
    val total = tabManager.tabs.size
    val activeIsPrivate = tabManager.activeTab?.isPrivate == true

    GBSheet(
        title = loc("タブ", "Tabs"),
        onDismiss = onDismiss,
        modifier = modifier,
        subtitle = if (privateCount > 0) {
            loc("${total}個 ・ プライベート ${privateCount}個", "$total open ・ $privateCount private")
        } else {
            loc("${total}個", "$total open")
        },
        accent = if (activeIsPrivate) GB.privateAccent else GB.accent,
    ) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 150.dp),
            contentPadding = PaddingValues(
                start = GB.Space.m,
                end = GB.Space.m,
                top = GB.Space.s,
                bottom = GB.Space.xl,
            ),
            horizontalArrangement = Arrangement.spacedBy(GB.Space.s),
            verticalArrangement = Arrangement.spacedBy(GB.Space.s),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(
                count = tabManager.tabs.size,
                key = { index -> tabManager.tabs[index].id },
            ) { index ->
                val tab = tabManager.tabs[index]
                TabCard(
                    tab = tab,
                    isActive = index == tabManager.activeIndex,
                    onSelect = {
                        tabManager.selectTab(index)
                        onDismiss()
                    },
                    onClose = { tabManager.closeTab(index) },
                )
            }

            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(Modifier.padding(top = GB.Space.xs)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(GB.Space.s)) {
                        GBQuietButton(
                            title = loc("新しいタブ", "New tab"),
                            icon = Icons.Filled.Add,
                            tint = GB.accent,
                            modifier = Modifier.weight(1f),
                            onClick = {
                                tabManager.newTab()
                                onDismiss()
                            },
                        )
                        GBQuietButton(
                            title = loc("プライベート", "Private"),
                            icon = Icons.Filled.PanTool,
                            tint = GB.privateAccent,
                            modifier = Modifier.weight(1f),
                            onClick = {
                                tabManager.newTab(isPrivate = true)
                                onDismiss()
                            },
                        )
                    }
                    if (privateCount > 0) {
                        Text(
                            text = loc(
                                "プライベートタブは履歴・Cookie・サムネイルを残さず、閉じるとセッションごと消えます。",
                                "Private tabs keep no history, cookies or thumbnails, and their " +
                                    "session ends when the last one closes.",
                            ),
                            style = GB.Type.caption.copy(color = GB.textFaint),
                            modifier = Modifier.padding(top = GB.Space.s),
                        )
                    }
                }
            }
        }
    }
}

/** Thumbnail card in the tab switcher grid. Mirrors ContentView.swift's TabCard. */
@Composable
private fun TabCard(
    tab: Tab,
    isActive: Boolean,
    onSelect: () -> Unit,
    onClose: () -> Unit,
) {
    val tint = if (tab.isPrivate) GB.privateAccent else GB.accent
    val borderColor = when {
        isActive -> tint
        tab.isPrivate -> tint.copy(alpha = 0.45f)
        else -> GB.border
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(GB.Radius.medium))
            .background(GB.surface)
            .border(
                width = if (isActive) 2.dp else 1.dp,
                color = borderColor,
                shape = RoundedCornerShape(GB.Radius.medium),
            )
            .clickable(onClick = onSelect),
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(96.dp)
                    .background(GB.surface),
                contentAlignment = Alignment.Center,
            ) {
                val snapshot = tab.snapshot
                // A private tab's thumbnail is never written to disk, so it
                // shows the placeholder again after a relaunch rather than a
                // picture of what was on screen.
                if (snapshot != null) {
                    Image(
                        bitmap = snapshot.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Icon(
                        if (tab.isPrivate) Icons.Filled.PanTool else Icons.Filled.Public,
                        contentDescription = null,
                        tint = if (tab.isPrivate) tint.copy(alpha = 0.7f) else GB.textFaint,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(34.dp)
                    .background(GB.surface)
                    .padding(horizontal = GB.Space.s),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(GB.Space.xs),
            ) {
                if (tab.isPrivate) {
                    Icon(
                        Icons.Filled.PanTool,
                        contentDescription = null,
                        tint = tint,
                        modifier = Modifier.size(10.dp),
                    )
                }
                Text(
                    text = tab.displayTitle,
                    style = GB.Type.caption.copy(color = GB.text),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        // A plain clickable Box (not Material3's IconButton) so the close chip stays a
        // tight ~22dp circle, matching TabCard's small corner button on iOS - IconButton
        // insists on a larger minimum touch target that would blow up this size.
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(GB.Space.xs)
                .size(22.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.55f))
                .clickable(onClick = onClose),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.Close,
                contentDescription = loc("タブを閉じる", "Close tab"),
                tint = Color.White,
                modifier = Modifier.size(12.dp),
            )
        }
    }
}
