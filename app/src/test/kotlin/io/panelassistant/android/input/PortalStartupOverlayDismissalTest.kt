package io.panelassistant.android.input

import android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
import android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
import org.junit.Assert.assertEquals
import org.junit.Test

class PortalStartupOverlayDismissalTest {
    // Fields read from the Portal's real AppDrawOverlaySettingsActivity tree. The event supplies the
    // activity class and window identity; the root itself is a FrameLayout, not an activity.
    private val target = PortalStartupOverlayDismissal.Window(
        41, PortalStartupOverlayDismissal.SETTINGS, listOf("Aloha Control Center"),
        listOf(PortalStartupOverlayDismissal.Toggle(true, true, true, false)),
    )

    private class Boot(var stored: Int? = 8, var writable: Boolean = true)
    private inner class Session(
        val boot: Boot = Boot(), count: Int = 9, profile: String = "meta-portal-10-gen2",
        label: String = "Aloha Control Center", startAt: Long = 24_000L,
    ) {
        var time = startAt
        var window: PortalStartupOverlayDismissal.Window? = target
        var changedWindow: PortalStartupOverlayDismissal.Window? = null
        var duringSecondRead: (() -> Unit)? = null
        var reads = 0
        var backs = 0
        var backResult = true
        val pending = mutableListOf<Runnable>()
        val dismissal = PortalStartupOverlayDismissal(
            profile, count, { boot.stored }, { next ->
                if (boot.writable) { boot.stored = next; true } else false
            }, label, { time }, {
                reads++
                if (reads == 2) duringSecondRead?.invoke()
                if (reads == 2 && changedWindow != null) changedWindow else window
            }, { backs++; backResult }, { pending += it }, { pending.remove(it) },
        )
        fun state(pkg: String? = PortalStartupOverlayDismissal.SETTINGS,
                  cls: String? = PortalStartupOverlayDismissal.ACTIVITY, id: Int = 41) =
            dismissal.onEvent(TYPE_WINDOW_STATE_CHANGED, pkg, cls, id)
        fun content(id: Int = 41) = dismissal.onEvent(
            TYPE_WINDOW_CONTENT_CHANGED, PortalStartupOverlayDismissal.SETTINGS, "android.widget.Switch", id,
        )
        fun settle() { pending.toList().also { pending.clear() }.forEach { it.run() } }
        fun result() = listOf(backs, boot.stored, pending.size)
    }

    @Test fun firstInstallationOnlyPrimesAndANewBootReturnsOnceWithoutRearmingOnRestart() {
        val boot = Boot(null)
        val install = Session(boot).apply { state(); settle() }
        val installed = install.result()
        val nextBoot = Session(boot, count = 10).apply { state(); settle(); state(); content(); settle() }
        val restart = Session(boot, count = 10).apply { state(); settle() }
        assertEquals(listOf(listOf(0, 9, 0), listOf(1, 10, 0), listOf(0, 10, 0)),
            listOf(installed, nextBoot.result(), restart.result()))
    }

    @Test fun supportedPortalProfilesMatchTheInstalledVendorLabelWithoutDependingOnEnglish() {
        val results = listOf("meta-portal", "meta-portal-10-gen2").map { profile ->
            Session(profile = profile, label = "Installed vendor label").apply {
                window = target.copy(headers = listOf("Installed vendor label"))
                state(); settle()
            }.result()
        }
        assertEquals(listOf(listOf(1, 9, 0), listOf(1, 9, 0)), results)
    }

    @Test fun unsupportedProfilesUnknownRollbackAndUnwritableBootsNeverNavigate() {
        val sessions = listOf(
            Session(profile = "generic"), Session(count = -1), Session(Boot(10)),
            Session(Boot(writable = false)), Session(Boot(-1)),
        )
        sessions.forEach { it.state(); it.settle() }
        assertEquals(listOf(listOf(0, 8, 0), listOf(0, 8, 0), listOf(0, 10, 0), listOf(0, 8, 0), listOf(0, 9, 0)),
            sessions.map { it.result() })
    }

