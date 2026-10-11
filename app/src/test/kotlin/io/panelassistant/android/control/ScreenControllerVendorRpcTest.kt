package io.panelassistant.android.control

import io.panelassistant.android.device.ScreenOff
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The vendor-rpc screen-off route through [ScreenController]'s public transitions, over a fake Stargate
 * whose backlight node behaves as measured on the bench X2i: the RPC writes the node behind Android's
 * back, and Android re-drives the node only when `SCREEN_BRIGHTNESS` changes.
 */
class ScreenControllerVendorRpcTest {
    private val vendor = FakeVendorScreen()
    private val backlight = SettingBacklight(vendor, setting = 128)
    private val power = FakeScreenPower()
    private val wakeTap = FakeWakeTap()

    private fun controller() = ScreenController(
        backlight, power, FakeRootShell(runResult = false), FakeDaemon(), wakeTap, ScreenOff.VENDOR_RPC,
        nap = {}, vendor = vendor,
    )

    @Test fun anOffBlanksTheBacklightWhileAndroidStaysAwakeAndATapWakesItWithoutReachingTheDashboard() {
        val sc = controller()

        sc.sleep()
        assertEquals(0, vendor.node)
        assertTrue("Android stays awake", power.interactive)
        assertTrue("the tap is captured by the overlay, not the dashboard", wakeTap.armed)
        assertTrue(sc.looksDark())
        assertEquals(RouteSelection(ScreenOff.VENDOR_RPC, ScreenOff.VENDOR_RPC, ScreenController.REASON_DECLARED), sc.routeSelection())
        assertEquals("no Android sleep is attempted", 0, power.timeoutWrites)

        wakeTap.fireTap()
        assertTrue(vendor.node > 0)
        assertFalse(sc.isIntendedOff())
        assertFalse(wakeTap.armed)
        assertEquals(ScreenController.REASON_DECLARED, sc.routeSelection().reason)
    }

    @Test fun repeatedOffsAndOnsConverge() {
        val sc = controller()
        sc.sleep()
        sc.sleep()
        assertEquals(0, vendor.node)
        assertTrue(sc.isIntendedOff())
        sc.wake()
        sc.wake()
        assertTrue(vendor.node > 0)
        assertFalse(sc.isIntendedOff())
        assertEquals("a lit panel is not woken again", WakeOutcome.ALREADY_ON, sc.ensureOn())
    }

    @Test fun aVendorThatDoesNotAnswerFallsBackToAndroidsScreenTimeout() {
        vendor.answers = false
        power.canWrite = true
        val sc = controller()

        sc.sleep()
        assertFalse("Android sleeps instead", power.interactive)
        assertFalse("a sleeping Android cannot deliver a tap to the overlay", wakeTap.armed)
        assertEquals(ScreenOff.SCREEN_TIMEOUT, sc.routeSelection().selected)
        assertTrue(sc.routeSelection().reason.contains("vendor screen RPC was refused"))
    }

    /** An answer alone is not an off: the node must read dark. Whatever the vendor half-applied is undone. */
    @Test fun anAnswerTheNodeDoesNotConfirmIsUndoneAndDimsWhenNothingElseCanSleep() {
        for (unconfirmed in listOf(FakeVendorScreen(blanks = false), FakeVendorScreen(readable = false))) {
            val sc = ScreenController(
                SettingBacklight(unconfirmed), power, FakeRootShell(runResult = false), FakeDaemon(), wakeTap,
                ScreenOff.VENDOR_RPC, nap = {}, vendor = unconfirmed,
            )
            sc.sleep()
            assertEquals("asked to relight after the unconfirmed off", listOf(false, true), unconfirmed.calls)
            assertTrue("never left dark", unconfirmed.node > 0)
            assertFalse(wakeTap.armed)
            assertEquals(ScreenOff.BRIGHTNESS_ZERO, sc.routeSelection().selected)
            assertTrue(sc.routeSelection().reason.contains("never-blank floor"))
        }
    }

    @Test fun withoutTheOverlayTheVendorIsNeverAskedToGoDark() {
        wakeTap.canArm = false
        power.canWrite = true
        val sc = controller()

        sc.sleep()
        assertTrue(vendor.calls.isEmpty())
        assertEquals(128, vendor.node)
        assertEquals(ScreenOff.SCREEN_TIMEOUT, sc.routeSelection().selected)
    }

    /** Stargate died while the panel was dark: the app relights the node it cannot write by changing the setting. */
    @Test fun aWakeTheVendorDoesNotAnswerRelightsThroughTheSetting() {
        val sc = controller()
        sc.sleep()
        assertEquals(0, vendor.node)

        vendor.answers = false
        wakeTap.fireTap()
        assertTrue("lit although the setting already held the saved level", vendor.node > 0)
        assertEquals("the app's level is back", 128, backlight.setting)
        assertFalse(sc.isIntendedOff())
    }

    @Test fun aDarkNodeNobodyAskedForIsRelitThroughTheVendor() {
        val sc = controller()
        vendor.node = 0

        assertTrue(sc.recoverUnexpectedDark())
        assertEquals(listOf(true), vendor.calls)
        assertTrue(vendor.node > 0)
    }

    @Test fun anUnreadableNodeIsUnknownSoNothingIsRelitOnAGuess() {
        vendor.readable = false
        val sc = controller()
        assertNull(sc.observedDark())
        assertFalse(sc.recoverUnexpectedDark())
        assertTrue(vendor.calls.isEmpty())
    }
}
