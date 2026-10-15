package io.panelassistant.android.http

import io.panelassistant.android.control.ZigbeeGatewayLayout
import io.panelassistant.android.control.ZigbeeGatewayObservation
import io.panelassistant.android.control.ZigbeeHealthPolicy
import io.panelassistant.android.control.ZigbeeHealthSnapshot
import io.panelassistant.android.control.ZigbeeHealthState
import io.panelassistant.android.control.ZigbeeNetInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ZigbeeWarningTest {
    @Test fun explicitlyEnabledUnjoinedGatewayWarnsToJoinOrDisable() {
        val notice = requireNotNull(
            zigbeeNotice(
                ZigbeeHealthSnapshot(state = ZigbeeHealthState.DEGRADED_UNJOINED, joined = false),
                configuredOn = true,
                strings = englishCatalogue,
            ),
        )
        val html = notice.html(englishCatalogue)

        assertEquals(NoticeSeverity.WARNING, notice.severity)
        assertTrue(html.contains("<b class=\"notice-title\">Zigbee router is enabled but not joined</b>"))
        assertTrue(html.contains("consume substantial CPU"))
        assertTrue(html.contains("coordinator"))
        assertTrue(html.contains("switch OFF"))
        assertTrue(html.contains("<span class=\"notice-action\"><a href=\"configure#cfg-zigbee_join\">"))
    }

    @Test fun runawayGatewayNeedsAction() {
        val notice = zigbeeNotice(ZigbeeHealthSnapshot(state = ZigbeeHealthState.RUNAWAY, joined = false), true, englishCatalogue)
        assertEquals(NoticeSeverity.ACTION_NEEDED, notice?.severity)
    }

    @Test fun busyGatewayHaPaneldDoesNotRunRaisesNoWarning() {
        val policy = ZigbeeHealthPolicy(startupGraceMs = 0)
        // NSPanel Pro Tools runs a joined gateway hard while ha-paneld's router switch is off.
        val busy = ZigbeeGatewayObservation(
            present = true,
            layout = ZigbeeGatewayLayout.MANAGED,
            packageVersion = null,
            productVersion = null,
            gatewayPid = 4242,
            gatewayCpu = 80.0,
            guardPid = null,
            guardCpu = null,
            role = null,
            netInfo = ZigbeeNetInfo(roleType = 2, nodeId = 0x1234L, panId = 0x5678L),
            recursiveWatchdogAssignment = false,
        )
        val decision = (0 until 10).map { policy.evaluate(it * 60_000L, configuredOn = false, busy) }.last()
        assertEquals(ZigbeeHealthState.DEGRADED_HIGH_CPU, decision.state)
        val snapshot = ZigbeeHealthSnapshot(state = decision.state, joined = true, gatewayCpu = 80)

        assertNull(zigbeeNotice(snapshot, configuredOn = false, strings = englishCatalogue))
        assertEquals(NoticeSeverity.WARNING, zigbeeNotice(snapshot, configuredOn = true, strings = englishCatalogue)?.severity)
    }

    @Test fun unconfiguredVendorGatewayDoesNotReceiveConfigurationNag() {
        assertNull(
            zigbeeNotice(
                ZigbeeHealthSnapshot(state = ZigbeeHealthState.DEGRADED_UNJOINED, joined = false),
                configuredOn = false,
                strings = englishCatalogue,
            ),
        )
    }
}
