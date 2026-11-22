package com.nextgentool.nudgy.ui.components

import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * TEMPORARY DIAGNOSTIC (S4-1 leak investigation, round 8) — delete after use.
 * Empty runTest: if the global ExceptionCollector holds a buffered
 * exception, enter() throws here; the probe rethrows the underlying
 * (suppressed) exception so the CI failure line names the real exception
 * type and its throw site. See com.nextgentool.nudgy.AaaLeakProbeTest.
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
