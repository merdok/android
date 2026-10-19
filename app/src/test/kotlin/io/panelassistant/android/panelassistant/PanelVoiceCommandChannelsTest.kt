package io.panelassistant.android.panelassistant

import io.panelassistant.android.control.BuiltinDashboard
import io.panelassistant.android.control.FakeDaemon
import io.panelassistant.android.control.FakeRootShell
import io.panelassistant.android.control.InteractiveController
import io.panelassistant.android.platform.AccessibilityActions
import io.panelassistant.android.control.FakeSystemEnv
import io.panelassistant.android.platform.ActivityRef
import io.panelassistant.android.testsupport.PanelRuntimeRig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * The channels Panel Assistant's voice commands send, through the production command dispatcher: Android
 * settings, the launcher and recent apps open over the dashboard unless kiosk lock holds the panel there,
 * Back goes to the dashboard's previous page while the dashboard is in front and is Android's Back
 * elsewhere, and showing a page that was shown before navigates to it again.
 */
internal class PanelVoiceCommandChannelsTest : PanelRuntimeRig() {
    private val settings = "com.android.settings/.Settings"
    private val launcher = ActivityRef("com.vendor.launcher", "com.vendor.launcher.Home")

    private fun env() = FakeSystemEnv(
        launchers = mapOf("com.android.settings" to settings),
        homes = listOf(launcher),
        default = launcher,
    )

    @Test fun androidSettingsOpensTheSettingsApp() {
        val daemon = FakeDaemon(replies = mapOf("START $settings" to "OK"))
        val rig = rig(systemDaemon = daemon, systemEnv = env())
        try {
            rig.start()
            assertEquals(PanelAssistantCommandResult.Applied, submitNative(rig, "android_settings", "PRESS"))
            assertEquals(listOf("START $settings"), daemon.sent.filter { it.startsWith("START") })
        } finally { rig.close() }
    }

    @Test fun theLauncherOpensTheHomeLauncher() {
        val daemon = FakeDaemon(replies = mapOf("START ${launcher.component}" to "OK"))
        val rig = rig(systemDaemon = daemon, systemEnv = env())
        try {
            rig.start()
            assertEquals(PanelAssistantCommandResult.Applied, submitNative(rig, "launcher", "PRESS"))
            assertEquals(listOf("START ${launcher.component}"), daemon.sent.filter { it.startsWith("START") })
        } finally { rig.close() }
    }

    @Test fun aLauncherStartTheHelperRefusesIsReportedAsFailed() {
        val daemon = FakeDaemon(replies = mapOf("START ${launcher.component}" to "BUSY"))
        val rig = rig(systemDaemon = daemon, systemEnv = env())
        try {
            rig.start()
            assertEquals(
                PanelAssistantCommandResult.Failed("hardware_unavailable"),
                submitNative(rig, "launcher", "PRESS"),
            )
            assertEquals(listOf("START ${launcher.component}"), daemon.sent.filter { it.startsWith("START") })
        } finally { rig.close() }
    }

    @Test fun kioskLockKeepsThePanelOnItsDashboard() {
        val daemon = FakeDaemon(replies = mapOf("START $settings" to "OK", "START ${launcher.component}" to "OK"))
        val rig = rig(systemDaemon = daemon, systemEnv = env(), configure = { it.commitKioskLock(true) })
        try {
            rig.start()
            val refused = PanelAssistantCommandResult.Failed("hardware_unavailable")
            assertEquals(refused, submitNative(rig, "android_settings", "PRESS"))
            assertEquals(refused, submitNative(rig, "launcher", "PRESS"))
            assertEquals(emptyList<String>(), daemon.sent.filter { it.startsWith("START") })
        } finally { rig.close() }
    }

    @Test fun aPanelWithoutASettingsAppSaysSo() {
        val rig = rig(systemEnv = FakeSystemEnv())
        try {
            rig.start()
            assertEquals(
                PanelAssistantCommandResult.Failed("hardware_unavailable"),
                submitNative(rig, "android_settings", "PRESS"),
            )
        } finally { rig.close() }
    }

    /** Android's Back and Recents through an accessibility service answering [back] and [recents]; no root. */
    private fun navigation(back: () -> Boolean = { false }, recents: () -> Boolean = { false }) = InteractiveController(
        canSu = false,
        root = FakeRootShell(available = false, runResult = false),
        accessibility = object : AccessibilityActions {
            override fun back() = back()
            override fun recents() = recents()
            override fun tap(x: Int, y: Int) = false
        },
    )

