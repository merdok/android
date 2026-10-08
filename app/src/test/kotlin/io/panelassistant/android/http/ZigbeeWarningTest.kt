package io.panelassistant.android.http

import io.panelassistant.android.control.ZigbeeHealthSnapshot
import io.panelassistant.android.control.ZigbeeHealthState
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
