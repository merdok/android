package io.panelassistant.android.http

import io.panelassistant.android.testsupport.TestSources
import org.junit.Assert.assertTrue
import org.junit.Test

class ZigbeeHealthSurfaceContractTest {
    // Source-text reason: the join request and retry need the Android service.

    @Test fun anExplicitZigbeeEnableRetriesTheGateway() {
        assertTrue(source("PanelCommandDispatcher.kt").contains("if (on) onZigbeeExplicitRetry()"))
    }

    @Test fun bridgeJoinRequestUsesTheRouterAdmission() {
        val bridge = source("PanelCommandDispatcher.kt")
        assertTrue(bridge.contains("fun requestZigbeeJoin(): Boolean"))
        assertTrue(bridge.contains("return admitZigbee(true) != LatestDispatcher.Admission.CLOSED"))
    }

    private fun source(relative: String): String {
        return TestSources.appFile("src/main/kotlin/io/panelassistant/android/$relative").readText()
    }
}
