package io.panelassistant.android

import io.panelassistant.android.control.BuiltinDashboard
import io.panelassistant.android.sensors.HaLifecycle
import io.panelassistant.android.sensors.HaLifecycleCoordinator
import io.panelassistant.android.sensors.HaLifecycleNotice
import io.panelassistant.android.sensors.HaLifecyclePhase
import io.panelassistant.android.sensors.HaLifecycleReason
import io.panelassistant.android.sensors.HaLifecycleState
import io.panelassistant.android.util.HaTransportEvidence
import io.panelassistant.android.util.HaTransportFault
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class HaLifecycleNoticeTest {
    private var owner = 0L

    @Before fun acquire() {
        RendererAdmissionRuntime.reset()
        owner = BuiltinDashboard.acquireActivityOwner()
    }

    @After fun release() {
        BuiltinDashboard.releaseActivityOwner(owner)
        RendererAdmissionRuntime.reset()
    }

    private fun admit(cached: Boolean = true) {
        RendererAdmissionRuntime.record(
            owner, RendererAdmissionState.ADMITTED, 1_000L,
            admittedOnCachedVersion = cached,
            evidence = HaTransportEvidence(HaTransportFault.DNS, "UnknownHostException"),
        )
    }

    private fun snapshot(state: HaLifecycleState) =
        HaLifecycle.Snapshot(state, false, 1L, 8_000L)

    @Test fun panelThatMissedStopShowsOfflineAfterConnectionLoss() {
        val tracker = HaLifecycle()
        tracker.onDisconnected(100L)
        assertEquals(HaLifecycleState.CONNECTION_LOST,
            haLifecycleNoticeState(tracker.snapshot(10_100L), null))
    }

    @Test fun cachedDisconnectedDashboardShowsAnOutageUntilTheFrontendConnects() {
        admit()
        for (state in listOf(null, HaLifecycleState.NORMAL, HaLifecycleState.CONNECTION_LOST, HaLifecycleState.BACK_ONLINE)) {
            assertEquals("cached dashboard with lifecycle $state", HaLifecycleState.CONNECTION_LOST,
                haLifecycleNoticeState(state?.let(::snapshot), RendererAdmissionRuntime.current()))
        }
        assertNull("cached rendering must not bypass an observed loss grace",
            haLifecycleNoticeState(snapshot(HaLifecycleState.CONNECTION_LOST).copy(offlineGraceRemainingMs = 1L),
                RendererAdmissionRuntime.current()))
        RendererAdmissionRuntime.setFrontendConnected(owner, true)
        assertNull(haLifecycleNoticeState(snapshot(HaLifecycleState.NORMAL), RendererAdmissionRuntime.current()))
        RendererAdmissionRuntime.setFrontendConnected(owner, false)
        assertEquals(HaLifecycleState.CONNECTION_LOST,
            haLifecycleNoticeState(null, RendererAdmissionRuntime.current()))
    }

    @Test fun deliberateShutdownAndStartupKeepTheirWording() {
        admit()
        for (state in listOf(HaLifecycleState.SHUTTING_DOWN, HaLifecycleState.STARTING)) {
            assertEquals(state, haLifecycleNoticeState(snapshot(state), RendererAdmissionRuntime.current()))
        }
    }

    @Test fun liveAdmissionShowsAnObservedLossOnlyAfterItsGrace() {
        assertNull(haLifecycleNoticeState(null, RendererAdmissionRuntime.current()))
        admit(cached = false)
        assertNull(haLifecycleNoticeState(snapshot(HaLifecycleState.CONNECTION_LOST).copy(offlineGraceRemainingMs = 1L), RendererAdmissionRuntime.current()))
        assertEquals(HaLifecycleState.CONNECTION_LOST,
            haLifecycleNoticeState(snapshot(HaLifecycleState.CONNECTION_LOST), RendererAdmissionRuntime.current()))
    }

    @Test fun replacingTheActivityRetiresTheOldCachedOutage() {
        admit()
        val replacement = BuiltinDashboard.acquireActivityOwner()
        try {
            assertNull(haLifecycleNoticeState(null, RendererAdmissionRuntime.current()))
            RendererAdmissionRuntime.setFrontendConnected(owner, false)
            assertNull(haLifecycleNoticeState(null, RendererAdmissionRuntime.current()))
        } finally {
            BuiltinDashboard.releaseActivityOwner(replacement)
        }
    }

    /**
     * Panel Assistant said Home Assistant was stopping, the socket dropped, and every hello after
     * that is refused (onNativeAuthenticated) or fails (onNativeDisconnected): no READY can ever arrive.
     * The dashboard reconnects and works, yet the Stopping card must not sit over it forever.
     */
    @Test fun aStopNoticeWithNoReadyGivesWayOnceRefusedAndFailedHellosRunPastTheUsualTime() {
        var now = 0L
        var published = 0
        val coordinator = HaLifecycleCoordinator(nowMs = { now }, onChanged = { published++ })
        coordinator.onNativeNotice(HaLifecycleNotice(HaLifecyclePhase.SHUTTING_DOWN, HaLifecycleReason.CORE_UPDATE, 0L, 113_000L))
        now = 1_000L; coordinator.onNativeDisconnected()
        assertEquals("the notice published once and the disconnect kept it", 1, published)
        admit(cached = false)
        assertEquals(true, RendererAdmissionRuntime.setFrontendConnected(owner, true))
        // Panel Assistant refuses or fails every hello for two hours; each one reaches the coordinator.
        var hellos = 0
        while (now < 2 * 60 * 60_000L) {
            now += 30_000L
            if (hellos++ % 2 == 0) coordinator.onNativeAuthenticated() else coordinator.onNativeDisconnected()
        }
        assertEquals(240, hellos)
        val snap = coordinator.snapshot()
        val live = RendererAdmissionRuntime.current()
        assertEquals(true, live?.frontendConnected)
        val card = haLifecycleNoticeState(snap, live)?.let { haLifecycleCard(it, snap, dashboardConnected = true) }
        assertNull("state=${snap.state} step=${card?.step} overdueMs=${card?.overdueMs}", haLifecycleNoticeState(snap, live))
    }

    /** The bound must not cut short the worst measured startup (395 s): the card stays up through it. */
    @Test fun aStopNoticeSurvivesRefusedAndFailedHellosForTheWorstMeasuredStartup() {
        var now = 0L
        val coordinator = HaLifecycleCoordinator(nowMs = { now })
        coordinator.onNativeNotice(HaLifecycleNotice(HaLifecyclePhase.SHUTTING_DOWN, HaLifecycleReason.RESTART, 0L, 113_000L))
        now = 1_000L; coordinator.onNativeDisconnected()
        admit(cached = false)
        assertEquals(true, RendererAdmissionRuntime.setFrontendConnected(owner, true))
        var hellos = 0
        while (now + 30_000L <= 395_000L) {
            now += 30_000L
            if (hellos++ % 2 == 0) coordinator.onNativeAuthenticated() else coordinator.onNativeDisconnected()
        }
        assertEquals(13, hellos)
        now = 395_000L
        val snap = coordinator.snapshot()
        assertEquals(HaLifecycleState.SHUTTING_DOWN, haLifecycleNoticeState(snap, RendererAdmissionRuntime.current()))
    }

    /** After the claim gives way, a fresh Starting notice from a reloaded Panel Assistant still leads to Back online. */
    @Test fun aRecoveryAfterTheClaimGaveWayIsStillAnnounced() {
        var now = 0L
        val coordinator = HaLifecycleCoordinator(nowMs = { now })
        coordinator.onNativeNotice(HaLifecycleNotice(HaLifecyclePhase.SHUTTING_DOWN, HaLifecycleReason.RESTART, 0L, 113_000L))
        now = 1_000L; coordinator.onNativeDisconnected()
        var hellos = 0
        while (now < 630_000L) {
            now += 30_000L
            if (hellos++ % 2 == 0) coordinator.onNativeAuthenticated() else coordinator.onNativeDisconnected()
        }
        assertNull(haLifecycleNoticeState(coordinator.snapshot(), null))
        now = 640_000L
        coordinator.onNativeNotice(HaLifecycleNotice(HaLifecyclePhase.STARTING, HaLifecycleReason.RESTART, 640_000L, 113_000L))
        assertEquals(HaLifecycleState.STARTING, haLifecycleNoticeState(coordinator.snapshot(), null))
        now = 700_000L
        coordinator.onNativeNotice(HaLifecycleNotice(HaLifecyclePhase.READY, HaLifecycleReason.RESTART, 700_000L, 113_000L))
        assertEquals(HaLifecycleState.BACK_ONLINE, haLifecycleNoticeState(coordinator.snapshot(), null))
    }

    /** The bound must not steal a real recovery: READY inside the usual time is still announced. */
    @Test fun aReadyInsideTheUsualTimeStillAnnouncesTheRecovery() {
        var now = 0L
        val coordinator = HaLifecycleCoordinator(nowMs = { now })
        coordinator.onNativeNotice(HaLifecycleNotice(HaLifecyclePhase.SHUTTING_DOWN, HaLifecycleReason.RESTART, 0L, 113_000L))
        now = 1_000L; coordinator.onNativeDisconnected()
        now = 30_000L; coordinator.onNativeAuthenticated()
        now = 60_000L; coordinator.onNativeNotice(HaLifecycleNotice(HaLifecyclePhase.READY, HaLifecycleReason.RESTART, 60_000L, 113_000L))
        assertEquals(HaLifecycleState.BACK_ONLINE, haLifecycleNoticeState(coordinator.snapshot(), null))
    }
}