    /** The built-in dashboard in front, answering Back with [wentBack], for the length of [block]. */
    private fun withDashboardInFront(wentBack: Boolean, block: () -> Unit): Int {
        val asked = AtomicInteger()
        val owner = BuiltinDashboard.acquireActivityOwner()
        try {
            BuiltinDashboard.setActivityForeground(owner, true)
            BuiltinDashboard.setBackHandler(owner) { asked.incrementAndGet(); wentBack }
            block()
        } finally { BuiltinDashboard.releaseActivityOwner(owner) }
        return asked.get()
    }

    @Test fun backOnTheDashboardGoesToItsPreviousPage() {
        val androidBacks = AtomicInteger()
        val rig = rig(navigation = navigation(back = { androidBacks.incrementAndGet(); true }))
        try {
            rig.start()
            val asked = withDashboardInFront(wentBack = true) {
                assertEquals(PanelAssistantCommandResult.Applied, submitNative(rig, "back", "PRESS"))
            }
            assertEquals(1, asked)
            assertEquals(0, androidBacks.get())
        } finally { rig.close() }
    }

    @Test fun backOnTheDashboardWithNoPreviousPageSaysSo() {
        // Android's Back would reach the dashboard, which swallows it, and still read as done.
        val androidBacks = AtomicInteger()
        val rig = rig(navigation = navigation(back = { androidBacks.incrementAndGet(); true }))
        try {
            rig.start()
            val asked = withDashboardInFront(wentBack = false) {
                assertEquals(
                    PanelAssistantCommandResult.Failed("hardware_unavailable"),
                    submitNative(rig, "back", "PRESS"),
                )
            }
            assertEquals(1, asked)
            assertEquals(0, androidBacks.get())
        } finally { rig.close() }
    }

    @Test fun backOverAnotherAppIsAndroidsBack() {
        val androidBacks = AtomicInteger()
        val rig = rig(navigation = navigation(back = { androidBacks.incrementAndGet() == 1 }))
        try {
            rig.start()
            assertEquals(PanelAssistantCommandResult.Applied, submitNative(rig, "back", "PRESS"))
            // Neither root nor the accessibility service carried it out.
            assertEquals(PanelAssistantCommandResult.Failed("hardware_unavailable"), submitNative(rig, "back", "PRESS"))
            assertEquals(2, androidBacks.get())
        } finally { rig.close() }
    }

    @Test fun recentAppsOpenUnlessKioskLockHoldsTheDashboard() {
        val recents = AtomicInteger()
        val rig = rig(navigation = navigation(recents = { recents.incrementAndGet(); true }))
        try {
            rig.start()
            assertEquals(PanelAssistantCommandResult.Applied, submitNative(rig, "recents", "PRESS"))
            assertEquals(1, recents.get())
        } finally { rig.close() }
        val absent = rig(navigation = navigation())
        try {
            absent.start()
            // Neither root nor the accessibility service carried it out.
            assertEquals(PanelAssistantCommandResult.Failed("hardware_unavailable"), submitNative(absent, "recents", "PRESS"))
        } finally { absent.close() }
        val locked = rig(navigation = navigation(recents = { recents.incrementAndGet(); true }), configure = { it.commitKioskLock(true) })
        try {
            locked.start()
            assertEquals(PanelAssistantCommandResult.Failed("hardware_unavailable"), submitNative(locked, "recents", "PRESS"))
            assertEquals(1, recents.get())
        } finally { locked.close() }
    }

    @Test fun thePreviousPageIsTheNearestEarlierEntryShowingAnotherPage() {
        assertEquals(1, BuiltinDashboard.previousPageSteps(listOf("/home", "/cameras"), 1))
        // An idle return replaced the entry with the page it already showed.
        assertEquals(2, BuiltinDashboard.previousPageSteps(listOf("/home", "/cameras", "/cameras"), 2))
        assertNull(BuiltinDashboard.previousPageSteps(listOf("/home", "/home"), 1))
        assertNull(BuiltinDashboard.previousPageSteps(listOf("/home"), 0))
    }

    @Test fun showingTheSamePageAgainNavigatesToIt() {
        val rig = rig(configure = {
            it.setRaw(requireNotNull(io.panelassistant.android.config.SettingsRegistry.spec("home_dashboard")), "lovelace/home")
        })
        try {
            rig.start()
            BuiltinDashboard.navPath = null
            assertEquals(PanelAssistantCommandResult.Applied, submitNative(rig, "navigate", "/lovelace/cameras"))
            assertEquals("lovelace/cameras", BuiltinDashboard.consumeNavPath()?.trimStart('/'))
            // The page moved by touch since; the same request must take the panel back there.
            assertEquals(PanelAssistantCommandResult.Applied, submitNative(rig, "navigate", "/lovelace/cameras"))
            assertEquals("lovelace/cameras", BuiltinDashboard.consumeNavPath()?.trimStart('/'))
        } finally {
            BuiltinDashboard.navPath = null
            rig.close()
        }
    }
}
