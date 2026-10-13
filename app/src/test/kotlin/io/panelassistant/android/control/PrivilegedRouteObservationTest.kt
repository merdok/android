package io.panelassistant.android.control

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivilegedRouteObservationTest {
    @Test fun observesEachAuthorityExactlyOnce() {
        var suCalls = 0
        var helperCalls = 0

        val observed = observePrivilegedRoutes(
            directSuProbe = { suCalls += 1; false },
            helperRootProbe = { helperCalls += 1; true },
        )

        assertFalse(observed.directSuReady)
        assertTrue(observed.helperRootReady)
        assertTrue(observed.rootControlReady)
        assertEquals(1, suCalls)
        assertEquals(1, helperCalls)
    }

    @Test fun aPanelWithoutRootOrTheHelperHasNoPrivilegedRoute() {
        val observed = PrivilegedRouteObservation(directSuReady = false, helperRootReady = false)

        assertFalse(observed.rootControlReady)
        PrivilegeRoute.entries.filter { it != PrivilegeRoute.APP }.forEach { assertFalse(observed.admits(it)) }
        // The app's own settings reads need no privilege, so a rootless panel can still read its sizing.
        assertTrue(observed.admits(PrivilegeRoute.APP))
        assertFalse(observeTypedShellCapability(directSuProbe = { false }, helperRootProbe = { false }).typedShellControlReady)
    }

    @Test fun directOrHelperRouteIndependentlyProvesRootControl() {
        listOf(true to false, false to true, true to true).forEach { (direct, helper) ->
            val observed = PrivilegedRouteObservation(directSuReady = direct, helperRootReady = helper)

            assertTrue(observed.rootControlReady)
            assertEquals(direct, observed.admits(PrivilegeRoute.SU))
            assertEquals(helper, observed.admits(PrivilegeRoute.DAEMON))
            assertFalse(observed.admits(PrivilegeRoute.ACCESSIBILITY))
        }
    }

    @Test fun typedCapabilitySkipsHelperWhenDirectSuAlreadyProvesIt() {
        var helperCalls = 0
        val observed = observeTypedShellCapability(
            directSuProbe = { true },
            helperRootProbe = { helperCalls += 1; true },
        )

        assertTrue(observed.typedShellControlReady)
        assertEquals(0, helperCalls)
    }

    @Test fun typedCapabilityProbesHelperOnceWhenSuIsNotReady() {
        var helperCalls = 0
        val observed = observeTypedShellCapability(
            directSuProbe = { false },
            helperRootProbe = { helperCalls += 1; true },
        )

        assertFalse(observed.directSuReady)
        assertTrue(observed.typedShellControlReady)
        assertEquals(1, helperCalls)
    }
}
