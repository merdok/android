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

    @Test fun aRefusedSignInDoesNotPretendThePanelIsRetryingAnOutage() {
        val rig = DashboardRendererRig()
        rig.phase = RendererPhase.NONE
        try {
            for (event in listOf(RendererEvent.LOAD_STARTED, RendererEvent.CONNECTED,
                RendererEvent.DISCONNECTED, RendererEvent.DISCONNECTED, RendererEvent.DISCONNECTED,
                RendererEvent.LOGIN_REFUSED)) rig.call("on", event)
            assertEquals(RendererPhase.AUTH_LATCHED, rig.phase)
            for (snap in listOf(null, snapshot(HaLifecycleState.NORMAL))) {
                for (now in listOf(21_000L, 600_000L)) {
                    val live = RendererAdmissionRuntime.current()
                    val notice = haLifecycleNoticeState(snap, live, now)
                    assertNull("a refused sign-in is not a retrying outage at $now", notice)
                    assertNull(notice?.let { haLifecycleCard(it, snap, dashboardConnected = false) })
                }
            }
            for (state in listOf(HaLifecycleState.SHUTTING_DOWN, HaLifecycleState.STARTING)) {
                assertEquals(state, haLifecycleNoticeState(snapshot(state), RendererAdmissionRuntime.current(), 600_000L))
            }
        } finally { rig.close() }
    }

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
        RendererAdmissionRuntime.setFrontendConnected(owner, true, RendererEvent.CONNECTED, 0L)
        assertNull(haLifecycleNoticeState(snapshot(HaLifecycleState.NORMAL), RendererAdmissionRuntime.current(), 0L))
        RendererAdmissionRuntime.setFrontendConnected(owner, false, RendererEvent.DISCONNECTED, 0L)
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
            RendererAdmissionRuntime.setFrontendConnected(owner, false, RendererEvent.DISCONNECTED, 0L)
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
        assertEquals(true, RendererAdmissionRuntime.setFrontendConnected(owner, true, RendererEvent.CONNECTED))
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
        assertEquals(true, RendererAdmissionRuntime.setFrontendConnected(owner, true, RendererEvent.CONNECTED))
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
        RendererAdmissionRuntime.setFrontendConnected(owner, true, RendererEvent.CONNECTED, lostAt - 5_000L)
        RendererAdmissionRuntime.setFrontendConnected(owner, false, RendererEvent.DISCONNECTED, lostAt)
    }

    private fun shown(snap: HaLifecycle.Snapshot?, nowMs: Long): HaLifecycleCard? {
        val renderer = RendererAdmissionRuntime.current()
        val state = haLifecycleNoticeState(snap, renderer, nowMs) ?: return null
        return haLifecycleCard(state, snap, renderer?.frontendConnected == true,
            dashboardLostMs = haDashboardLostMs(snap, renderer, nowMs))
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
        RendererAdmissionRuntime.setFrontendConnected(owner, true, RendererEvent.CONNECTED, 80_000L)
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
        RendererAdmissionRuntime.setFrontendConnected(owner, false, RendererEvent.DISCONNECTED, 1_000L)
        assertNull(shown(null, 600_000L))
    }

    private var phase = RendererPhase.NONE

    /** Move the renderer's phase as DashboardActivity's `on(event)` does, and publish it the same way. */
    private fun publish(event: RendererEvent, at: Long) {
        phase = phase.after(event)
        RendererAdmissionRuntime.setFrontendConnected(owner, phase == RendererPhase.CONNECTED, event, at)
    }

    @Test fun theAppsOwnTeardownOrReloadIsNotAnOutage() {
        admit(cached = false)
        publish(RendererEvent.LOAD_STARTED, 1_000L)
        publish(RendererEvent.CONNECTED, 5_000L)
        // A rebuild: the WebView is torn down and the rebuilt page's first load takes a minute (the entity hold).
        publish(RendererEvent.TORN_DOWN, 10_000L)
        publish(RendererEvent.LOAD_STARTED, 70_000L)
        assertNull("a rebuild the app started is not Home Assistant being unreachable", shown(null, 80_000L))
        publish(RendererEvent.CONNECTED, 85_000L)
        // A reload of a connected page that is slow to connect again.
        publish(RendererEvent.LOAD_STARTED, 90_000L)
        assertNull("a reload the app started is not Home Assistant being unreachable", shown(null, 120_000L))
        publish(RendererEvent.CONNECTED, 125_000L)
        // A real drop of the connected page still says so after the grace.
        publish(RendererEvent.DISCONNECTED, 130_000L)
        assertNull(shown(null, 139_999L))
        assertEquals(R.string.ha_unreachable_retrying, shown(null, 141_000L)?.let(::haLifecycleDetail))
    }

    @Test fun aRestartsDropIsNotAnOutageOnceHomeAssistantIsReady() {
        var now = 0L
        val coordinator = HaLifecycleCoordinator(nowMs = { now })
        admit(cached = false)
        publish(RendererEvent.LOAD_STARTED, now)
        publish(RendererEvent.CONNECTED, now)
        now = 10_000L
        coordinator.onNativeNotice(HaLifecycleNotice(HaLifecyclePhase.SHUTTING_DOWN, HaLifecycleReason.RESTART, 0L, 113_000L))
        now = 12_000L; publish(RendererEvent.DISCONNECTED, now)
        now = 130_000L
        coordinator.onNativeNotice(HaLifecycleNotice(HaLifecyclePhase.READY, HaLifecycleReason.RESTART, 120_000L, 113_000L))
        assertEquals(HaRestartStep.RELOADING, shown(coordinator.snapshot(), now)!!.step)
        // The back-online window has ended and the dashboard is still reconnecting from the restart.
        now = 139_000L
        assertNull("the restart explains the drop", shown(coordinator.snapshot(), now))
        now = 189_000L
        assertNull("while the dashboard has a watchdog retry to reconnect", shown(coordinator.snapshot(), now))
        now = 191_000L
        assertEquals("a dashboard that has not reconnected a minute after Home Assistant was ready is an outage",
            R.string.ha_unreachable_retrying, shown(coordinator.snapshot(), now)?.let(::haLifecycleDetail))
        publish(RendererEvent.CONNECTED, now)
        // A drop after Home Assistant was ready is a new one, and is shown after the grace, even when
        // Panel Assistant's next hello carries its ordinary ready notice.
        now = 200_000L; publish(RendererEvent.DISCONNECTED, now)
        now = 205_000L
        coordinator.onNativeNotice(HaLifecycleNotice(HaLifecyclePhase.READY, HaLifecycleReason.UNKNOWN, null, null))
        now = 211_000L
        assertEquals(R.string.ha_unreachable_retrying, shown(coordinator.snapshot(), now)?.let(::haLifecycleDetail))
    }
}
