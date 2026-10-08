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
            val native = PanelAssistantShadowReporter(log = {})
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
            assertEquals(
                PanelAssistantCommandResult.Refused(PanelAssistantCommandProcessor.CODE_NOT_COMMANDABLE),
                submitNative(rig, "update_paneld", "install"),
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
}