    @Test fun lateStartAndAQueuedCallbackAfterThirtySecondsLeaveManualSettingsAlone() {
        val late = Session(startAt = 30_000L).apply { state(); settle() }
        val expired = Session().apply { state(); time = 30_000L; settle(); state(); settle() }
        val restart = Session(expired.boot).apply { state(); settle() }
        assertEquals(listOf(listOf(0, 9, 0), listOf(0, 9, 0), listOf(0, 9, 0)),
            listOf(late.result(), expired.result(), restart.result()))
    }

    @Test fun contentEventsRetryAnUnsettledTreeWithoutReplacingTheActivityWithAWidgetClass() {
        val session = Session().apply {
            window = null; state(); settle()
            window = target; content(); settle()
        }
        assertEquals(listOf(1, 9, 0), session.result())
    }

    @Test fun contentEventsWithoutTheExactActivityAndWindowCannotDismissOtherPermissionPages() {
        val outcomes = listOf(
            Session().apply { content(); settle() },
            Session().apply { state(cls = "com.android.settings.Settings\$WriteSettingsActivity"); content(); settle() },
            Session().apply { state(id = 42); content(); settle() },
            Session().apply { state(); state(pkg = "android", cls = "ResolverActivity"); settle() },
            Session().apply { state(pkg = "com.android.packageinstaller", cls = "PackageInstallerActivity"); settle() },
            Session().apply { state(pkg = "io.panelassistant.android", cls = "AdminLauncherActivity"); settle() },
        ).map { it.backs }
        assertEquals(listOf(0, 0, 0, 0, 0, 0), outcomes)
    }

    @Test fun anActiveWindowThatChangedDuringTheReadIsLeftAloneAndCanRetryOnANewEvent() {
        val session = Session().apply {
            changedWindow = target.copy(id = 42); state(); settle()
        }
        val beforeRetry = session.backs
        session.changedWindow = null
        session.content(); session.settle()
        assertEquals(listOf(0, 1), listOf(beforeRetry, session.backs))
    }

    @Test fun onlyOneVisibleEnabledDeniedVendorToggleInTheCurrentSettingsWindowIsEligible() {
        val off = target.toggles.single()
        val windows = listOf(
            null, target.copy(id = 42), target.copy(packageName = "other.settings"),
            target.copy(headers = listOf("Panel Assistant")), target.copy(headers = listOf(null)),
            target.copy(headers = target.headers + target.headers), target.copy(toggles = emptyList()),
            target.copy(toggles = listOf(off, off)), target.copy(toggles = listOf(off.copy(visible = false))),
            target.copy(toggles = listOf(off.copy(enabled = false))),
            target.copy(toggles = listOf(off.copy(checkable = false))), target.copy(toggles = listOf(off.copy(checked = true))),
            target,
        )
        val results = windows.map { current -> Session().apply { window = current; state(); settle() }.backs }
        assertEquals(List(12) { 0 } + 1, results)
    }

    @Test fun eventBurstsSpendOnlyOneBackAttemptEvenWhenAndroidRefusesIt() {
        val session = Session().apply { backResult = false; repeat(20) { state(); content() } }
        val scheduled = session.pending.size
        session.settle()
        repeat(20) { session.state(); session.content() }
        session.settle()
        assertEquals(listOf(1, 1, 0), listOf(scheduled, session.backs, session.pending.size))
    }

    @Test fun teardownCancelsAndAlsoRejectsAnAlreadyDequeuedCallback() {
        val session = Session().apply { state() }
        val dequeued = session.pending.single()
        session.dismissal.close()
        dequeued.run()
        session.state(); session.content(); session.settle()
        assertEquals(listOf(0, 9, 0), session.result())
    }

    @Test fun expiryOrTeardownDuringTheFinalReadCannotNavigate() {
        val expired = Session().apply {
            duringSecondRead = { time = 30_000L }; state(); settle()
        }
        val closed = Session().apply {
            duringSecondRead = { dismissal.close() }; state(); settle()
        }
        assertEquals(listOf(0, 0), listOf(expired.backs, closed.backs))
    }
}
