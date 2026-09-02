package com.anfaal.gamebrowser

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Mouse
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

/*
 * ============================================================================
 * Settings-related enums this screen owns.
 * ============================================================================
 *
 * These four are declared here (rather than assumed on BrowserViewModel, the
 * way TrackingLevel piggybacks on AdBlocker.kt's existing `TrackerBlocker`
 * int constants, or the way Credential/PaymentCard reuse AutofillStore.kt's
 * existing data classes) because nothing else in the port defines them yet.
 * They are ordinary top-level, non-private declarations, so BrowserViewModel.kt
 * (and any other file in this package) can reference them by name with no
 * import needed once a matching property is added there — see this port's
 * INTEGRATION STEPS for the exact property declarations expected.
 */

/** Trackpad behavior scheme. Mirrors BrowserViewModel.swift's `ControlScheme`. */
enum class ControlScheme { CLASSIC, QUICK }

/** Mirrors BrowserViewModel.swift's `SearchEngine`. */
enum class SearchEngine { GOOGLE, BING, DUCKDUCKGO, YAHOO_JAPAN }

/** Mirrors BrowserViewModel.swift's `NewTabPage`. */
enum class NewTabPage { HOME, START_PAGE }

/** What to do when a site asks for camera/microphone access. Mirrors BrowserViewModel.swift's `CapturePolicy`. */
enum class CapturePolicy { ASK, ALLOW, DENY }

private val ControlScheme.label: String
    get() = when (this) {
        ControlScheme.CLASSIC -> loc("従来", "Classic")
        ControlScheme.QUICK -> loc("クイック", "Quick")
    }

private val SearchEngine.label: String
    get() = when (this) {
        SearchEngine.GOOGLE -> "Google"
        SearchEngine.BING -> "Bing"
        SearchEngine.DUCKDUCKGO -> "DuckDuckGo"
        SearchEngine.YAHOO_JAPAN -> "Yahoo! JAPAN"
    }

private val NewTabPage.label: String
    get() = when (this) {
        NewTabPage.HOME -> loc("ホーム(Google)", "Home (Google)")
        NewTabPage.START_PAGE -> loc("スタートページ", "Start page")
    }

private val CapturePolicy.label: String
    get() = when (this) {
        CapturePolicy.ASK -> loc("毎回確認", "Ask")
        CapturePolicy.ALLOW -> loc("許可", "Allow")
        CapturePolicy.DENY -> loc("拒否", "Deny")
    }

/**
 * Bilingual label for AdBlocker.kt's `TrackerBlocker.OFF/BALANCED/STRICT` int
 * constants. Deliberately NOT calling `TrackerBlocker.label(level)` — that
 * function is English-only today (see its doc comment: "Android has no
 * bilingual `loc()` helper yet"), and AdBlocker.kt is an existing file this
 * change must not edit. This is the bilingual equivalent, scoped to this screen.
 */
private fun trackingLevelLabel(level: Int): String = when (level) {
    TrackerBlocker.OFF -> loc("オフ", "Off")
    TrackerBlocker.BALANCED -> loc("バランス", "Balanced")
    TrackerBlocker.STRICT -> loc("厳重", "Strict")
    else -> loc("オフ", "Off")
}

// ----------------------------------------------------------------------------
// Palette. Everything structural comes from Theme.kt's GB tokens, so this
// screen cannot drift from the rest of the app. The per-card icon tints are
// the iOS dark-mode system colours SettingsView.swift names (.blue, .orange,
// .purple, ...) rather than the Material palette, so the two settings screens
// are the same screen rather than two takes on one.
// ----------------------------------------------------------------------------

private val Accent = GB.accent
private val TintBlue = Color(0xFF0A84FF)
private val TintOrange = Color(0xFFFF9F0A)
private val TintPurple = Color(0xFFBF5AF2)
private val TintYellow = Color(0xFFFFD60A)
private val TintGreen = Color(0xFF30D158)
private val TintTeal = Color(0xFF40C8E0)
private val TintRed = Color(0xFFFF453A)
private val TintPink = Color(0xFFFF375F)
private val TintIndigo = Color(0xFF5E5CE6)
private val TintGray = Color(0xFF8E8E93)

private val BackgroundTop = GB.bg
private val BackgroundBottom = GB.bgDeep

