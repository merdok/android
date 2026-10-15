package io.panelassistant.android.control

import io.panelassistant.android.device.ScreenOff
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A screen-timeout sleep writes a temporary short SCREEN_OFF_TIMEOUT and clears stay-on for the whole
 * sleep. Power safety must judge the panel's configured values (the displaced record), not those
 * temporary ones, so a guarded panel stays SAFE while asleep and after wake.
 */
class PowerSafetyScreenTimeoutSleepTest {
    private val power = FakeScreenPower().apply {
        canWrite = true
        canWriteSecure = true
        stayOnMask = 7
        timeoutMs = Int.MAX_VALUE
    }
    private val displaced = InMemoryDisplacedScreenTimeout()
    private val screen = ScreenController(
        FakeBacklight(200), power, FakeRootShell(runResult = false), FakeDaemon(), FakeWakeTap(),
        ScreenOff.SCREEN_TIMEOUT, nap = {}, displacedTimeout = displaced, touchCount = { 0L },
    )

    /** PowerSafetyController.observe needs a real Context; its inputs and substitution rule are shared here. */
    private fun observed() = PowerSafetyObservation(
        keepAwakeConfigured = true, wakeLockHeld = true, wifiLockRequired = false, wifiLockHeld = true,
        preventIdleDimConfigured = true,
        screenOffTimeoutMs = configuredDuringSleep(screen.heldSleepRecord()?.displacedMs ?: -1, power.screenOffTimeoutMs()),
        interactive = power.isInteractive(), pluggedMask = 1,
        stayOnWhilePluggedIn = configuredDuringSleep(screen.heldSleepRecord()?.displacedStayOnMask ?: -1, power.stayOnWhilePluggedInMask()),
        deviceIdleMode = false, ignoringBatteryOptimizations = false,
        screenOffMechanism = "screen_timeout",
        screenOffSelected = screen.routeSelection().selected?.name?.lowercase(),
        screenOffReason = screen.routeSelection().reason,
    )

    private fun assertSafe(phase: String) {
        val assessment = PowerSafetyPolicy.assess(observed())
        val advisory = PowerSafetyAdvisoryPolicy.evaluate(assessment, PowerRepairCapability.APP_ONLY, null)
        assertEquals("$phase: level", PowerRiskLevel.SAFE, assessment.level)
        assertEquals("$phase: action", PowerSafetyAdvisoryAction.NONE, advisory.action)
        assertFalse("$phase: reasons ${assessment.reasonCodes}", "native_screen_timeout_finite" in assessment.reasonCodes)
    }

    @Test fun aGuardedPanelStaysSafeWhileAScreenTimeoutSleepHoldsTheShortTimeoutAndAfterWake() {
        screen.sleep()
        assertEquals("precondition: the SCREEN_TIMEOUT route displaced the timeout", Int.MAX_VALUE, displaced.displacedMs)
        assertFalse("precondition: Android is asleep", power.isInteractive())
        assertSafe("asleep")

        screen.wake()
        power.interactive = true
        assertSafe("awake")
    }

    @Test fun aWakeWhoseRestoreFailedReportsTheShortTimeoutItLeftBehind() {
        screen.sleep()
        assertEquals("precondition: the SCREEN_TIMEOUT route displaced the timeout", Int.MAX_VALUE, displaced.displacedMs)
        power.canWrite = false
        power.canWriteSecure = false

        screen.wake()
        power.interactive = true

        assertEquals("precondition: the failed restore kept its record", Int.MAX_VALUE, displaced.displacedMs)
        assertReportsTheShortTimeout("awake")

        // A retried off is refused by the same missing grants and falls back to a dim: the panel still
        // runs the short timeout, and the kept record is still not its configured state.
        screen.sleep()
        assertTrue("precondition: the retried off is held", screen.isIntendedOff())
        assertEquals("precondition: the record is still kept", Int.MAX_VALUE, displaced.displacedMs)
        assertReportsTheShortTimeout("refused retry")
    }

    @Test fun aDeviceAdminFallbackAfterAFailedRestoreReportsTheShortTimeout() {
        screen.sleep()
        power.canWrite = false
        power.canWriteSecure = false
        screen.wake()
        power.interactive = true
        power.adminActive = true

        screen.sleep()

        assertFalse("precondition: the device-admin lock slept Android", power.isInteractive())
        assertEquals("precondition: the record is still kept", Int.MAX_VALUE, displaced.displacedMs)
        assertReportsTheShortTimeout("device-admin fallback")
    }

    private fun assertReportsTheShortTimeout(phase: String) {
        val assessment = PowerSafetyPolicy.assess(observed())
        assertNotEquals("$phase: level", PowerRiskLevel.SAFE, assessment.level)
        assertTrue("$phase: reasons ${assessment.reasonCodes}", "native_screen_timeout_finite" in assessment.reasonCodes)
    }
}
