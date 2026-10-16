package io.panelassistant.android.panelassistant

import io.panelassistant.android.HardenedModeRefusalException
import io.panelassistant.android.LiveSettingUnavailableException
import io.panelassistant.android.SensitiveApprovalPendingException
import io.panelassistant.android.panelAssistantCommandResult
import io.panelassistant.android.security.LocalApprovalBroker
import io.panelassistant.android.testsupport.PanelRuntimeRig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Native commands through the production command dispatcher: the channel's handler reaches the hardware,
 * the outcome reads as the command's result, and a sensitive command asks for its approval under the
 * Panel Assistant peer.
 */
internal class PanelAssistantCommandWiringContractTest : PanelRuntimeRig() {
    @Test fun aNativeCommandReachesItsHandlerAndReportsItsResult() {
        val rig = rig()
        try {
            rig.start()
            val native = PanelAssistantStateReporter(log = {})
            rig.core.addStateSink(native.bindShape(rig.core::nativeChannelShape))
            // The accepted hello's pass confirms the relays, as it does on a panel.
            assertTrue(kotlinx.coroutines.runBlocking { rig.core.observeForNativeHello { true } })
            assertEquals(PanelAssistantCommandResult.Applied, submitNative(rig, "relay1", "ON"))
            assertEquals("$RELAY_BASE/relay1=1", rig.sysfs.writes.last())
            drainStatePump()
            native.open(native.offer().descriptors)
            val begin = org.json.JSONObject(requireNotNull(native.next(1, "native-session", 0L)))
            val observations = begin.getJSONArray("observations")
            val relay1 = (0 until observations.length()).map(observations::getJSONObject).single { it.getString("channel") == "relay1" }
            assertEquals(true, relay1.get("value"))
            assertEquals(
                PanelAssistantCommandResult.Refused(PanelAssistantCommandProcessor.CODE_UNKNOWN_CHANNEL),
                submitNative(rig, "not_a_channel", "ON"),
            )
        } finally { rig.close() }
    }

    @Test fun aSensitiveCommandWaitsForApprovalUnderThePanelAssistantPeer() {
        val rig = rig(hasCamera = true)
        val broker = LocalApprovalBroker.instance
        val before = broker.pending().map { it.id }.toSet()
        try {
            rig.start()
            val pending = submitNative(rig, "camera_enabled", "ON")
            assertTrue("$pending", pending is PanelAssistantCommandResult.ApprovalPending)
            val nativeApproval = broker.pending().single { it.id !in before }
            assertEquals("panel_assistant", nativeApproval.peer)
            assertFalse("nothing changed before approval", rig.config.cameraEnabled)
            assertTrue(broker.approve(nativeApproval.id))

            assertEquals(PanelAssistantCommandResult.Applied, submitNative(rig, "camera_enabled", "ON"))
            assertTrue(rig.config.cameraEnabled)
        } finally {
            broker.pending().filter { it.id !in before }.forEach { broker.deny(it.id) }
            rig.close()
        }
    }

    @Test fun anInvalidHomeDashboardRouteFromPanelAssistantIsRefusedAndAValidOneApplies() {
        val superseded = java.util.concurrent.CopyOnWriteArrayList<String>()
        val rig = rig(onExternalSettingApplied = { superseded += it; true })
        try {
            rig.start()
            val stored = rig.config.homeDashboard
            // Refused, so Home Assistant raises an error and restores its field rather than keeping the typed text.
            assertEquals(
                PanelAssistantCommandResult.Refused(PanelAssistantCommandProcessor.CODE_INVALID_VALUE),
                submitNative(rig, "home_dashboard", "/alpha/../evil"),
            )
            assertEquals(stored, rig.config.homeDashboard)
            assertEquals("a refused command retires no queued page value", emptyList<String>(), superseded.toList())

            assertEquals(PanelAssistantCommandResult.Applied, submitNative(rig, "home_dashboard", "/dashboard-test/alpha"))
            assertEquals("/dashboard-test/alpha", rig.config.homeDashboard)
            assertEquals(listOf("home_dashboard"), superseded.toList())
        } finally { rig.close() }
    }

    @Test fun aHandlerOutcomeReadsAsItsCommandResult() {
        assertEquals(PanelAssistantCommandResult.Applied, panelAssistantCommandResult(null))
        assertEquals(
            PanelAssistantCommandResult.ApprovalPending("a1"),
            panelAssistantCommandResult(SensitiveApprovalPendingException("a1", "approval required")),
        )
        assertEquals(
            PanelAssistantCommandResult.Refused("refused_hardened"),
            panelAssistantCommandResult(HardenedModeRefusalException("no")),
        )
        assertEquals(
            PanelAssistantCommandResult.Failed("hardware_unavailable"),
            panelAssistantCommandResult(LiveSettingUnavailableException("relay1")),
        )
        assertEquals(
            PanelAssistantCommandResult.Refused("invalid_value"),
            panelAssistantCommandResult(IllegalArgumentException("bad")),
        )
        assertEquals(PanelAssistantCommandResult.Failed("failed"), panelAssistantCommandResult(IllegalStateException("x")))
    }

    /** A camera switch the store could not make durable is not answered applied, and the panel keeps reporting what it stored. */
    @Test fun aCameraSwitchThatCouldNotBeStoredIsNotReportedApplied() {
        val commits = java.util.concurrent.atomic.AtomicBoolean(true)
        val rig = rig(
            hasCamera = true,
            commitsSucceed = commits::get,
            configure = { it.setRaw(requireNotNull(io.panelassistant.android.config.SettingsRegistry.spec("camera_enabled")), "true") },
        )
        try {
            rig.start()
            assertTrue(rig.config.cameraEnabled)

            // Turning the camera off needs no approval. The store refuses the durable write.
            commits.set(false)
            val result = submitNative(rig, "camera_enabled", "OFF")
            commits.set(true)
            assertTrue("an unstored switch is not answered applied: $result", result is PanelAssistantCommandResult.Failed)
            assertTrue("the panel still reports what it has stored", rig.config.cameraEnabled)

            // Once storage works, the same command applies and stays applied.
            assertEquals(PanelAssistantCommandResult.Applied, submitNative(rig, "camera_enabled", "OFF"))
            assertFalse(rig.config.cameraEnabled)
        } finally { commits.set(true); rig.close() }
    }
}
