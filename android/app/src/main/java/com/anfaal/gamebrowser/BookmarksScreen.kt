package com.anfaal.gamebrowser

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Bookmarks list, matching ContentView.swift's `bookmarksSheet`. Tap opens the
 * bookmark, the trailing X removes it. Ordered as saved (no drag-reorder,
 * unlike iOS's `.onMove` - a reasonable simplification for a first pass).
 */
@Composable
fun BookmarksScreen(
    viewModel: BrowserViewModel,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    GBSheet(
        title = loc("ブックマーク", "Bookmarks"),
        onDismiss = onDismiss,
        modifier = modifier,
        accent = if (viewModel.isPrivateTab) GB.privateAccent else GB.accent,
    ) {
        if (viewModel.bookmarks.isEmpty()) {
            GBEmptyState(
                icon = Icons.Filled.Book,
                title = loc("ブックマークはまだありません", "No bookmarks yet"),
                message = loc(
                    "メニューの「ブックマークに追加」で保存できます。",
                    "Add one from the menu on any page.",
                ),
            )
        } else {
            LazyColumn(
                contentPadding = PaddingValues(bottom = GB.Space.xl),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(viewModel.bookmarks, key = { it.id }) { bookmark ->
                    GBRow(
                        icon = Icons.Filled.Star,
                        iconTint = GB.warning,
                        title = bookmark.title,
                        subtitle = bookmark.url,
                        onClick = {
                            viewModel.open(bookmark)
                            onDismiss()
                        },
                        trailing = {
                            Box(
                                modifier = Modifier
                                    .size(28.dp)
                                    .clickable { viewModel.bookmarks = viewModel.bookmarks - bookmark },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    Icons.Filled.Close,
                                    contentDescription = loc("削除", "Remove"),
                                    tint = GB.textDim,
                                    modifier = Modifier.size(15.dp),
                                )
                            }
                        },
                    )
                    GBDivider(Modifier.padding(start = GB.Space.xl + GB.Space.l))
                }
            }
        }
    }
}
