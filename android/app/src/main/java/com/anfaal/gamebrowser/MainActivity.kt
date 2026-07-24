package com.anfaal.gamebrowser

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
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
        permissionLauncher.launch(permissions.toTypedArray())

        // Mirrors ContentView's `@State private var isLocked = UserDefaults...` cold-start
        // check; onResume() below immediately attempts to clear it via biometrics (or,
        // if none are enrolled, AppLock.authenticate's own "don't lock the user out" fallback).
        if (viewModel.appLockEnabled) viewModel.isLocked = true

        setContent {
            var showTabs by remember { mutableStateOf(false) }
            var showSettings by remember { mutableStateOf(false) }

            Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
                Column(modifier = Modifier.fillMaxSize()) {
                    if (!viewModel.toolbarOnBottom) {
                        BrowserToolbar(viewModel, onTabsClick = { showTabs = true }, onSettingsClick = { showSettings = true })
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

                    ControlBar(viewModel)

                    if (viewModel.toolbarOnBottom) {
                        BrowserToolbar(viewModel, onTabsClick = { showTabs = true }, onSettingsClick = { showSettings = true })
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
            }

            LaunchedEffect(Unit) {
                viewModel.applyKeyboardSuppression()
            }
        }
    }

    override fun onResume() {
        super.onResume()
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
        super.onDestroy()
    }
}

/** Full-screen lock overlay shown while [BrowserViewModel.isLocked]. Mirrors ContentView.swift's `lockScreen`. */
@Composable
private fun LockScreen(onUnlockClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.94f)),
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
