package io.panelassistant.android.state

import io.panelassistant.android.testsupport.PanelRuntimeRig
import io.panelassistant.android.AutoSleepActivitySnapshot
import io.panelassistant.android.control.ZigbeeContainmentResult
import io.panelassistant.android.control.ZigbeeHealthSnapshot
import io.panelassistant.android.control.ZigbeeHealthState
import io.panelassistant.android.input.ButtonBus
import io.panelassistant.android.panelassistant.PanelAssistantReportResult
import io.panelassistant.android.panelassistant.PanelAssistantStateReporter
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/** Button events, auto-sleep activity, and Zigbee health, reported on the native transport. */
internal class NativeReportedChannelsTest : PanelRuntimeRig() {
    @Test fun aButtonPressIsReportedAsANativeEvent() {
        val rig = rig()
        try {
            val native = bound(rig)
            rig.start()
            assertTrue(runBlocking { rig.core.observeForNativeHello { true } })
            val button = native.offer().descriptors.single { it.channel == "button" }
            assertEquals("event", button.platform)
            assertTrue("keycode_home" in button.options!!)
            fullSync(native)

            ButtonBus.emit("KEYCODE_HOME")
            val event = JSONObject(requireNotNull(native.next(4, "native-session", 0L)))
            assertEquals("panel_assistant/report_event", event.getString("type"))
            assertEquals("native-session", event.getString("session"))
            assertEquals("button", event.getString("channel"))
            assertEquals("keycode_home", event.getString("event_type"))
            ButtonBus.emit("KEYCODE_BACK")
            val second = JSONObject(requireNotNull(native.next(5, "native-session", 0L)))
            assertTrue("event ids only grow", second.getLong("event_id") > event.getLong("event_id"))
        } finally { rig.close() }
    }

    @Test fun autoSleepActivityIsReportedWithItsAttributes() {
        val rig = rig(
            autoSleepActivity = {
                AutoSleepActivitySnapshot(
                    holdingAwake = true, policyHealthy = true, reason = "presence", learnedDelay = "90s",
                    sourceCount = 2, phase = "holding", manualSuppression = true,
                )
            },
        )
        try {
            val native = bound(rig)
            rig.start()
            assertTrue(runBlocking { rig.core.observeForNativeHello { true } })
            val observation = observation(fullSync(native), "auto_sleep_activity")
            assertEquals("known", observation.getString("state"))
            assertEquals(true, observation.get("value"))
            val attributes = observation.getJSONObject("attributes")
            assertEquals("presence", attributes.getString("reason"))
            assertEquals("90s", attributes.getString("learned_delay"))
            assertEquals(2, attributes.getInt("source_count"))
            assertEquals("holding", attributes.getString("phase"))
            assertEquals(true, attributes.getBoolean("manual_suppression"))
        } finally { rig.close() }
    }

    @Test fun zigbeeGatewayHealthCarriesItsStateAndAttributes() {
        val native = PanelAssistantStateReporter(log = {})
        val sink = native.bind { listOf("zigbee_gateway_health") }
        val health = ZigbeeHealthSnapshot(
            state = ZigbeeHealthState.DEGRADED_HIGH_CPU,
            gatewayCpu = 87,
            containment = ZigbeeContainmentResult.PARTIAL,
        )
        sink("zigbee_gateway_health", StateConverger.Observation.Known(health.state.wireValue, health.attributesJson()))
        val descriptor = native.offer().descriptors.single { it.channel == "zigbee_gateway_health" }
        assertEquals("sensor", descriptor.platform)
        assertEquals("zigbee_gateway_health", descriptor.uniqueSuffix)
        assertEquals("diagnostic", descriptor.entityCategory)
        assertEquals(ZigbeeHealthState.entries.map { it.wireValue }, descriptor.options)

        val observation = observation(fullSync(native), "zigbee_gateway_health")
        assertEquals("degraded_high_cpu", observation.getString("value"))
        val attributes = observation.getJSONObject("attributes")
        assertEquals(JSONObject(health.attributesJson()).keys().asSequence().toSet(), attributes.keys().asSequence().toSet())
        assertEquals(87, attributes.getInt("gateway_cpu_percent"))
        assertEquals("partial", attributes.getString("containment_result"))
        assertTrue(attributes.isNull("firmware"))
    }

