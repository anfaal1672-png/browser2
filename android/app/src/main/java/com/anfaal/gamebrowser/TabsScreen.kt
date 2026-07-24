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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Tab switcher grid: ported from ContentView.swift's `tabsSheet` + `TabCard`.
 * A LazyVerticalGrid of thumbnail cards (tap to select, X to close) plus a
 * trailing "+" card that opens a new tab, over a near-opaque dark backdrop
 * matching Toolbar.kt / VirtualKeyboard.kt's Color.Black.copy(alpha = ...)
 * + Color.Cyan accent styling.
 *
 * This composable only draws the grid - it does not decide *how* it's
 * presented (full-screen overlay, Dialog, BackHandler, etc.); that's an
 * integration decision for whoever wires it into MainActivity.kt.
 *
 * @param onDismiss called after selecting a tab, opening a new tab, or
 *   tapping "Done" - the caller is expected to hide this screen in response.
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

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.96f)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Tabs (${tabManager.tabs.size})",
                color = Color.White,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "Done",
                color = Color.Cyan,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onDismiss)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }

        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 150.dp),
            contentPadding = PaddingValues(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
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

            item {
                NewTabCard(
                    onClick = {
                        tabManager.newTab()
                        onDismiss()
                    },
                )
            }
        }
    }
}

/** Thumbnail card in the tab switcher grid. Ported from ContentView.swift's TabCard. */
@Composable
private fun TabCard(
    tab: Tab,
    isActive: Boolean,
    onSelect: () -> Unit,
    onClose: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White.copy(alpha = 0.06f))
            .border(
                width = if (isActive) 2.dp else 1.dp,
                color = if (isActive) Color.Cyan else Color.White.copy(alpha = 0.12f),
                shape = RoundedCornerShape(14.dp),
            )
            .clickable(onClick = onSelect),
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(96.dp)
                    .background(Color.White.copy(alpha = 0.05f)),
                contentAlignment = Alignment.Center,
            ) {
                val snapshot = tab.snapshot
                if (snapshot != null) {
                    Image(
                        bitmap = snapshot.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Icon(
                        Icons.Filled.Public,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.35f),
                        modifier = Modifier.size(26.dp),
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.55f))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = tab.displayTitle,
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
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
                .padding(6.dp)
                .size(22.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.55f))
                .clickable(onClick = onClose),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.Close,
                contentDescription = "Close tab",
                tint = Color.White,
                modifier = Modifier.size(12.dp),
            )
        }
    }
}

/** Trailing "new tab" card in the grid: dashed-feel border, "+" icon, matches TabCard's sizing. */
@Composable
private fun NewTabCard(onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(138.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White.copy(alpha = 0.05f))
            .border(
                width = 1.5.dp,
                color = Color.White.copy(alpha = 0.25f),
                shape = RoundedCornerShape(14.dp),
            )
            .clickable(onClick = onClick),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Filled.Add,
            contentDescription = "New tab",
            tint = Color.White.copy(alpha = 0.7f),
            modifier = Modifier.size(26.dp),
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "New tab",
            color = Color.White.copy(alpha = 0.7f),
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}
