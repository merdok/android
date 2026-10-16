package io.panelassistant.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KioskSettingApplyTest {
    @Test fun failedEnableIsNeitherPersistedNorPublished() {
        var persisted = false
        var reconciled = false

        assertFalse(applyAcknowledgedKioskSetting(
            on = true,
            actuate = { false },
            persist = { persisted = it; true },
            reconcile = { reconciled = true },
        ))

        assertFalse(persisted)
        assertFalse(reconciled)
    }

    @Test fun enableActuatesBeforePersistenceAndOffPersistsBeforeBestEffortCleanup() {
        val events = mutableListOf<String>()
        assertTrue(applyAcknowledgedKioskSetting(
            on = true,
            actuate = { events += "actuate-on"; true },
            persist = { events += "persist-$it"; true },
            reconcile = { events += "reconcile" },
        ))
        assertEquals(listOf("actuate-on", "persist-true", "reconcile"), events)

        events.clear()
        assertTrue(applyAcknowledgedKioskSetting(
            on = false,
            actuate = { events += "actuate-off"; false },
            persist = { events += "persist-$it"; true },
            reconcile = { events += "reconcile" },
        ))
        assertEquals(listOf("persist-false", "actuate-off", "reconcile"), events)
    }

    @Test fun failedEnablePersistenceRollsBackBeforeReportingFailure() {
        val events = mutableListOf<String>()

        assertFalse(applyAcknowledgedKioskSetting(
            on = true,
            actuate = { on -> events += "actuate-$on"; true },
            persist = { events += "persist-$it"; false },
            reconcile = { events += "reconcile" },
        ))
        assertEquals(
            listOf("actuate-true", "persist-true", "persist-false", "actuate-false"),
            events,
        )
    }

    @Test fun failedOffPersistenceStillRunsFailSafeCleanup() {
        val events = mutableListOf<String>()

        assertFalse(applyAcknowledgedKioskSetting(
            on = false,
            actuate = { events += "actuate-$it"; false },
            persist = { events += "persist-$it"; false },
            reconcile = { events += "reconcile" },
        ))
        assertEquals(listOf("persist-false", "actuate-false"), events)
    }
}

/**
 * The kiosk handler's persist-and-actuate step over a stand-in controller which, like the real one, refuses
 * ON when it cannot enable the persistent policy. For HTTP route tests that answer a live kiosk setting.
 */
internal fun kioskSetting(
    canEnable: () -> Boolean,
    actuate: (Boolean) -> Boolean,
    persist: (Boolean) -> Boolean,
): (Boolean) -> Boolean = { on ->
    applyAcknowledgedKioskSetting(on, { o -> if (o && !canEnable()) false else actuate(o) }, persist) {}
}
