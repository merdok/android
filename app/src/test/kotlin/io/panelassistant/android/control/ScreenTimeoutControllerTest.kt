package io.panelassistant.android.control

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenTimeoutControllerTest {
    @Test fun enablingSavesTheFiniteTimeoutAndVerifiesNever() {
        var timeout = 60_000
        var saved = -1
        val controller = ScreenTimeoutController(read = { timeout }, write = { timeout = it; true })

        val result = controller.apply(enabled = true, savedTimeoutMs = saved) { saved = it }

        assertEquals(60_000, saved)
        assertEquals(NEVER_SCREEN_TIMEOUT_MS, timeout)
        assertEquals(NEVER_SCREEN_TIMEOUT_MS, result.observedMs)
        assertTrue(result.effective)
    }

    @Test fun rejectedWriteIsReportedAsIneffective() {
        val controller = ScreenTimeoutController(read = { 60_000 }, write = { false })

        val result = controller.apply(enabled = true, savedTimeoutMs = -1) { }

        assertFalse(result.writeAccepted)
        assertFalse(result.effective)
        assertEquals(60_000, result.observedMs)
    }

    @Test fun rejectedRedundantWriteIsStillEffectiveWhenReadbackAlreadyMatches() {
        val controller = ScreenTimeoutController(read = { NEVER_SCREEN_TIMEOUT_MS }, write = { false })

        val result = controller.apply(enabled = true, savedTimeoutMs = 60_000) { }

        assertFalse(result.writeAccepted)
        assertTrue(result.effective)
        assertEquals(NEVER_SCREEN_TIMEOUT_MS, result.observedMs)
    }

    @Test fun acceptedWriteWithMismatchingReadbackIsReportedAsIneffective() {
        val controller = ScreenTimeoutController(read = { 60_000 }, write = { true })

        val result = controller.apply(enabled = true, savedTimeoutMs = -1) { }

        assertTrue(result.writeAccepted)
        assertFalse(result.effective)
        assertEquals(60_000, result.observedMs)
    }

    /** While the screen-timeout screen-off has the panel asleep, the live value is its short timeout.
     *  Toggling prevent-idle-dim then must never save that as the firmware default, and the new choice
     *  lands in the record the wake restores rather than in the live setting. */
    @Test fun aToggleWhileTheScreenTimeoutOffHoldsTheSettingNeverSavesTheShortTimeout() {
        var timeout = 1_000
        var saved = -1
        val displaced = InMemoryDisplacedScreenTimeout(120_000)
        val controller = ScreenTimeoutController(read = { timeout }, write = { timeout = it; true })

        val on = controller.apply(enabled = true, savedTimeoutMs = saved, displaced = displaced) { saved = it }
        assertEquals("the real timeout is remembered, not the short one", 120_000, saved)
        assertEquals(NEVER_SCREEN_TIMEOUT_MS, displaced.displacedMs)
        assertEquals("the sleeping panel's live timeout is left alone", 1_000, timeout)
        assertTrue(on.effective)

        controller.apply(enabled = false, savedTimeoutMs = saved, displaced = displaced) { saved = it }
        assertEquals("turning it off while asleep restores the saved default on wake", 120_000, displaced.displacedMs)
        assertEquals(120_000, saved)
        assertEquals(1_000, timeout)
    }

    @Test fun disablingRestoresSavedTimeoutOrTheSafeDefault() {
        var timeout = NEVER_SCREEN_TIMEOUT_MS
        val controller = ScreenTimeoutController(read = { timeout }, write = { timeout = it; true })

        assertEquals(120_000, controller.apply(false, 120_000) { }.observedMs)
        timeout = NEVER_SCREEN_TIMEOUT_MS
        assertEquals(60_000, controller.apply(false, -1) { }.observedMs)
    }

    @Test fun diagnosticsDistinguishEffectiveMismatchAndDisabledState() {
        assertEquals("on · timeout never", preventIdleDimDiagnostic(true, NEVER_SCREEN_TIMEOUT_MS))
        assertEquals("on · timeout 60s (not applied)", preventIdleDimDiagnostic(true, 60_000))
        assertEquals("off · timeout 60s", preventIdleDimDiagnostic(false, 60_000))
        assertEquals("on · timeout unknown", preventIdleDimDiagnostic(true, -1))
    }
}