/**
 * Custom-designed settings screen: dark surface cards with tinted section
 * icons, chip pickers and cyan accents — ported from GameBrowser/Sources/
 * SettingsView.swift's card-based dark layout (replaces the stock Android/
 * Material "list of preferences" look, the same way the Swift original
 * replaces the stock iOS Form look).
 *
 * This is a plain full-screen composable, not a Dialog/BottomSheet/Activity —
 * see this port's INTEGRATION STEPS for how MainActivity.kt is expected to
 * show/hide it (the same overlay-toggled-by-a-boolean pattern TabsScreen.kt
 * already uses for the tab switcher).
 *
 * @param viewModel Source of every setting shown here. Several of the
 *   properties/functions referenced below do not exist on BrowserViewModel.kt
 *   yet (this port is still in progress across parallel workstreams) — see
 *   INTEGRATION STEPS for the full list this screen expects to find there.
 * @param onDismiss Called when the user taps the close button, "Go home", or
 *   otherwise wants the screen dismissed. The caller decides what that means
 *   (hide the overlay, pop a nav entry, ...).
 */
@Composable
fun SettingsScreen(
    viewModel: BrowserViewModel,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showCardEditor by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(BackgroundTop, BackgroundBottom))),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            SettingsHeader(onDismiss)
            BrowserModeCard(viewModel)
            ControlsCard(viewModel)
            SearchCard(viewModel, onDismiss)
            AppearanceCard(viewModel)
            AutofillCard(viewModel, onEditCard = { showCardEditor = true })
            SecurityCard(viewModel)
            PermissionsCard(viewModel)
            DataCard(viewModel)
            HighlightsCard(viewModel)
            BackgroundCard(viewModel)
            Spacer(modifier = Modifier.height(16.dp))
        }
    }

    if (showCardEditor) {
        CardEditorSheet(viewModel, onDismiss = { showCardEditor = false })
    }
}

@Composable
private fun SettingsHeader(onDismiss: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 20.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(loc("設定", "Settings"), style = GB.Type.title)
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
                tint = Color.White.copy(alpha = 0.8f),
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

// ----------------------------------------------------------------------------
// Cards — one private composable per card, mirroring SettingsView.swift's
// one-private-computed-property-per-card structure (browserModeCard,
// controlsCard, searchCard, ...).
// ----------------------------------------------------------------------------

@Composable
private fun BrowserModeCard(viewModel: BrowserViewModel) {
    SettingsCard(icon = Icons.Filled.SportsEsports, tint = Accent, title = loc("ブラウザモード", "Browser mode")) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ModeButton(
                label = loc("スマホ", "Phone"),
                icon = Icons.Filled.TouchApp,
                selected = !viewModel.pcMode,
            ) {
                viewModel.pcMode = false
                viewModel.hapticLight()
            }
            ModeButton(
                label = "PC",
                icon = Icons.Filled.Computer,
                selected = viewModel.pcMode,
            ) {
                viewModel.pcMode = true
                viewModel.hapticLight()
            }
        }
        Text(
            text = if (viewModel.pcMode) {
                loc("仮想マウス・キーボード付きのゲーミングブラウザ", "Gaming browser with virtual mouse & keyboard")
            } else {
                loc("通常のタッチ操作ブラウザ(下部バー非表示)", "Regular touch browser (no bottom bar)")
            },
            color = Color.White.copy(alpha = 0.5f),
            fontSize = 12.sp,
        )
    }
}

