package com.anfaal.gamebrowser

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Constructs the real BrowserViewModel (and, through it, TabManager) the same
 * way the Activity does on a cold start, on a fresh Application with no
 * persisted SharedPreferences/tabs.json — i.e. exactly the "just installed,
 * first launch" state that has crashed in the field. Runs on the plain JVM
 * via Robolectric (no emulator/device needed), so a regression here fails
 * the build instead of only surfacing after a user installs a broken APK.
 */
@RunWith(RobolectricTestRunner::class)
class BrowserViewModelSmokeTest {

    @Test
    fun `constructs and creates a first tab without throwing on a fresh install`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val viewModel = BrowserViewModel(app)
        assert(viewModel.tabManager.tabs.isNotEmpty()) { "expected a first tab to be created" }
    }
}
