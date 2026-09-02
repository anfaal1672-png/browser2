package com.anfaal.gamebrowser

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Floating one-tap "instant replay" capture button, shown top-leading over the
 * WebView while `highlightsEnabled` is on. Ported from ContentView.swift's
 * `highlightButton` / `highlightIcon` / `highlightColor`:
 *
 *  - idle    -> Videocam        (iOS "video.badge.checkmark"), white
 *  - saving  -> HourglassEmpty  (iOS "hourglass"),             white, disabled
 *  - saved   -> CheckCircle     (iOS "checkmark.circle.fill"), green + "Saved"
 *  - failed  -> Cancel          (iOS "xmark.circle.fill"),     red   + "Failed"
 *
 * SF Symbol -> Material Icon substitutions follow the same precedent as
 * `CursorShapes.kt`. Visual style (black translucent pill, thin white border,
 * Cyan-family accents) matches `Toolbar.kt`/`CursorOverlay.kt`.
 *
 * The caller is responsible for gating visibility on
 * `viewModel.highlightsEnabled` and for placement — e.g. in MainActivity's
 * WebView Box:
 *
 *     if (viewModel.highlightsEnabled) {
 *         HighlightButton(viewModel, modifier = Modifier.align(Alignment.TopStart))
 *     }
 */
@Composable
fun HighlightButton(viewModel: BrowserViewModel, modifier: Modifier = Modifier) {
    val state = viewModel.highlightSaveState
    val saving = state == HighlightSaveState.Saving

    val icon: ImageVector = when (state) {
        HighlightSaveState.Idle -> Icons.Filled.Videocam
        HighlightSaveState.Saving -> Icons.Filled.HourglassEmpty
        HighlightSaveState.Saved -> Icons.Filled.CheckCircle
        HighlightSaveState.Failed -> Icons.Filled.Cancel
    }

    val targetColor = when (state) {
        HighlightSaveState.Saved -> Color(0xFF34C759) // iOS-green
        HighlightSaveState.Failed -> Color(0xFFFF3B30) // iOS-red
        else -> GB.text.copy(alpha = 0.9f)
    }
    // Mirrors iOS's `.animation(.easeInOut(0.2), value: highlightSaveState)`.
    val tint by animateColorAsState(targetValue = targetColor, label = "highlightTint")

    val label = when (state) {
        HighlightSaveState.Saved -> loc("保存済み", "Saved")
        HighlightSaveState.Failed -> loc("失敗", "Failed")
        else -> null
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .padding(10.dp)
            .height(30.dp)
            .background(Color.Black.copy(alpha = 0.45f), CircleShape)
            .border(0.5.dp, GB.borderStrong, CircleShape)
            // iOS disables the button while saving; drop the click handler then.
            .clickable(enabled = !saving) { viewModel.saveHighlight() }
            .padding(horizontal = if (state == HighlightSaveState.Idle) 9.dp else 11.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = loc("ハイライトを保存", "Save highlight"),
            tint = tint,
            modifier = Modifier.size(18.dp),
        )
        if (label != null) {
            Text(
                text = label,
                color = tint,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 6.dp),
            )
        }
    }
}
