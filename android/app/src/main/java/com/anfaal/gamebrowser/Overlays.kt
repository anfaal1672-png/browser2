package com.anfaal.gamebrowser

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Tab
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog

/**
 * Brief confirmation for things that otherwise happen invisibly - a finished
 * download, a copied link, a profile switching itself on. Mirrors
 * DownloadsView.swift's `ToastView`.
 */
@Composable
fun ToastHost(viewModel: BrowserViewModel, modifier: Modifier = Modifier) {
    val text = viewModel.toastText
    AnimatedVisibility(
        visible = text != null,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = GB.Space.l)
                .clip(RoundedCornerShape(GB.Radius.large))
                .background(GB.bgDeep.copy(alpha = 0.94f))
                .border(0.5.dp, GB.borderStrong, RoundedCornerShape(GB.Radius.large))
                .padding(horizontal = GB.Space.m, vertical = GB.Space.s),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                glyph(viewModel.toastIcon),
                contentDescription = null,
                tint = GB.text,
                modifier = Modifier.size(15.dp),
            )
            Text(
                text = text.orEmpty(),
                color = GB.text,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun glyph(icon: ToastIcon): ImageVector = when (icon) {
    ToastIcon.SUCCESS -> Icons.Filled.CheckCircle
    ToastIcon.DOWNLOAD -> Icons.Filled.Download
    ToastIcon.COPY -> Icons.Filled.ContentCopy
    ToastIcon.TAB -> Icons.Filled.Tab
    ToastIcon.WARNING -> Icons.Filled.Warning
}

/**
 * The page's own animation rate - a slow game and a slow connection look the
 * same on screen otherwise. Mirrors ContentView.swift's `fpsBadge`.
 */
@Composable
fun FpsBadge(viewModel: BrowserViewModel, modifier: Modifier = Modifier) {
    val fps = viewModel.fps
    val color = when {
        fps <= 0 -> GB.textDim
        fps < 25 -> GB.danger
        fps < 50 -> GB.warning
        else -> GB.success
    }
    Box(
        modifier = modifier
            .padding(GB.Space.s)
            .height(30.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.45f))
            .border(0.5.dp, GB.borderStrong, CircleShape)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (fps > 0) "$fps FPS" else "— FPS",
            color = color,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

/**
 * Right-clicking a link in cursor mode: WebView's own menu can't reach us
 * there, so the same actions are offered natively. Mirrors the
 * `confirmationDialog` in ContentView.swift.
 */
@Composable
fun LinkMenu(viewModel: BrowserViewModel) {
    val target = viewModel.linkTarget ?: return
    val dismiss = { viewModel.linkTarget = null }
    Dialog(onDismissRequest = dismiss) {
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(GB.Radius.large))
                .background(GB.bg)
                .border(1.dp, GB.border, RoundedCornerShape(GB.Radius.large))
                .padding(vertical = GB.Space.s),
        ) {
            Text(
                text = target.text,
                style = GB.Type.caption,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = GB.Space.m, vertical = GB.Space.xs),
            )
            GBDivider(Modifier.padding(vertical = GB.Space.xs))
            LinkAction(loc("新しいタブで開く", "Open in new tab"), Icons.Filled.OpenInNew) {
                viewModel.openLinkInNewTab()
            }
            LinkAction(loc("リンクをコピー", "Copy link"), Icons.Filled.ContentCopy) {
                viewModel.copyLink()
            }
            LinkAction(loc("リンク先をダウンロード", "Download linked file"), Icons.Filled.Download) {
                viewModel.downloadLink()
            }
            GBDivider(Modifier.padding(vertical = GB.Space.xs))
            LinkAction(loc("キャンセル", "Cancel"), null, GB.textDim) { dismiss() }
        }
    }
}

@Composable
private fun LinkAction(
    title: String,
    icon: ImageVector?,
    tint: Color = LocalGBAccent.current,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = GB.Space.m, vertical = GB.Space.s),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(GB.Space.s),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
        }
        Text(title, style = GB.Type.body)
    }
}
