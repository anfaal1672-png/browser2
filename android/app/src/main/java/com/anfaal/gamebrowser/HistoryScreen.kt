package com.anfaal.gamebrowser

import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

/** Browsing history list, matching ContentView.swift's `historySheet`, newest first. */
@Composable
fun HistoryScreen(
    viewModel: BrowserViewModel,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    GBSheet(
        title = loc("履歴", "History"),
        onDismiss = onDismiss,
        modifier = modifier,
        accent = if (viewModel.isPrivateTab) GB.privateAccent else GB.accent,
        toolbar = {
            if (viewModel.history.isNotEmpty()) {
                Text(
                    text = loc("消去", "Clear"),
                    style = GB.Type.label.copy(color = GB.danger),
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = viewModel::clearHistory)
                        .padding(horizontal = GB.Space.xs, vertical = 4.dp),
                )
            }
        },
    ) {
        if (viewModel.history.isEmpty()) {
            GBEmptyState(
                icon = Icons.Filled.History,
                title = loc("履歴はまだありません", "No history yet"),
            )
        } else {
            val entries = viewModel.history.asReversed()
            LazyColumn(
                contentPadding = PaddingValues(bottom = GB.Space.xl),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(entries, key = { it.id }) { entry ->
                    GBRow(
                        icon = Icons.Filled.History,
                        title = entry.title.ifBlank { entry.url },
                        subtitle = entry.url,
                        onClick = {
                            viewModel.webView?.loadUrl(entry.url)
                            onDismiss()
                        },
                        trailing = {
                            Text(
                                text = DateUtils.getRelativeTimeSpanString(entry.date).toString(),
                                style = GB.Type.caption,
                            )
                        },
                    )
                    GBDivider(Modifier.padding(start = GB.Space.xl + GB.Space.l))
                }
            }
        }
    }
}
