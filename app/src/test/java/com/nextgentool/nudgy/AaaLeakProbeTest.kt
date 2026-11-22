package com.nextgentool.nudgy

import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * TEMPORARY DIAGNOSTIC (S4-1 leak investigation, round 8) — delete after use.
 *
 * The suite fails only on BoardDragTest with UncaughtExceptionsBeforeTest
 * (TestScope.kt:238 = runTest enter()). That exception is internal and
 * carries the UNDERLYING exception as a suppressed exception, which the CI
 * log never shows. This probe runs an empty runTest: if the global
 * ExceptionCollector already holds a buffered exception, enter() throws
 * here instead, and the probe rethrows the underlying (suppressed)
 * exception — so the CI failure line names the real exception type and
 * its throw site. It also consumes the poison, so if BoardDragTest then
 * passes, the leaker runs before this probe.
 */
class AaaLeakProbeTest {

    @Test
    fun probeLeakCollector() {
        try {
            runTest { }
        } catch (e: IllegalStateException) {
            val suppressed = e.suppressed
            if (suppressed.isNotEmpty()) {
                throw suppressed[0]
            }
            throw e
        }
    }
}