@Composable
private fun ControlsCard(viewModel: BrowserViewModel) {
    SettingsCard(icon = Icons.Filled.Mouse, tint = TintBlue, title = loc("操作", "Controls")) {
        Labeled(loc("操作スキーム", "Control scheme")) {
            Chips(
                options = ControlScheme.entries.map { it to it.label },
                selection = viewModel.controlScheme,
            ) {
                viewModel.controlScheme = it
                viewModel.hapticLight()
            }
        }
        Text(
            text = if (viewModel.controlScheme == ControlScheme.QUICK) {
                loc(
                    "タップ後すぐ押し込みでドラッグ / フリックで慣性 / 長押しで右クリック",
                    "Tap-and-press to drag / flick for momentum / long-press to right-click",
                )
            } else {
                loc("長押しでドラッグ / 2本指タップで右クリック", "Long-press to drag / two-finger tap to right-click")
            },
            color = Color.White.copy(alpha = 0.45f),
            fontSize = 11.sp,
        )

        SettingsDivider()
        SliderRow(
            title = loc("カーソル感度", "Cursor sensitivity"),
            value = viewModel.cursorSensitivity,
            range = 0.5f..3.0f,
            steps = 24,
            display = String.format(Locale.US, "%.1fx", viewModel.cursorSensitivity),
            onValueChange = { viewModel.cursorSensitivity = it },
        )
        SliderRow(
            title = loc("スクロール速度", "Scroll speed"),
            value = viewModel.scrollSpeed,
            range = 300f..1500f,
            steps = 23,
            display = viewModel.scrollSpeed.toInt().toString(),
            onValueChange = { viewModel.scrollSpeed = it },
        )

        SettingsDivider()
        ToggleRow(loc("触覚フィードバック", "Haptic feedback"), viewModel.hapticsEnabled) { viewModel.hapticsEnabled = it }
        ToggleRow(loc("FPS(フレームレート)を表示", "Show FPS meter"), viewModel.showFps) {
            viewModel.showFps = it
        }
        ToggleRow(loc("ジョイスティック: 矢印キーを送信", "Joystick sends arrow keys"), viewModel.joystickUsesArrows) {
            viewModel.joystickUsesArrows = it
        }
        ButtonRow(loc("ジョイスティック位置をリセット", "Reset joystick position")) {
            viewModel.resetJoystickPosition()
        }
    }
}

@Composable
private fun SearchCard(viewModel: BrowserViewModel, onDismiss: () -> Unit) {
    SettingsCard(icon = Icons.Filled.Search, tint = TintOrange, title = loc("検索とタブ", "Search & tabs")) {
        Labeled(loc("検索エンジン", "Search engine")) {
            Chips(
                options = SearchEngine.entries.map { it to it.label },
                selection = viewModel.searchEngine,
            ) {
                viewModel.searchEngine = it
                viewModel.hapticLight()
            }
        }
        Labeled(loc("新しいタブ", "New tab")) {
            Chips(
                options = NewTabPage.entries.map { it to it.label },
                selection = viewModel.newTabPage,
            ) {
                viewModel.newTabPage = it
                viewModel.hapticLight()
            }
        }
        ButtonRow(loc("ホームに戻る", "Go home")) {
            viewModel.goHome()
            onDismiss()
        }
    }
}

@Composable
private fun AppearanceCard(viewModel: BrowserViewModel) {
    SettingsCard(icon = Icons.Filled.Palette, tint = TintPurple, title = loc("外観", "Appearance")) {
        Labeled(loc("テーマ", "Theme")) {
            // 0 = Dark, 1 = Light, 2 = System — NOTE this ordering is different
            // from appLanguage's 0 = System below; both are ported verbatim
            // from SettingsView.swift's own (differently ordered) chip lists.
            Chips(
                options = listOf(0 to loc("ダーク", "Dark"), 1 to loc("ライト", "Light"), 2 to loc("システム", "System")),
                selection = viewModel.appTheme,
            ) {
                viewModel.appTheme = it
                viewModel.hapticLight()
            }
        }
        Labeled(loc("言語", "Language")) {
            Chips(
                options = listOf(0 to loc("システム", "System"), 1 to "日本語", 2 to "English"),
                selection = viewModel.appLanguage,
            ) {
                // Keep BrowserViewModel's own persisted appLanguage and the
                // separate Localization object in sync — see INTEGRATION STEPS.
                viewModel.appLanguage = it
                Localization.languageSetting = it
                viewModel.hapticLight()
            }
        }
        Labeled(loc("ツールバーの位置", "Toolbar position")) {
            Chips(
                options = listOf(false to loc("上", "Top"), true to loc("下", "Bottom")),
                selection = viewModel.toolbarOnBottom,
            ) {
                viewModel.toolbarOnBottom = it
                viewModel.hapticLight()
            }
        }
        SettingsDivider()
        ToggleRow(loc("PC版サイトを表示", "Show desktop sites"), viewModel.desktopMode) { viewModel.desktopMode = it }
        ToggleRow(loc("スクロールボタンを表示", "Show scroll buttons"), viewModel.showScrollButtons) {
            viewModel.showScrollButtons = it
        }
    }
}

