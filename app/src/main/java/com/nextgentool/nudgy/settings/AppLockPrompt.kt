package com.nextgentool.nudgy.settings

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.nextgentool.nudgy.R

/**
 * Sprint 6-6 (step 4a) — the thin system BiometricPrompt wrapper
 * (spec §2 Q6=(a), §8).
 *
 * The prompt is the SYSTEM one, never an app-built PIN screen:
 * a strong biometric OR the device credential (PIN / pattern /
 * password). There is no app-side "forgot" path — if the user
 * forgets, the system PIN/pattern opens it. Notifications are
 * untouched by the lock (they arrive either way); only opening the
 * app prompts.
 *
 * Two honest limits, both by design:
 * - The prompt needs a FragmentActivity (BiometricPrompt has no
 *   ComponentActivity constructor) — that is why MainActivity
 *   extends FragmentActivity since this step.
 * - If the device has NO usable authenticator (no biometric
 *   enrolled AND no secure lock screen), the prompt cannot show
 *   at all — MainActivity then fails OPEN (unlocks for the
 *   session) rather than bricking the user out of their own data
 *   with no way in. [isAuthenticatorAvailable] is the pure
 *   decision behind that call, unit-tested.
 *
 * No negative button is set on purpose: with DEVICE_CREDENTIAL
 * allowed, setNegativeButtonText throws IllegalArgumentException.
 * A user cancel arrives as onAuthenticationError
 * (ERROR_USER_CANCELED) and the app stays locked behind the
 * overlay, whose Unlock button retries.
 *
 * The show() path itself is device-gated (no BiometricPrompt in
 * Robolectric) — verified by CI compile + the batch-end device
 * test, like the Glance rendering and the NavHost wiring.
 */
object AppLockPrompt {

    /**
     * The authenticators the spec allows: a strong biometric OR the
     * device credential. BIOMETRIC_WEAK is deliberately excluded.
     */
    val AUTHENTICATORS: Int =
        BiometricManager.Authenticators.BIOMETRIC_STRONG or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL

    /**
     * Pure mapping of a BiometricManager.canAuthenticate result:
     * only BIOMETRIC_SUCCESS means the prompt can be shown.
     */
    fun isAuthenticatorAvailable(result: Int): Boolean =
        result == BiometricManager.BIOMETRIC_SUCCESS

    /** Whether the system prompt can be shown on this device now. */
    fun canAuthenticate(context: Context): Boolean =
        isAuthenticatorAvailable(
            BiometricManager.from(context).canAuthenticate(AUTHENTICATORS)
        )

    /**
     * Shows the system prompt. [onSuccess] runs on the main
     * executor when the user authenticates. [onError] covers user
     * cancel and lockout; the caller keeps the app locked.
     */
    fun show(
        activity: FragmentActivity,
        onSuccess: () -> Unit,
        onError: (errorCode: Int, errString: CharSequence) -> Unit = { _, _ -> }
    ) {
        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(
                    result: BiometricPrompt.AuthenticationResult
                ) {
                    onSuccess()
                }

                override fun onAuthenticationError(
                    errorCode: Int,
                    errString: CharSequence
                ) {
                    onError(errorCode, errString)
                }
            }
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(activity.getString(R.string.app_lock_title))
            .setSubtitle(activity.getString(R.string.app_lock_subtitle))
            .setAllowedAuthenticators(AUTHENTICATORS)
            .build()
        prompt.authenticate(info)
    }
}
