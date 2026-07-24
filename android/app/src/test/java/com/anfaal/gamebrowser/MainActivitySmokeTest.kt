package com.anfaal.gamebrowser

import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Drives MainActivity's real onCreate()/onStart()/onResume() lifecycle (incl.
 * its Compose setContent{} tree) the way Android itself does on a cold start,
 * on a fresh install with no persisted state. Runs on the plain JVM via
 * Robolectric -- no emulator/device needed -- to catch a launch-time crash in
 * the build instead of only after a user installs a broken APK.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp", sdk = [26, 27, 28, 29, 30, 31, 33, 34])
class MainActivitySmokeTest {

    @Test
    fun `launches without throwing on a fresh install`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java)
        try {
            controller.create().start().resume()
        } finally {
            controller.pause().stop().destroy()
        }
    }
}
