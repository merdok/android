package io.panelassistant.android.http

import io.panelassistant.android.panelassistant.PanelAssistantCommandResult
import io.panelassistant.android.testsupport.PanelRuntimeRig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/** The Zigbee router's explicit enable and join request, through the production command dispatcher. */
internal class ZigbeeHealthSurfaceContractTest : PanelRuntimeRig() {
    @Test fun anExplicitZigbeeEnableRetriesTheGateway() {
        val retries = AtomicInteger()
        val rig = rig(onZigbeeExplicitRetry = { retries.incrementAndGet() })
        try {
            rig.start()
            assertEquals(PanelAssistantCommandResult.Applied, submitNative(rig, "zigbee_router", "ON"))
            assertEquals(1, retries.get())
            assertTrue(rig.config.zigbeeRouterEnabled)
            assertEquals(PanelAssistantCommandResult.Applied, submitNative(rig, "zigbee_router", "OFF"))
            assertEquals("disabling is not a retry", 1, retries.get())
        } finally { rig.close() }
    }

    @Test fun bridgeJoinRequestUsesTheRouterAdmission() {
        val retries = AtomicInteger()
        val rig = rig(onZigbeeExplicitRetry = { retries.incrementAndGet() })
        try {
            rig.start()
            rig.config.setZigbeeRouterEnabled(false)
            assertFalse("a disabled router does not join", rig.commands.requestZigbeeJoin())
            assertEquals(0, retries.get())
            rig.config.setZigbeeRouterEnabled(true)
            assertTrue(rig.commands.requestZigbeeJoin())
            assertEquals(1, retries.get())
        } finally { rig.close() }
    }
}
