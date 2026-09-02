package com.anfaal.gamebrowser

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The app's design system - the Kotlin twin of iOS's Theme.swift (`enum GB`).
 *
 * Every value here matches its Swift counterpart exactly, because the two apps
 * are meant to be indistinguishable: the same colours, the same spacing scale,
 * the same corner radii, the same type ramp. If a token changes on one side it
 * has to change on the other, so keep the two files in the same order and with
 * the same names.
 *
 * Before this, each Android screen picked its own `Color.Black.copy(alpha=...)`
 * backdrop and `Color.Cyan` accent by hand, which is why they drifted from iOS
 * (and from each other) as soon as either side was touched.
 */
object GB {

    // ---- Colour ----

    /** Page background, at the bottom of the stack. */
    val bg = Color(0xFF0B0F14)
    val bgDeep = Color(0xFF040609)

    /** Raised surfaces (cards, bars, fields). */
    val surface = Color.White.copy(alpha = 0.06f)
    val surfaceHigh = Color.White.copy(alpha = 0.10f)
    val surfacePressed = Color.White.copy(alpha = 0.16f)

    /** Hairlines. */
    val border = Color.White.copy(alpha = 0.09f)
    val borderStrong = Color.White.copy(alpha = 0.16f)

    val text = Color(0xFFE8EDF2)
    val textDim = Color.White.copy(alpha = 0.55f)
    val textFaint = Color.White.copy(alpha = 0.38f)

    val accent = Color(0xFF39D3F5)
    val accentDeep = Color(0xFF3B82F6)

    /** Private browsing runs violet everywhere, so the mode is never in doubt. */
    val privateAccent = Color(0xFFA78BFA)
    val danger = Color(0xFFF2545B)
    val success = Color(0xFF67D38B)
    val warning = Color(0xFFF9BA51)

    // ---- Metrics ----

    object Radius {
        val small = 10.dp
        val medium = 14.dp
        val large = 18.dp
        val pill = 999.dp
    }

    object Space {
        val xs = 6.dp
        val s = 10.dp
        val m = 14.dp
        val l = 20.dp
        val xl = 28.dp
    }

    // ---- Type ----

    object Type {
        val title = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.Bold, color = GB.text)
        val heading = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = GB.text)
        val rowTitle = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium, color = GB.text)
        val body = TextStyle(fontSize = 14.sp, color = GB.text)
        val label = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium, color = GB.text)
        val caption = TextStyle(fontSize = 11.sp, color = GB.textDim)
    }

    /** The screen-filling background every sheet and full-screen surface uses. */
    val background = Brush.verticalGradient(listOf(bg, bgDeep))
}

/**
 * The accent in force for the surface being drawn - cyan normally, violet in a
 * private tab. SwiftUI gets this from `.tint(accent)` on the sheet; Compose has
 * no such ambient, so components read it from here and a private sheet sets it
 * once instead of every call site passing a colour down.
 */
val LocalGBAccent = staticCompositionLocalOf { GB.accent }

// ---- Components ----

/**
 * Section container: a rounded surface with a tinted icon, a title, and an
 * optional trailing control. Mirrors `GBCard` in Theme.swift.
 */
@Composable
fun GBCard(
    icon: ImageVector,
    tint: Color,
    title: String,
    modifier: Modifier = Modifier,
    trailing: @Composable (RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(GB.Radius.large))
            .background(GB.surface)
            .border(1.dp, GB.border, RoundedCornerShape(GB.Radius.large))
            .padding(GB.Space.m),
        verticalArrangement = Arrangement.spacedBy(GB.Space.m),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(GB.Space.s),
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(tint.copy(alpha = 0.85f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(15.dp))
            }
            Text(title, style = GB.Type.heading)
            Spacer(Modifier.weight(1f))
            trailing?.invoke(this)
        }
        content()
    }
}

/**
 * Sheet chrome: the app's background, a large title with a close button, and a
 * body. Mirrors `GBSheet` in Theme.swift, which replaced the stock
 * NavigationStack look the list screens used to inherit.
 */
@Composable
fun GBSheet(
    title: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    accent: Color = GB.accent,
    toolbar: @Composable (RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    CompositionLocalProvider(LocalGBAccent provides accent) {
        Column(
            modifier = modifier
                .fillMaxSize()
                .background(GB.background),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = GB.Space.m, end = GB.Space.m, top = GB.Space.l, bottom = GB.Space.s),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(GB.Space.s),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = GB.Type.title)
                    if (subtitle != null) {
                        Text(subtitle, style = GB.Type.caption)
                    }
                }
                toolbar?.invoke(this)
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(GB.surfaceHigh)
                        .clickable(onClick = onDismiss),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = loc("閉じる", "Close"),
                        tint = GB.text.copy(alpha = 0.8f),
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
            content()
        }
    }
}

/** One line in a list screen: leading glyph, title, subtitle, trailing slot. */
@Composable
fun GBRow(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    iconTint: Color = LocalGBAccent.current,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (RowScope.() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(horizontal = GB.Space.m, vertical = GB.Space.s),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(GB.Space.s),
    ) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(iconTint.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(16.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = GB.Type.rowTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!subtitle.isNullOrEmpty()) {
                Text(subtitle, style = GB.Type.caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        trailing?.invoke(this)
    }
}

/** Full-width primary action. */
@Composable
fun GBPrimaryButton(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    tint: Color = LocalGBAccent.current,
    destructive: Boolean = false,
    enabled: Boolean = true,
) {
    val fill = if (destructive) GB.danger.copy(alpha = 0.85f) else tint
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(RoundedCornerShape(GB.Radius.small))
            .background(if (enabled) fill else fill.copy(alpha = 0.3f))
            .clickable(enabled = enabled, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (destructive) Color.White else GB.bgDeep,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.size(GB.Space.xs))
        }
        Text(
            title,
            color = if (destructive) Color.White else GB.bgDeep,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** Quiet, tinted action used inside cards. */
@Composable
fun GBQuietButton(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    tint: Color = LocalGBAccent.current,
    enabled: Boolean = true,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(36.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(tint.copy(alpha = 0.12f))
            .clickable(enabled = enabled, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (enabled) tint else GB.textFaint,
                modifier = Modifier.size(15.dp),
            )
            Spacer(Modifier.size(GB.Space.xs))
        }
        Text(
            title,
            color = if (enabled) tint else GB.textFaint,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

/** Empty-state block for the list screens. */
@Composable
fun GBEmptyState(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    message: String? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(GB.Space.s),
    ) {
        Icon(icon, contentDescription = null, tint = GB.textFaint, modifier = Modifier.size(34.dp))
        Text(title, style = GB.Type.rowTitle.copy(color = GB.textDim))
        if (message != null) {
            Text(
                message,
                style = GB.Type.caption.copy(color = GB.textFaint),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 36.dp),
            )
        }
    }
}

/** Hairline separator matching the card borders. */
@Composable
fun GBDivider(modifier: Modifier = Modifier, color: Color = GB.border) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(color),
    )
}

/** Small pill used for counts and mode badges. */
@Composable
fun GBBadge(text: String, tint: Color = LocalGBAccent.current, modifier: Modifier = Modifier) {
    Text(
        text = text,
        color = GB.bgDeep,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(tint.copy(alpha = 0.9f))
            .padding(horizontal = 5.dp, vertical = 1.dp),
    )
}

/** Standard content padding for the scrolling body of a sheet. */
val GBSheetContentPadding = PaddingValues(
    start = GB.Space.m,
    end = GB.Space.m,
    top = GB.Space.s,
    bottom = GB.Space.xl,
)
