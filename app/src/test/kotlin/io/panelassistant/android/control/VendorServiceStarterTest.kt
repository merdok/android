package io.panelassistant.android.control

import io.panelassistant.android.device.VendorService
import java.util.concurrent.Executor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VendorServiceStarterTest {
    private val stargate = VendorService("cloud.shelly.stargate", "cloud.shelly.stargate.activities.MainActivity", 8021)

    /** A panel whose vendor service opens its port [opensAfterPolls] probes after the vendor start. */
    private class Panel(
        var listening: Boolean = false,
        var overlays: Boolean = true,
        var interactive: Boolean = true,
        var opensAfterPolls: Int? = 2,
    ) : VendorServiceStarter.Seams {
        val events = mutableListOf<String>()
        var clock = 0L
        private var probesSinceStart: Int? = null

        override fun listening(port: Int): Boolean {
            probesSinceStart = probesSinceStart?.plus(1)
            val opened = opensAfterPolls?.let { n -> probesSinceStart?.let { it > n } } == true
            if (opened) listening = true
            return listening
        }
        override fun canDrawOverlays() = overlays
        override fun interactive() = interactive
        override fun addCover(): Boolean { events += "cover"; return true }
        override fun removeCover() { events += "uncover" }
        override fun startVendor(service: VendorService) { events += "start:${service.className}"; probesSinceStart = 0 }
        override fun bringDashboardBack() { events += "dashboard" }
        override fun now() = clock
        override fun sleep(ms: Long) { clock += ms }
        override fun stopping() = false
    }

    private val direct = Executor { it.run() }
    private fun starter(panel: Panel, service: VendorService? = stargate) = VendorServiceStarter(service, panel, direct)

    @Test fun noDeclaredServiceDoesNothing() {
        val panel = Panel()
        val starter = starter(panel, service = null)
        starter.request()
        assertEquals(emptyList<String>(), panel.events)
        assertEquals(VendorServiceStarter.State.NONE, starter.state)
        assertEquals("[vendor-service] port=- state=skipped:none attempts=0", starter.diagnosticLine())
    }

    @Test fun anAlreadyListeningServiceIsLeftAlone() {
        val panel = Panel(listening = true)
        val starter = starter(panel)
        starter.request()
        assertEquals(emptyList<String>(), panel.events)
        assertEquals("[vendor-service] port=8021 state=up attempts=0", starter.diagnosticLine())
    }

    @Test fun withoutTheOverlayGrantTheVendorScreenIsNeverShown() {
        val panel = Panel(overlays = false)
        val starter = starter(panel)
        starter.request()
        assertEquals(emptyList<String>(), panel.events)
        assertEquals(VendorServiceStarter.State.NO_OVERLAY, starter.state)
    }

    @Test fun aDarkScreenDefersToTheNextScreenOn() {
        val panel = Panel(interactive = false)
        val starter = starter(panel)
        starter.request()
        assertEquals(emptyList<String>(), panel.events)
        assertEquals(VendorServiceStarter.State.DEFERRED, starter.state)

        panel.interactive = true
        starter.request()
        assertEquals(VendorServiceStarter.State.STARTED, starter.state)
    }

    @Test fun theVendorStartsUnderTheCoverAndTheDashboardReturnsBeforeItLifts() {
        val panel = Panel(opensAfterPolls = 3)
        val starter = starter(panel)
        starter.request()
        assertEquals(listOf("cover", "start:cloud.shelly.stargate.activities.MainActivity", "dashboard", "uncover"), panel.events)
        assertEquals("[vendor-service] port=8021 state=started attempts=1", starter.diagnosticLine())
        // Three polls at 200 ms before the port opened, then the cover held for 700 ms over the dashboard.
        assertEquals(3 * VendorServiceStarter.POLL_MS + VendorServiceStarter.COVER_HOLD_MS, panel.clock)
    }

    @Test fun aPortThatNeverOpensStillReturnsTheDashboardAndLiftsTheCover() {
        val panel = Panel(opensAfterPolls = null)
        val starter = starter(panel)
        starter.request()
        assertEquals(listOf("cover", "start:cloud.shelly.stargate.activities.MainActivity", "dashboard", "uncover"), panel.events)
        assertEquals(VendorServiceStarter.State.FAILED, starter.state)
        assertTrue(panel.clock >= VendorServiceStarter.START_TIMEOUT_MS)
    }

    @Test fun aThrowingVendorStartStillLiftsTheCoverAndNeverEscapes() {
        val panel = object : VendorServiceStarter.Seams by Panel() {
            val events = mutableListOf<String>()
            override fun addCover(): Boolean { events += "cover"; return true }
            override fun removeCover() { events += "uncover" }
            override fun bringDashboardBack() { events += "dashboard" }
            override fun startVendor(service: VendorService) = throw SecurityException("not exported")
        }
        val starter = VendorServiceStarter(stargate, panel, direct)
        starter.request()
        assertEquals(listOf("cover", "dashboard", "uncover"), panel.events)
        assertEquals(VendorServiceStarter.State.FAILED, starter.state)
    }

    @Test fun startsAreRateLimitedAndCappedPerProcess() {
        val panel = Panel(opensAfterPolls = null)
        val starter = starter(panel)
        fun starts() = panel.events.count { it.startsWith("start:") }

        starter.request()
        assertEquals(1, starts())
        // A screen-on soon after a failed start does not start the vendor again.
        starter.request()
        assertEquals(1, starts())

        repeat(5) {
            panel.clock += VendorServiceStarter.RETRY_INTERVAL_MS
            starter.request()
        }
        assertEquals(VendorServiceStarter.MAX_ATTEMPTS, starts())
        assertEquals("[vendor-service] port=8021 state=failed attempts=3", starter.diagnosticLine())
    }
}