@Composable
private fun AutofillCard(viewModel: BrowserViewModel, onEditCard: () -> Unit) {
    SettingsCard(icon = Icons.Filled.VpnKey, tint = TintYellow, title = loc("自動入力", "Autofill")) {
        ToggleRow(loc("パスワード・カードの自動入力", "Autofill passwords & cards"), viewModel.autofillEnabled) {
            viewModel.autofillEnabled = it
        }

        if (viewModel.credentials.isNotEmpty()) {
            SettingsDivider()
            viewModel.credentials.forEach { credential ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        Text(
                            text = credential.username.ifEmpty { loc("(ユーザー名なし)", "(no username)") },
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(credential.domain, color = Color.White.copy(alpha = 0.5f), fontSize = 11.sp)
                    }
                    IconButton(onClick = {
                        viewModel.credentials = viewModel.credentials.filterNot { it.id == credential.id }
                    }) {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = loc("削除", "Delete"),
                            tint = TintRed.copy(alpha = 0.85f),
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }

        SettingsDivider()
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(loc("支払い方法", "Payment method"), color = Color.White, fontSize = 14.sp)
            Text(
                text = if (viewModel.paymentCard.isEmpty) loc("追加", "Add") else viewModel.paymentCard.maskedNumber,
                color = Accent,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.clickable(onClick = onEditCard),
            )
        }
    }
}

@Composable
private fun SecurityCard(viewModel: BrowserViewModel) {
    SettingsCard(icon = Icons.Filled.Shield, tint = TintGreen, title = loc("セキュリティ", "Security")) {
        ToggleRow(loc("広告ブロック", "Ad blocking"), viewModel.adBlockEnabled) { viewModel.adBlockEnabled = it }
        if (viewModel.adBlockEnabled) {
            Box(modifier = Modifier.padding(start = 12.dp)) {
                ToggleRow(loc("強力な広告ブロック(EasyList)", "Strong ad blocking (EasyList)"), viewModel.useFullAdList) {
                    viewModel.useFullAdList = it
                }
            }
        }
        SettingsDivider()
        Labeled(loc("トラッキング防止", "Tracking prevention")) {
            Chips(
                options = listOf(TrackerBlocker.OFF, TrackerBlocker.BALANCED, TrackerBlocker.STRICT)
                    .map { it to trackingLevelLabel(it) },
                selection = viewModel.trackingLevel,
            ) {
                viewModel.trackingLevel = it
                viewModel.hapticLight()
            }
        }
        if (viewModel.trackingLevel == TrackerBlocker.STRICT) {
            Text(
                loc("厳重: 一部サイトが動かなくなる場合があります", "Strict: some sites may break"),
                color = TintOrange.copy(alpha = 0.9f),
                fontSize = 11.sp,
            )
        }
        SettingsDivider()
        ToggleRow(loc("生体認証でアプリをロック", "Lock app with biometrics"), viewModel.appLockEnabled) {
            viewModel.appLockEnabled = it
        }
        ToggleRow(loc("詐欺Webサイトの警告", "Fraudulent site warning"), viewModel.fraudWarning) { viewModel.fraudWarning = it }
        ToggleRow(loc("HTTPSを優先", "Prefer HTTPS"), viewModel.httpsOnly) { viewModel.httpsOnly = it }
        ToggleRow(loc("ポップアップをブロック", "Block pop-ups"), viewModel.blockPopups) { viewModel.blockPopups = it }
        ToggleRow(loc("JavaScriptを有効にする", "Enable JavaScript"), viewModel.javaScriptEnabled) {
            viewModel.javaScriptEnabled = it
        }
    }
}

@Composable
private fun PermissionsCard(viewModel: BrowserViewModel) {
    SettingsCard(icon = Icons.Filled.Security, tint = TintTeal, title = loc("サイトの権限", "Site permissions")) {
        Labeled(loc("カメラ・マイク", "Camera & microphone")) {
            Chips(
                options = CapturePolicy.entries.map { it to it.label },
                selection = viewModel.capturePolicy,
            ) {
                viewModel.capturePolicy = it
                viewModel.hapticLight()
            }
        }
        ToggleRow(loc("サイトからの通知を許可", "Allow site notifications"), viewModel.webNotificationsEnabled) {
            viewModel.webNotificationsEnabled = it
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ButtonRow(loc("位置情報を許可", "Allow location"), modifier = Modifier.weight(1f)) {
                viewModel.requestLocationPermission()
            }
            ButtonRow(loc("通知を許可", "Allow notifications"), modifier = Modifier.weight(1f)) {
                viewModel.requestNotificationPermission()
            }
        }
    }
}

@Composable
private fun DataCard(viewModel: BrowserViewModel) {
    var clearCookies by remember { mutableStateOf(true) }
    var clearCache by remember { mutableStateOf(true) }
    var clearHistoryToo by remember { mutableStateOf(false) }
    var dataCleared by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    SettingsCard(icon = Icons.Filled.Delete, tint = TintRed, title = loc("閲覧データを削除", "Clear browsing data")) {
        ToggleRow(loc("Cookie・サイトデータ", "Cookies & site data"), clearCookies) { clearCookies = it }
        ToggleRow(loc("キャッシュ", "Cache"), clearCache) { clearCache = it }
        ToggleRow(loc("履歴", "History"), clearHistoryToo) { clearHistoryToo = it }

        val canClear = clearCookies || clearCache || clearHistoryToo
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(if (dataCleared) TintGreen.copy(alpha = 0.7f) else TintRed.copy(alpha = 0.8f))
                .clickable(enabled = canClear) {
                    viewModel.clearData(cookies = clearCookies, cache = clearCache, history = clearHistoryToo)
                    viewModel.hapticMedium()
                    dataCleared = true
                    scope.launch {
                        delay(2000)
                        dataCleared = false
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = if (dataCleared) loc("削除しました ✓", "Cleared ✓") else loc("選択したデータを削除", "Clear selected data"),
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun HighlightsCard(viewModel: BrowserViewModel) {
    SettingsCard(icon = Icons.Filled.Videocam, tint = TintPink, title = loc("ハイライト", "Highlights")) {
        ToggleRow(loc("インスタントリプレイを有効にする", "Enable instant replay"), viewModel.highlightsEnabled) {
            viewModel.highlightsEnabled = it
        }
        Text(
            loc(
                "直近15秒のプレイ画面を裏で保持しておき、ボタン一つでギャラリーに保存できます。録画中の表示は出ません。",
                "Keeps the last 15 seconds of play buffered invisibly — tap once to save it to your gallery. No recording indicator is shown.",
            ),
            color = Color.White.copy(alpha = 0.45f),
            fontSize = 11.sp,
        )
    }
}

@Composable
private fun BackgroundCard(viewModel: BrowserViewModel) {
    SettingsCard(icon = Icons.Filled.Bedtime, tint = TintIndigo, title = loc("バックグラウンド", "Background")) {
        ToggleRow(loc("バックグラウンドで実行を継続", "Keep running in background"), viewModel.keepAliveInBackground) {
            viewModel.keepAliveInBackground = it
        }
        Text(
            loc(
                "バックグラウンドでの実行を継続し、アプリを閉じてもページが動き続けるようにします。バッテリー消費が増えます。",
                "Keeps pages running when the app is closed instead of the OS suspending them. Uses more battery.",
            ),
            color = Color.White.copy(alpha = 0.45f),
            fontSize = 11.sp,
        )
    }
}

// ----------------------------------------------------------------------------
// Payment card editor sheet — ported from SettingsView.swift's `cardEditor`
// `.sheet(isPresented:)` (`.presentationDetents([.medium])`) as a Material3
// ModalBottomSheet, the closest Compose idiom to a half-height iOS sheet.
// ----------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CardEditorSheet(viewModel: BrowserViewModel, onDismiss: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = BackgroundTop,
        contentColor = Color.White,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(loc("支払い方法", "Payment method"), color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text(
                    loc("完了", "Done"),
                    color = Accent,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.clickable(onClick = onDismiss),
                )
            }

            CardField(
                placeholder = loc("カード番号", "Card number"),
                value = viewModel.paymentCard.number,
                onValueChange = { viewModel.paymentCard = viewModel.paymentCard.copy(number = it) },
                keyboardType = KeyboardType.Number,
            )
            CardField(
                placeholder = loc("名義(ローマ字)", "Name on card"),
                value = viewModel.paymentCard.holder,
                onValueChange = { viewModel.paymentCard = viewModel.paymentCard.copy(holder = it) },
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CardField(
                    placeholder = loc("月(MM)", "Month (MM)"),
                    value = viewModel.paymentCard.expMonth,
                    onValueChange = { viewModel.paymentCard = viewModel.paymentCard.copy(expMonth = it) },
                    modifier = Modifier.weight(1f),
                    keyboardType = KeyboardType.Number,
                )
                CardField(
                    placeholder = loc("年(YY)", "Year (YY)"),
                    value = viewModel.paymentCard.expYear,
                    onValueChange = { viewModel.paymentCard = viewModel.paymentCard.copy(expYear = it) },
                    modifier = Modifier.weight(1f),
                    keyboardType = KeyboardType.Number,
                )
            }

            Text(
                loc("この端末の暗号化ストレージにのみ保存されます", "Stored encrypted only on this device"),
                color = Color.White.copy(alpha = 0.45f),
                fontSize = 11.sp,
            )

            if (!viewModel.paymentCard.isEmpty) {
                Text(
                    loc("カード情報を削除", "Delete card"),
                    color = TintRed,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.clickable { viewModel.paymentCard = PaymentCard() },
                )
            }
        }
    }
}

// ----------------------------------------------------------------------------
// Building blocks — mirrors SettingsView.swift's `card`/`toggleRow`/`buttonRow`/
// `sliderRow`/`labeled`/`chips`/`modeButton`/`field` helpers, using Compose
// Material3 idioms (Surface-like Box/Column backgrounds, Material3 Switch/
// Slider/TextField) rather than literal SwiftUI translations.
// ----------------------------------------------------------------------------

@Composable
private fun SettingsCard(
    icon: ImageVector,
    tint: Color,
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(GB.surface)
            .border(1.dp, GB.border, RoundedCornerShape(GB.Radius.large))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(tint.copy(alpha = 0.85f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(15.dp))
            }
            Text(title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }
        content()
    }
}

@Composable
private fun SettingsDivider() {
    HorizontalDivider(color = GB.border, thickness = 1.dp)
}

@Composable
private fun ToggleRow(title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = Accent,
                checkedBorderColor = Color.Transparent,
                uncheckedThumbColor = Color.White.copy(alpha = 0.9f),
                uncheckedTrackColor = Color.White.copy(alpha = 0.25f),
                uncheckedBorderColor = Color.Transparent,
            ),
        )
    }
}

