package com.anfaal.gamebrowser

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Downloads list: what's coming in, and everything already saved. Mirrors
 * DownloadsView.swift.
 */
@Composable
fun DownloadsScreen(
    downloads: DownloadManager,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    accent: Color = GB.accent,
) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { downloads.loadFromDisk() }

    GBSheet(
        title = loc("ダウンロード", "Downloads"),
        onDismiss = onDismiss,
        modifier = modifier,
        subtitle = if (downloads.activeCount > 0) {
            loc("${downloads.activeCount}件 ダウンロード中", "${downloads.activeCount} in progress")
        } else {
            null
        },
        accent = accent,
        toolbar = {
            if (downloads.items.any { !it.isActive }) {
                Text(
                    text = loc("すべて削除", "Clear"),
                    style = GB.Type.label.copy(color = GB.danger),
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { downloads.clearFinished() }
                        .padding(horizontal = GB.Space.xs, vertical = 4.dp),
                )
            }
        },
    ) {
        if (downloads.items.isEmpty()) {
            GBEmptyState(
                icon = Icons.Filled.Download,
                title = loc("ダウンロードはまだありません", "No downloads yet"),
                message = loc(
                    "保存したファイルは端末の「ダウンロード」フォルダからも開けます。",
                    "Saved files are also in the device's Downloads folder.",
                ),
            )
        } else {
            LazyColumn(
                contentPadding = PaddingValues(bottom = GB.Space.xl),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(downloads.items, key = { it.systemId }) { item ->
                    DownloadRow(item, downloads) { intent ->
                        context.startActivity(android.content.Intent.createChooser(intent, null))
                    }
                    GBDivider(Modifier.padding(start = GB.Space.xl + GB.Space.l))
                }
            }
        }
    }
}

/** One row: live progress while downloading, actions once it's on disk. */
@Composable
private fun DownloadRow(
    item: DownloadManager.Item,
    downloads: DownloadManager,
    onShare: (android.content.Intent) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = GB.Space.m, vertical = GB.Space.s),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(GB.Space.s),
    ) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(item.tint().copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(item.icon(), contentDescription = null, tint = item.tint(), modifier = Modifier.size(16.dp))
        }

        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                item.filename,
                style = GB.Type.rowTitle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (item.isActive) {
                LinearProgressIndicator(
                    progress = { item.progress },
                    modifier = Modifier.fillMaxWidth(),
                    color = GB.accent,
                    trackColor = GB.surfaceHigh,
                )
            }
            Text(
                item.subtitle(),
                style = GB.Type.caption,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        if (item.isActive) {
            RowAction(Icons.Filled.Close, loc("キャンセル", "Cancel"), GB.textDim) {
                downloads.cancel(item)
            }
        } else {
            if (item.state is DownloadManager.Item.State.Finished) {
                RowAction(Icons.Filled.Share, loc("共有", "Share"), GB.accent) {
                    downloads.shareIntent(item)?.let(onShare)
                }
            }
            RowAction(Icons.Filled.Delete, loc("削除", "Delete"), GB.danger) {
                downloads.delete(item)
            }
        }
    }
}

@Composable
private fun RowAction(icon: ImageVector, description: String, tint: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier.size(30.dp).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, tint = tint, modifier = Modifier.size(16.dp))
    }
}

private fun DownloadManager.Item.icon(): ImageVector = when (state) {
    is DownloadManager.Item.State.Downloading -> Icons.Filled.ArrowDownward
    is DownloadManager.Item.State.Finished -> Icons.Filled.Description
    is DownloadManager.Item.State.Failed -> Icons.Filled.Warning
    is DownloadManager.Item.State.Cancelled -> Icons.Filled.Block
}

private fun DownloadManager.Item.tint(): Color = when (state) {
    is DownloadManager.Item.State.Downloading -> GB.accent
    is DownloadManager.Item.State.Finished -> GB.success
    is DownloadManager.Item.State.Failed -> GB.danger
    is DownloadManager.Item.State.Cancelled -> GB.textDim
}

private fun DownloadManager.Item.subtitle(): String = when (val s = state) {
    is DownloadManager.Item.State.Downloading -> {
        val percent = (progress * 100).toInt()
        if (sizeText.isEmpty()) "$percent%" else "$percent% ・ $sizeText"
    }
    is DownloadManager.Item.State.Finished -> listOf(
        sizeText,
        DateUtils.getRelativeTimeSpanString(date).toString(),
    ).filter { it.isNotEmpty() }.joinToString(" ・ ")
    is DownloadManager.Item.State.Failed -> s.message
    is DownloadManager.Item.State.Cancelled -> loc("キャンセルしました", "Cancelled")
}
