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
            haLifecycleNoticeState(tracker.snapshot(10_100L), null, 0L))
    }

    @Test fun cachedDisconnectedDashboardShowsAnOutageUntilTheFrontendConnects() {
        admit()
        for (state in listOf(null, HaLifecycleState.NORMAL, HaLifecycleState.CONNECTION_LOST, HaLifecycleState.BACK_ONLINE)) {
            assertEquals("cached dashboard with lifecycle $state", HaLifecycleState.CONNECTION_LOST,
                haLifecycleNoticeState(state?.let(::snapshot), RendererAdmissionRuntime.current(), 0L))
        }
        assertNull("cached rendering must not bypass an observed loss grace",
            haLifecycleNoticeState(snapshot(HaLifecycleState.CONNECTION_LOST).copy(offlineGraceRemainingMs = 1L),
                RendererAdmissionRuntime.current(), 0L))
        RendererAdmissionRuntime.setFrontendConnected(owner, true, 0L)
        assertNull(haLifecycleNoticeState(snapshot(HaLifecycleState.NORMAL), RendererAdmissionRuntime.current(), 0L))
        RendererAdmissionRuntime.setFrontendConnected(owner, false, 0L)
        assertEquals(HaLifecycleState.CONNECTION_LOST,
            haLifecycleNoticeState(null, RendererAdmissionRuntime.current(), 0L))
    }

    @Test fun deliberateShutdownAndStartupKeepTheirWording() {
        admit()
        for (state in listOf(HaLifecycleState.SHUTTING_DOWN, HaLifecycleState.STARTING)) {
            assertEquals(state, haLifecycleNoticeState(snapshot(state), RendererAdmissionRuntime.current(), 0L))
        }
    }

    @Test fun liveAdmissionShowsAnObservedLossOnlyAfterItsGrace() {
        assertNull(haLifecycleNoticeState(null, RendererAdmissionRuntime.current(), 0L))
        admit(cached = false)
        assertNull(haLifecycleNoticeState(snapshot(HaLifecycleState.CONNECTION_LOST).copy(offlineGraceRemainingMs = 1L), RendererAdmissionRuntime.current(), 0L))
        assertEquals(HaLifecycleState.CONNECTION_LOST,
            haLifecycleNoticeState(snapshot(HaLifecycleState.CONNECTION_LOST), RendererAdmissionRuntime.current(), 0L))
    }

    @Test fun replacingTheActivityRetiresTheOldCachedOutage() {
        admit()
        val replacement = BuiltinDashboard.acquireActivityOwner()
        try {
            assertNull(haLifecycleNoticeState(null, RendererAdmissionRuntime.current(), 0L))
            RendererAdmissionRuntime.setFrontendConnected(owner, false, 0L)
            assertNull(haLifecycleNoticeState(null, RendererAdmissionRuntime.current(), 0L))
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

    /** A dashboard that was connected and then dropped, [lostAt] on the renderer's clock. */
    private fun dropDashboard(lostAt: Long) {
        admit(cached = false)
        RendererAdmissionRuntime.setFrontendConnected(owner, true, lostAt - 5_000L)
        RendererAdmissionRuntime.setFrontendConnected(owner, false, lostAt)
    }

    private fun shown(snap: HaLifecycle.Snapshot?, nowMs: Long): HaLifecycleCard? {
        val renderer = RendererAdmissionRuntime.current()
        val state = haLifecycleNoticeState(snap, renderer, nowMs) ?: return null
        return haLifecycleCard(state, snap, renderer?.frontendConnected == true,
            dashboardLostMs = haDashboardLostMs(renderer, nowMs))
    }

    @Test fun withoutASessionADroppedDashboardSaysItCannotReachHomeAssistantAfterTheGrace() {
        dropDashboard(lostAt = 50_000L)
        // No session: no snapshot at all, or one that never heard of an outage.
        for (snap in listOf(null, snapshot(HaLifecycleState.NORMAL))) {
            assertNull("an ordinary reconnect inside the grace shows nothing ($snap)", shown(snap, 59_999L))
            val card = shown(snap, 72_000L)!!
            assertNull(card.step)
            assertEquals("the card counts how long Home Assistant has been unreachable", 22_000L, card.offlineMs)
            assertEquals(R.string.ha_unreachable_retrying, haLifecycleDetail(card))
        }
        RendererAdmissionRuntime.setFrontendConnected(owner, true, 80_000L)
        assertNull("the dashboard reconnecting ends it", shown(null, 80_001L))
    }

    @Test fun aSessionNoticeReplacesTheFallbackWithTheRestartCard() {
        dropDashboard(lostAt = 50_000L)
        val stopping = snapshot(HaLifecycleState.SHUTTING_DOWN).copy(elapsedMs = 30_000L, expectedMs = 100_000L)
        assertEquals(HaRestartStep.STOPPING, shown(stopping, 90_000L)!!.step)
        assertEquals(HaRestartStep.STARTING, shown(snapshot(HaLifecycleState.STARTING), 90_000L)!!.step)
        assertEquals("Home Assistant ready while the dashboard catches up is the Reloading step",
            HaRestartStep.RELOADING, shown(snapshot(HaLifecycleState.BACK_ONLINE), 90_000L)!!.step)
    }

    @Test fun aFirstLoadThatHasNotConnectedIsNotAnOutage() {
        admit(cached = false)
        RendererAdmissionRuntime.setFrontendConnected(owner, false, 1_000L)
        assertNull(shown(null, 600_000L))
    }
}
