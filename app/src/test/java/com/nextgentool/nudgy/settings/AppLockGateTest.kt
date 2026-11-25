package com.nextgentool.nudgy.settings

import androidx.biometric.BiometricManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sprint 6-6 (step 4a) — the app-lock gate: in-memory only.
 *
 * Plain JUnit, no Robolectric: the gate is pure logic (a Compose
 * State only so MainActivity recomposes on authenticate — reading
 * and writing it needs no composition).
 *
 * What is NOT covered here: the BiometricPrompt show() path itself
 * (no BiometricPrompt in Robolectric) — CI compile + the batch-end
 * device test, like the Glance rendering and the NavHost wiring.
 * The fail-open decision IS covered below, through the pure
 * [AppLockPrompt.isAuthenticatorAvailable] mapping.
 */
class AppLockGateTest {

    @Test
    fun `starts locked`() {
        val gate = AppLockGate()
        assertFalse(gate.isUnlocked.value)
    }

    @Test
    fun `shouldPrompt is true only when the lock is enabled and the session is locked`() {
        val gate = AppLockGate()
        assertTrue(gate.shouldPrompt(lockEnabled = true))
        assertFalse(gate.shouldPrompt(lockEnabled = false))
    }

    @Test
    fun `authenticating unlocks, and shouldPrompt goes false`() {
        val gate = AppLockGate()
        gate.onAuthenticated()
        assertTrue(gate.isUnlocked.value)
        assertFalse(gate.shouldPrompt(lockEnabled = true))
    }

    @Test
    fun `backgrounding re-locks, so the next return prompts again`() {
        val gate = AppLockGate()
        gate.onAuthenticated()
        assertTrue(gate.isUnlocked.value)
        gate.onBackgrounded()
        assertFalse(gate.isUnlocked.value)
        assertTrue(gate.shouldPrompt(lockEnabled = true))
    }

    @Test
    fun `authenticator available only on BIOMETRIC_SUCCESS`() {
        assertTrue(
            AppLockPrompt.isAuthenticatorAvailable(BiometricManager.BIOMETRIC_SUCCESS)
        )
        assertFalse(
            AppLockPrompt.isAuthenticatorAvailable(
                BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE
            )
        )
        assertFalse(
            AppLockPrompt.isAuthenticatorAvailable(
                BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE
            )
        )
        assertFalse(
            AppLockPrompt.isAuthenticatorAvailable(
                BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED
            )
        )
        assertFalse(
            AppLockPrompt.isAuthenticatorAvailable(
                BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED
            )
        )
    }
}
