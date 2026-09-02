package com.anfaal.gamebrowser

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Key
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * "Save this password?" banner shown after a login form is submitted.
 * Mirrors ContentView.swift's `credentialSavePrompt`.
 */
@Composable
fun CredentialSavePrompt(viewModel: BrowserViewModel, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(GB.bgDeep.copy(alpha = 0.9f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Icons.Filled.Key, contentDescription = null, tint = GB.accent, modifier = Modifier.height(16.dp))
        Text(
            loc("パスワードを保存しますか?", "Save this password?"),
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f),
        )
        TextAction(loc("保存", "Save"), GB.accent) { viewModel.savePendingCredential() }
        TextAction(loc("しない", "Never"), GB.textDim) { viewModel.pendingCredential = null }
    }
}

@Composable
private fun TextAction(label: String, color: Color, onClick: () -> Unit) {
    Text(
        label,
        color = color,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .pointerInput(label) { detectTapGestures(onTap = { onClick() }) }
            .padding(horizontal = 6.dp, vertical = 4.dp),
    )
}

/**
 * Row of saved-credential / card suggestions offered when a login or card
 * field is focused. Mirrors ContentView.swift's `autofillBar`.
 */
@Composable
fun AutofillBar(viewModel: BrowserViewModel, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(GB.bgDeep.copy(alpha = 0.9f))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            for (credential in viewModel.autofillSuggestions) {
                SuggestionChip(
                    label = credential.username.ifEmpty { credential.domain },
                    icon = Icons.Filled.Key,
                    onClick = { viewModel.fill(credential) },
                )
            }
            if (viewModel.cardSuggestionVisible) {
                SuggestionChip(
                    label = viewModel.paymentCard.maskedNumber,
                    icon = Icons.Filled.CreditCard,
                    onClick = { viewModel.fillCard() },
                )
            }
        }
        Box(
            modifier = Modifier
                .clip(CircleShape)
                .pointerInput(Unit) { detectTapGestures(onTap = { viewModel.dismissAutofill() }) }
                .padding(4.dp),
        ) {
            Icon(
                Icons.Filled.Close,
                contentDescription = "Dismiss",
                tint = GB.textDim,
                modifier = Modifier.height(16.dp),
            )
        }
    }
}

@Composable
private fun SuggestionChip(label: String, icon: ImageVector, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(GB.accent.copy(alpha = 0.22f))
            .pointerInput(label) { detectTapGestures(onTap = { onClick() }) }
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.height(14.dp))
        Text(label, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}
