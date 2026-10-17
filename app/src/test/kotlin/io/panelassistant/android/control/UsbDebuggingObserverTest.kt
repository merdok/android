package io.panelassistant.android.control

import io.panelassistant.android.state.StateConverger
import io.panelassistant.android.state.StateConverger.Observation
import org.junit.Assert.assertEquals
import org.junit.Test

class UsbDebuggingObserverTest {
    @Test fun readsUsbSettingAndClearsUnreadableStateRatherThanClaimingOff() {
        var raw: String? = "1"
        val observer = UsbDebuggingObserver(sdkInt = { 29 }, readSetting = { raw })
        assertEquals(Observation.Known("ON"), observer.observation())
        assertEquals("on", observer.statusText())
        raw = "0"
        assertEquals(Observation.Known("OFF"), observer.observation())
        assertEquals("off", observer.statusText())
        for (unreadable in listOf(null, "", "2", "true")) {
            raw = unreadable
            assertEquals(Observation.NoReading(StateConverger.NoReadingReason.NOT_READABLE), observer.observation())
            assertEquals("unknown", observer.statusText())
        }
        val denied = UsbDebuggingObserver(sdkInt = { 29 }, readSetting = { throw SecurityException("denied") })
        assertEquals(Observation.NoReading(StateConverger.NoReadingReason.NOT_READABLE), denied.observation())
        assertEquals("unknown", denied.statusText())
    }

    @Test fun redactedAndroidVersionsNeverReportTheirSyntheticZeroAsOff() {
        for ((sdk, preview) in listOf(37 to 0, 38 to 0, 36 to 1)) {
            val observer = UsbDebuggingObserver(
                sdkInt = { sdk }, previewSdkInt = { preview }, readSetting = { "0" },
            )
            assertEquals(Observation.NoReading(StateConverger.NoReadingReason.NOT_READABLE), observer.observation())
            assertEquals("unknown", observer.statusText())
        }
        assertEquals(
            Observation.Known("OFF"),
            UsbDebuggingObserver(sdkInt = { 36 }, readSetting = { "0" }).observation(),
        )
    }
}
