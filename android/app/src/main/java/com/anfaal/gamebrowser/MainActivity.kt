package com.anfaal.gamebrowser

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged

class MainActivity : ComponentActivity() {
    private val viewModel: BrowserViewModel by viewModels()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { /* WebView's onPermissionRequest re-checks grants at call time */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        permissionLauncher.launch(
            arrayOf(
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.ACCESS_FINE_LOCATION,
            ),
        )

        setContent {
            Column(modifier = Modifier.fillMaxSize().background(Color.Black)) {
                BrowserToolbar(viewModel)

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

                if (viewModel.keyboardVisible) {
                    GamepadKeyboard(viewModel)
                }

                ControlBar(viewModel)
            }

            LaunchedEffect(Unit) {
                viewModel.applyKeyboardSuppression()
            }
        }
    }

    override fun onDestroy() {
        viewModel.releaseAllKeys()
        super.onDestroy()
    }
}
