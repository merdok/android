package io.panelassistant.android.state

import io.panelassistant.android.control.UsbDebuggingObserver
import io.panelassistant.android.panelassistant.PanelAssistantReportResult
import io.panelassistant.android.panelassistant.PanelAssistantShadowReporter
import io.panelassistant.android.testsupport.PanelRuntimeRig
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

internal class NativeUsbDebuggingTest : PanelRuntimeRig() {
    @Test fun unreadableUsbDebuggingRemainsAnOfferedDiagnosticOnRootlessPanels() {
        val rig = rig()
        try {
            val native = PanelAssistantShadowReporter(log = {})
            rig.core.addStateSink(native.bindShape(rig.core::nativeChannelShape))
            assertTrue(runBlocking { rig.core.observeForNativeHello { true } })
            val descriptor = native.offer().descriptors.single { it.channel == "diag_usb_debugging" }
            assertEquals("binary_sensor", descriptor.platform)
            assertEquals("usb_debugging", descriptor.translationKey)
            assertEquals("usb_debugging", descriptor.uniqueSuffix)
            assertEquals("diagnostic", descriptor.entityCategory)
            assertTrue(descriptor.enabledDefault)
            assertEquals(null, descriptor.deviceClass)
            assertFalse("diag_usb_debugging" in rig.core.nativeChannelShape().unsupported)
            native.open(native.offer().descriptors)
            val report = JSONObject(requireNotNull(native.next(1, "session", 0)))
            val observations = report.getJSONArray("observations")
            val usb = (0 until observations.length()).map(observations::getJSONObject)
                .single { it.getString("channel") == "diag_usb_debugging" }
            assertEquals("unavailable", usb.getString("state"))
        } finally { rig.close() }
    }

    @Test fun nativeReportsReplaceEnabledUsbDebuggingWithUnavailableThenDisabled() {
        var setting: String? = "1"
        val observer = UsbDebuggingObserver(sdkInt = { 29 }, readSetting = { setting })
        val native = PanelAssistantShadowReporter(log = {})
        val converger = StateConverger()
        converger.register(StateConverger.Channel("diag_usb_debugging", observer::observation))
        converger.addSink(native.bind { converger.keys() })
        converger.reconcileAll()
        native.open(native.offer().descriptors)
        val begin = JSONObject(requireNotNull(native.next(1, "session", 0)))
        val on = begin.getJSONArray("observations").getJSONObject(0)
        assertEquals("known", on.getString("state"))
        assertTrue(on.getBoolean("value"))
        native.onResult(PanelAssistantReportResult.Acknowledged(1, emptyMap()), 0)
        native.next(2, "session", 0)
        native.onResult(PanelAssistantReportResult.Acknowledged(2, emptyMap()), 0)

        setting = null
        converger.reconcileAll()
        val unavailable = JSONObject(requireNotNull(native.next(3, "session", 0)))
            .getJSONArray("observations").getJSONObject(0)
        assertEquals("unavailable", unavailable.getString("state"))
        assertFalse(unavailable.has("value"))
        native.onResult(PanelAssistantReportResult.Acknowledged(3, emptyMap()), 0)

        setting = "0"
        converger.reconcileAll()
        val off = JSONObject(requireNotNull(native.next(4, "session", 0)))
            .getJSONArray("observations").getJSONObject(0)
        assertEquals("known", off.getString("state"))
        assertFalse(off.getBoolean("value"))
        converger.close()
    }
}
