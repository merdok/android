package io.panelassistant.android.state

import io.panelassistant.android.config.SettingsRegistry
import io.panelassistant.android.panelassistant.PanelAssistantReportResult
import io.panelassistant.android.panelassistant.PanelAssistantStateReporter
import io.panelassistant.android.testsupport.PanelRuntimeRig
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A sensor the panel serves but has no reading for reaches Panel Assistant as `unknown` with its reason, through the
 * production state core, its native describe and the reporter's `report_state`; an integration that did not grant
 * `unknown_state` gets `unavailable`, the only form it accepts.
 */
internal class NativeNoReadingTest : PanelRuntimeRig() {
    @Test(timeout = 90_000) fun aSensorWithNoReadingIsDescribedAndReportsWhy() {
        // A light sensor that has reported on this install, with no reading yet in this process.
        val rig = rig(lightChannel = { true }, hasCht8305 = true)
        try {
            rig.config.setHaExposed("humidity", false)
            rig.config.setHaExposed("diag_wifi_rssi", true)
            rig.config.setRaw(requireNotNull(SettingsRegistry.spec("auto_sleep")), "false")
            val native = session(rig)
            val report = fullSync(native, unknownGranted = true)
            assertEquals(
                mapOf(
                    "illuminance" to "waiting",
                    "temperature" to "waiting",
                    "humidity" to "not_shared",
                    "room_temp" to "not_readable",
                    "diag_wifi_rssi" to "not_on_wifi",
                    "diag_wifi_ssid" to "not_shared",
                    "auto_sleep_activity" to "turned_off",
                ),
                mapOf(
                    "illuminance" to reason(report, "illuminance"),
                    "temperature" to reason(report, "temperature"),
                    "humidity" to reason(report, "humidity"),
                    "room_temp" to reason(report, "room_temp"),
                    "diag_wifi_rssi" to reason(report, "diag_wifi_rssi"),
                    "diag_wifi_ssid" to reason(report, "diag_wifi_ssid"),
                    "auto_sleep_activity" to reason(report, "auto_sleep_activity"),
                ),
            )
        } finally { rig.close() }
    }

    @Test(timeout = 90_000) fun anIntegrationThatDidNotGrantTheStateGetsUnavailable() {
        val rig = rig()
        try {
            val native = session(rig)
            val report = fullSync(native, unknownGranted = false)
            val light = observation(report, "illuminance")
            assertEquals("unavailable", light.getString("state"))
            assertFalse(light.has("reason"))
        } finally { rig.close() }
    }

    @Test(timeout = 90_000) fun aReadingThenNoReadingNeverReplaysTheOldValueToTheNextSession() {
        val rig = rig()
        try {
            val native = session(rig)
            rig.core.publishLight(42)
            assertEquals(42, observation(fullSync(native, unknownGranted = true), "illuminance").getInt("value"))

            rig.config.setHaExposed("illuminance", false)
            rig.core.reconcile("illuminance")
            drainStatePump()
            // A reconnect: the new session's full sync must carry the change, not the last value.
            val again = fullSync(native, unknownGranted = true)
            assertEquals("not_shared", reason(again, "illuminance"))
            assertFalse(observation(again, "illuminance").has("value"))
        } finally { rig.close() }
    }

    private fun session(rig: Rig): PanelAssistantStateReporter {
        val native = PanelAssistantStateReporter(log = {})
        rig.core.addStateSink(native.bindShape(rig.core::nativeChannelShape))
        assertTrue(runBlocking { rig.core.observeForNativeHello { true } })
        return native
    }

    private fun fullSync(native: PanelAssistantStateReporter, unknownGranted: Boolean): JSONObject {
        native.open(native.offer().descriptors, unknownGranted)
        val begin = JSONObject(requireNotNull(native.next(1, "session", 0)))
        native.onResult(PanelAssistantReportResult.Acknowledged(1, emptyMap()), 0)
        return begin
    }

    private fun observation(report: JSONObject, channel: String): JSONObject {
        val observations = report.getJSONArray("observations")
        return (0 until observations.length()).map(observations::getJSONObject).single { it.getString("channel") == channel }
    }

    /** The reason, else the state, else "not reported" when the full sync left the channel out. */
    private fun reason(report: JSONObject, channel: String): String {
        val observations = report.getJSONArray("observations")
        val found = (0 until observations.length()).map(observations::getJSONObject)
            .singleOrNull { it.getString("channel") == channel } ?: return "not reported"
        return if (found.getString("state") == "unknown") found.optString("reason", "no reason") else found.getString("state")
    }
}