@Composable
private fun ButtonRow(title: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(34.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(Accent.copy(alpha = 0.12f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(title, color = Accent, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun Labeled(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text(title, color = Color.White.copy(alpha = 0.55f), fontSize = 12.sp, fontWeight = FontWeight.Medium)
        content()
    }
}

/** Horizontal chip picker. */
@Composable
private fun <T> Chips(
    options: List<Pair<T, String>>,
    selection: T,
    onSelect: (T) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        options.forEach { (value, label) ->
            val selected = value == selection
            Box(
                modifier = Modifier
                    .height(31.dp)
                    .clip(CircleShape)
                    .background(if (selected) Accent else Color.White.copy(alpha = 0.1f))
                    .clickable { onSelect(value) }
                    .padding(horizontal = 13.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    color = if (selected) Color.Black else Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

@Composable
private fun SliderRow(
    title: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    display: String,
    onValueChange: (Float) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title, color = Color.White, fontSize = 14.sp)
            Text(
                text = display,
                color = Accent,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                fontFamily = FontFamily.Monospace,
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            steps = steps,
            colors = SliderDefaults.colors(
                thumbColor = Accent,
                activeTrackColor = Accent,
                inactiveTrackColor = Color.White.copy(alpha = 0.2f),
            ),
        )
    }
}

@Composable
private fun RowScope.ModeButton(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .weight(1f)
            .height(66.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) Accent else Color.White.copy(alpha = 0.08f))
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (selected) Color.Black else Color.White,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            label,
            color = if (selected) Color.Black else Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun CardField(
    placeholder: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    TextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(RoundedCornerShape(10.dp)),
        singleLine = true,
        placeholder = { Text(placeholder, color = Color.White.copy(alpha = 0.4f), fontSize = 15.sp) },
        textStyle = TextStyle(color = Color.White, fontSize = 15.sp),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Color.White.copy(alpha = 0.08f),
            unfocusedContainerColor = Color.White.copy(alpha = 0.08f),
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            cursorColor = Accent,
        ),
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
    )
}
