package com.nextgentool.nudgy

import dagger.hilt.android.AndroidEntryPoint
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sprint 1-1 fix — the @AndroidEntryPoint regression guard.
 *
 * Without @AndroidEntryPoint on MainActivity, the first hiltViewModel()
 * call crashes the app ("does not implement GeneratedComponentManager",
 * Crashlytics 2026-11-16, TasksScreen). This test fails if anyone drops
 * the annotation again. No Robolectric needed: the check is pure
 * reflection on the compiled class.
 *
 * The annotation is checked two ways because its runtime visibility
 * depends on its retention: the generated Hilt_MainActivity holder only
 * exists when the annotation is present. Either signal passing means
 * the entry point is in place; both missing means it was dropped.
 */
class MainActivityEntryPointTest {

    @Test
    fun `MainActivity carries the Hilt entry point`() {
        val annotation =
            MainActivity::class.java.getAnnotation(AndroidEntryPoint::class.java)
        val generatedHolder = runCatching {
            Class.forName("com.nextgentool.nudgy.Hilt_MainActivity")
        }.getOrNull()
        assertTrue(
            "MainActivity must carry @AndroidEntryPoint — without it, " +
                "hiltViewModel() crashes on first use (S1-1, 2026-11-16)",
            annotation != null || generatedHolder != null
        )
    }
}
