package com.anfaal.gamebrowser

import android.Manifest
import android.app.Activity
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.FragmentActivity

/**
 * FragmentActivity (not ComponentActivity) because [AppLock]'s BiometricPrompt
 * hosts its UI as a DialogFragment, which needs a FragmentActivity/Fragment host.
 */
class MainActivity : FragmentActivity() {
    private val viewModel: BrowserViewModel by viewModels()
    private val gamepadInput by lazy { GamepadInput(viewModel, this) }
    private var unlocking = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { /* WebView's onPermissionRequest re-checks grants at call time */ }

    private val mediaProjectionManager by lazy {
        getSystemService(MediaProjectionManager::class.java)
    }

    /** Handles the system screen-capture consent dialog for [HighlightRecorder]. */
    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            HighlightRecorder.onConsentGranted(this, result.resultCode, data)
        } else {
            // Declined the system dialog — onConsentDenied() fires onBufferingUnavailable,
            // which flips highlightsEnabled back off so Settings stays honest.
            HighlightRecorder.onConsentDenied()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val permissions = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        permissionLauncher.launch(permissions.toTypedArray())

        // Mirrors ContentView's `@State private var isLocked = UserDefaults...` cold-start
        // check; onResume() below immediately attempts to clear it via biometrics (or,
        // if none are enrolled, AppLock.authenticate's own "don't lock the user out" fallback).
        if (viewModel.appLockEnabled) viewModel.isLocked = true

        // Also forces the lazy GamepadInput to exist now, so its device
        // listener is registered before the first controller event arrives.
        gamepadInput.refreshConnectedState()

        HighlightRecorder.onRequestConsent = {
            screenCaptureLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
        }
        HighlightRecorder.onBufferingUnavailable = {
            viewModel.highlightsEnabled = false
        }
        // Android's projection consent token is one-shot, so a setting left on from a
        // previous process isn't actually buffering after a cold start — re-request it
        // once here (not from onResume, which would re-prompt on every foreground/
        // background cycle and stack awkwardly with the app-lock screen).
        if (viewModel.highlightsEnabled) {
            HighlightRecorder.enable(applicationContext)
        }

        setContent {
            var showTabs by remember { mutableStateOf(false) }
            var showSettings by remember { mutableStateOf(false) }
            var showBookmarks by remember { mutableStateOf(false) }
            var showHistory by remember { mutableStateOf(false) }
            var showFindBar by remember { mutableStateOf(false) }
            var findQuery by remember { mutableStateOf("") }

            fun dismissFindBar() {
                if (!showFindBar) return
                showFindBar = false
                findQuery = ""
                viewModel.clearFindSelection()
            }

            // Playing with a controller produces no touches at all, and neither
            // does a long cutscene in fullscreen, so the display would dim and
            // lock mid-game. Hold it awake only for those two explicit "playing"
            // signals; ordinary browsing still sleeps normally.
            LaunchedEffect(viewModel.immersive, viewModel.gamepadConnected) {
                if (viewModel.immersive || viewModel.gamepadConnected) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }

            // Hides/shows the system status/nav bars to match viewModel.immersive,
            // mirroring ContentView.swift's `.overlay(alignment: .topTrailing)` fullscreen mode.
            LaunchedEffect(viewModel.immersive) {
                val controller = WindowCompat.getInsetsController(window, window.decorView)
                if (viewModel.immersive) {
                    controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    controller.hide(WindowInsetsCompat.Type.systemBars())
                } else {
                    controller.show(WindowInsetsCompat.Type.systemBars())
                }
            }

            Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
                Column(modifier = Modifier.fillMaxSize()) {
                    if (!viewModel.immersive && !viewModel.toolbarOnBottom) {
                        BrowserToolbar(
                            viewModel,
                            onTabsClick = { showTabs = true },
                            onSettingsClick = { showSettings = true },
                            onBookmarksClick = { showBookmarks = true },
                            onHistoryClick = { showHistory = true },
                            onFindClick = { showFindBar = true },
                        )
                        if (showFindBar) {
                            FindBar(viewModel, findQuery, { findQuery = it }, onDismiss = ::dismissFindBar)
                        }
                    }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxSize()
                            .onSizeChanged { size ->
                                viewModel.webViewSize = Pair(size.width.toFloat(), size.height.toFloat())
                            },
                    ) {
                        GameWebView(viewModel, modifier = Modifier.fillMaxSize())
                        if (viewModel.cursorMode) {
                            TrackpadOverlay(viewModel, modifier = Modifier.fillMaxSize())
                            CursorOverlay(viewModel, modifier = Modifier.fillMaxSize())
                        }
                        if (viewModel.cursorMode && viewModel.showScrollButtons) {
                            ScrollButtons(viewModel, modifier = Modifier.align(Alignment.CenterEnd))
                        }
                        if (viewModel.immersive) {
                            ImmersiveExitControls(viewModel, modifier = Modifier.align(Alignment.TopEnd).padding(10.dp))
                        }
                        if (viewModel.highlightsEnabled) {
                            HighlightButton(viewModel, modifier = Modifier.align(Alignment.TopStart))
                        }
                    }

                    if (viewModel.pendingCredential != null) {
                        CredentialSavePrompt(viewModel)
                    }
                    if (viewModel.autofillSuggestions.isNotEmpty() || viewModel.cardSuggestionVisible) {
                        AutofillBar(viewModel)
                    }

                    if (viewModel.keyboardVisible) {
                        VirtualKeyboardHost(viewModel)
                    }

                    if (viewModel.pcMode && !viewModel.immersive) {
                        ControlBar(viewModel)
                    }

                    if (!viewModel.immersive && viewModel.toolbarOnBottom) {
                        if (showFindBar) {
                            FindBar(viewModel, findQuery, { findQuery = it }, onDismiss = ::dismissFindBar)
                        }
                        BrowserToolbar(
                            viewModel,
                            onTabsClick = { showTabs = true },
                            onSettingsClick = { showSettings = true },
                            onBookmarksClick = { showBookmarks = true },
                            onHistoryClick = { showHistory = true },
                            onFindClick = { showFindBar = true },
                        )
                    }
                }

                if (viewModel.isLocked) {
                    LockScreen(onUnlockClick = ::attemptUnlock)
                }

                if (showTabs) {
                    TabsScreen(viewModel.tabManager, onDismiss = { showTabs = false }, modifier = Modifier.fillMaxSize())
                }
                if (showSettings) {
                    SettingsScreen(viewModel, onDismiss = { showSettings = false }, modifier = Modifier.fillMaxSize())
                }
                if (showBookmarks) {
                    BookmarksScreen(viewModel, onDismiss = { showBookmarks = false }, modifier = Modifier.fillMaxSize())
                }
                if (showHistory) {
                    HistoryScreen(viewModel, onDismiss = { showHistory = false }, modifier = Modifier.fillMaxSize())
                }
            }

            LaunchedEffect(Unit) {
                viewModel.applyKeyboardSuppression()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        gamepadInput.refreshConnectedState()
        if (viewModel.isLocked) attemptUnlock()
    }

    override fun onPause() {
        viewModel.tabManager.onAppBackgrounded()
        super.onPause()
    }

    override fun onStop() {
        super.onStop()
        if (viewModel.appLockEnabled) viewModel.isLocked = true
    }

    private fun attemptUnlock() {
        if (unlocking) return
        unlocking = true
        AppLock.authenticate(this) { success ->
            if (success) viewModel.isLocked = false
            unlocking = false
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (gamepadInput.handleKeyEvent(event)) return true
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (gamepadInput.handleGenericMotionEvent(event)) return true
        return super.dispatchGenericMotionEvent(event)
    }

    override fun onDestroy() {
        viewModel.releaseAllKeys()
        gamepadInput.dispose()
        HighlightRecorder.onRequestConsent = null
        HighlightRecorder.onBufferingUnavailable = null
        super.onDestroy()
    }
}

