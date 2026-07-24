package com.anfaal.gamebrowser

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/**
 * Android port of ContentView.swift's app-lock (Face ID) feature.
 *
 * iOS:
 * ```
 * let context = LAContext()
 * guard context.canEvaluatePolicy(.deviceOwnerAuthentication, error: &error) else {
 *     isLocked = false   // no passcode set on the device — don't lock the user out
 *     return
 * }
 * context.evaluatePolicy(.deviceOwnerAuthentication, localizedReason: ...) { success, _ in ... }
 * ```
 *
 * `.deviceOwnerAuthentication` accepts either biometrics (Face ID/Touch ID) OR
 * the device passcode as a fallback. The Android equivalent combination is
 * `BIOMETRIC_STRONG or DEVICE_CREDENTIAL`, which is exactly what's used below.
 *
 * This object only performs authentication; it does not itself decide *when*
 * to lock. That decision (mirroring ContentView's `.onChange(of: scenePhase)`
 * switching on `.background`/`.active`) belongs to MainActivity's lifecycle
 * hooks — see the "INTEGRATION STEPS" notes in the porting report for how to
 * wire onStop()/onResume() to a `isLocked` flag on BrowserViewModel that calls
 * into [authenticate].
 */
object AppLock {

    /** Biometric OR device credential (PIN/pattern/password) — matches `.deviceOwnerAuthentication`. */
    private const val ALLOWED_AUTHENTICATORS = BIOMETRIC_STRONG or DEVICE_CREDENTIAL

    /**
     * Mirrors `LAContext().canEvaluatePolicy(.deviceOwnerAuthentication, error:)`.
     *
     * Returns `false` when the device has neither biometrics nor a screen
     * lock (PIN/pattern/password) enrolled. Callers must treat that exactly
     * like the iOS fallback path: never show a lock screen the user has no
     * way to unlock — just leave the app unlocked, the same way `unlock()`
     * on iOS sets `isLocked = false` and returns immediately in that case.
     */
    fun isAvailable(context: Context): Boolean {
        val result = BiometricManager.from(context).canAuthenticate(ALLOWED_AUTHENTICATORS)
        return result == BiometricManager.BIOMETRIC_SUCCESS
    }

    /**
     * Shows the system biometric / device-credential prompt, mirroring
     * `LAContext.evaluatePolicy(.deviceOwnerAuthentication, localizedReason:)`.
     *
     * Must be called on the main thread with a host [FragmentActivity] that
     * is currently at least STARTED — `BiometricPrompt` hosts its UI as a
     * `DialogFragment`, which is why it needs a `FragmentActivity`/`Fragment`
     * rather than a plain `Activity`/`ComponentActivity` (see the
     * "INTEGRATION STEPS" note about changing `MainActivity`'s superclass).
     *
     * [onResult] fires exactly once per call, with:
     *  - `true` if the device can't evaluate the policy at all (no biometric
     *    or device credential enrolled) — same "don't lock the user out"
     *    fallback as [isAvailable], or if authentication succeeded.
     *  - `false` on any authentication error (including user cancel, and
     *    lockout after too many failed attempts).
     *
     * A single failed attempt (e.g. one bad fingerprint read) is *not*
     * terminal — the system prompt lets the user retry, so [onResult] is not
     * invoked for `onAuthenticationFailed()`; only a real error/cancel ends
     * the flow, matching `evaluatePolicy`'s single completion callback.
     */
    fun authenticate(activity: FragmentActivity, onResult: (Boolean) -> Unit) {
        if (!isAvailable(activity)) {
            onResult(true)
            return
        }

        val executor = ContextCompat.getMainExecutor(activity)
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                onResult(true)
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                // Covers user cancel (ERROR_NEGATIVE_BUTTON / ERROR_USER_CANCELED /
                // ERROR_CANCELED) and lockouts (ERROR_LOCKOUT / ERROR_LOCKOUT_PERMANENT)
                // alike — all of them mean "stay locked", same as `success == false`
                // on the iOS completion handler.
                onResult(false)
            }

            override fun onAuthenticationFailed() {
                // One rejected attempt; the prompt stays open for a retry.
                // Intentionally not forwarded to onResult (see doc above).
            }
        }

        val prompt = BiometricPrompt(activity, executor, callback)
        // NOTE: setNegativeButtonText() must NOT be called when
        // DEVICE_CREDENTIAL is one of the allowed authenticators — the
        // system supplies its own "Cancel"/"Use PIN" affordances in that
        // mode, and BiometricPrompt throws IllegalArgumentException if both
        // are set at once.
        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock GameBrowser")
            .setSubtitle("Authenticate to continue")
            .setAllowedAuthenticators(ALLOWED_AUTHENTICATORS)
            .build()

        prompt.authenticate(promptInfo)
    }
}
