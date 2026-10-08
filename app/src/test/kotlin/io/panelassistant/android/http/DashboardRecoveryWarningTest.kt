package io.panelassistant.android.http

import io.panelassistant.android.PanelStatus.DashboardRecoveryState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardRecoveryWarningTest {
    @Test
    fun noRecoverySuppressionHasNoWarning() {
        assertNull(recoveryNotice(DashboardRecoveryState.NONE, englishCatalogue))
    }

    @Test
    fun builtinWarningNamesWebViewAndExplicitReload() {
        val notice = requireNotNull(recoveryNotice(DashboardRecoveryState.BUILTIN_RENDERER, englishCatalogue))
        val warning = notice.wire()
        assertEquals(NoticeSeverity.ACTION_NEEDED, notice.severity)
        assertTrue(warning.contains("Built-in renderer"))
        assertTrue(warning.contains("System WebView"))
        assertTrue(warning.contains("Reload dashboard"))
        assertTrue(!warning.contains("Companion app"))
    }

    @Test
    fun externalWarningRetainsDashboardAppRemediation() {
        val notice = requireNotNull(recoveryNotice(DashboardRecoveryState.EXTERNAL_RENDERER, englishCatalogue))
        assertEquals("Dashboard app is crash-looping", notice.title)
        assertTrue(notice.wire().contains("dashboard/Companion app"))
    }
}
