package io.panelassistant.android

import org.junit.Assert.assertEquals
import org.junit.Test

class PaneldServiceStartTest {
    @Test fun visibleOreoActivityStartsWithoutArmingTheForegroundDeadline() {
        for (sdk in 26..27) {
            val requests = mutableListOf<String>()
            dispatchPanelServiceStart(
                sdk, fromVisibleActivity = true, alreadyForeground = false,
                startOrdinary = { requests += "ordinary" },
                startForeground = { requests += "foreground" },
            )
            assertEquals("API $sdk", listOf("ordinary"), requests)
        }
    }

    @Test fun backgroundAndNewerStartsKeepTheirRequiredRoute() {
        for ((sdk, visible, expected) in listOf(
            Triple(25, false, "ordinary"),
            Triple(27, false, "foreground"),
            Triple(28, true, "foreground"),
        )) {
            val requests = mutableListOf<String>()
            dispatchPanelServiceStart(sdk, visible, false, { requests += "ordinary" }, { requests += "foreground" })
            assertEquals("API $sdk, visible=$visible", listOf(expected), requests)
        }
    }

    // After an update the accessibility bind's second revive asked an already promoted service to
    // promote again, and Android 8.1 stopped it on a stale timer and crashed it.
    @Test fun aServiceAlreadyForegroundIsNeverAskedToPromoteAgain() {
        for (sdk in 26..35) {
            for (visible in listOf(false, true)) {
                val requests = mutableListOf<String>()
                dispatchPanelServiceStart(
                    sdk, fromVisibleActivity = visible, alreadyForeground = true,
                    startOrdinary = { requests += "ordinary" },
                    startForeground = { requests += "foreground" },
                )
                assertEquals("API $sdk, visible=$visible", listOf("ordinary"), requests)
            }
        }
    }
}