/** Small floating controls shown in immersive mode. Mirrors ContentView.swift's `immersiveExitButton`. */
@Composable
private fun ImmersiveExitControls(viewModel: BrowserViewModel, modifier: Modifier = Modifier) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FloatingButton(Icons.Filled.Keyboard) { viewModel.keyboardVisible = !viewModel.keyboardVisible }
        FloatingButton(Icons.Filled.FullscreenExit) { viewModel.immersive = false }
    }
}

@Composable
private fun FloatingButton(icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(38.dp)
            .background(Color.Black.copy(alpha = 0.45f), androidx.compose.foundation.shape.CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        IconButton(onClick = onClick) {
            Icon(icon, contentDescription = null, tint = GB.text.copy(alpha = 0.9f))
        }
    }
}

/** Full-screen lock overlay shown while [BrowserViewModel.isLocked]. Mirrors ContentView.swift's `lockScreen`. */
@Composable
private fun LockScreen(onUnlockClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(GB.background),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Filled.Lock,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.padding(bottom = 16.dp),
            )
            Text(
                loc("GameBrowserはロックされています", "GameBrowser is locked"),
                color = Color.White,
                fontSize = 15.sp,
                modifier = Modifier.padding(bottom = 16.dp),
            )
            Button(onClick = onUnlockClick) {
                Text(loc("ロック解除", "Unlock"))
            }
        }
    }
}