    @Test fun aChangeToOnlyTheOutageFloorReReportsTheCountWithItsNewAttribute() {
        val counts = java.util.concurrent.atomic.AtomicReference(io.panelassistant.android.control.WifiOutageCounts(last24h = 200))
        val rig = rig(wifiOutages = { counts.get() })
        try {
            val native = bound(rig)
            rig.start()
            assertTrue(runBlocking { rig.core.observeForNativeHello { true } })
            val first = observation(fullSync(native), "diag_wifi_outages_24h")
            assertEquals(false, first.getJSONObject("attributes").getBoolean("is_lower_bound"))

            counts.set(io.panelassistant.android.control.WifiOutageCounts(last24h = 200, saturated = true))
            rig.core.reconcile("diag_wifi_outages_24h")
            val delta = JSONObject(requireNotNull(native.next(4, "native-session", 0L)))
            assertEquals(setOf("diag_wifi_outages_24h"), channels(delta))
            assertEquals(true, observation(delta, "diag_wifi_outages_24h").getJSONObject("attributes").getBoolean("is_lower_bound"))
        } finally { rig.close() }
    }

    @Test fun startupRestoresTheButtonBacklight() {
        val sent = CopyOnWriteArrayList<String>()
        val rig = rig(
            helperSend = { command -> sent += command; "OK" },
            configure = {
                it.attachProfile(io.panelassistant.android.control.fakeProfile(hasButtonBacklight = true))
                it.lastButtonBacklight = 255
            },
        )
        try {
            rig.start()
            assertNotNull("the stored level was re-applied: $sent", sent.singleOrNull { it.startsWith("BTN ") })
        } finally { rig.close() }
    }

    @Test fun anUnsetButtonBacklightIsLeftAlone() {
        val sent = CopyOnWriteArrayList<String>()
        val rig = rig(helperSend = { command -> sent += command; "OK" })
        try {
            rig.start()
            assertNull(sent.firstOrNull { it.startsWith("BTN ") })
        } finally { rig.close() }
    }

    private fun bound(rig: Rig): PanelAssistantStateReporter = PanelAssistantStateReporter(log = {}).also { native ->
        rig.core.addStateSink(native.bindShape(rig.core::nativeChannelShape))
        rig.core.addEventSink(native::event)
    }

    /** Runs an accepted session's full sync and returns its `full_begin` report. */
    private fun fullSync(native: PanelAssistantStateReporter): JSONObject {
        native.open(native.offer().descriptors)
        val begin = JSONObject(requireNotNull(native.next(2, "native-session", 0L)))
        assertEquals("full_begin", begin.getString("sync"))
        native.onResult(PanelAssistantReportResult.Acknowledged(2, emptyMap()), 0L)
        assertEquals("full_end", JSONObject(requireNotNull(native.next(3, "native-session", 0L))).getString("sync"))
        native.onResult(PanelAssistantReportResult.Acknowledged(3, emptyMap()), 0L)
        assertTrue(native.fullSyncComplete())
        return begin
    }

    private fun observation(report: JSONObject, channel: String): JSONObject {
        val observations = report.getJSONArray("observations")
        return (0 until observations.length()).map(observations::getJSONObject).singleOrNull { it.getString("channel") == channel }
            ?: throw AssertionError("$channel not reported in $report")
    }

    private fun channels(report: JSONObject): Set<String> = report.getJSONArray("observations").let { observations ->
        (0 until observations.length()).mapTo(linkedSetOf()) { observations.getJSONObject(it).getString("channel") }
    }
}
