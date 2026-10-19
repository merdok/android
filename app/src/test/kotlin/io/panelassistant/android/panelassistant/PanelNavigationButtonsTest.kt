package io.panelassistant.android.panelassistant

import io.panelassistant.android.AppIdentity
import io.panelassistant.android.control.FakeDreamAccessibility
import io.panelassistant.android.control.FakeRootShell
import io.panelassistant.android.control.FakeSystemEnv
import io.panelassistant.android.control.InteractiveController
import io.panelassistant.android.platform.AccessibilityActions
import io.panelassistant.android.testsupport.PanelRuntimeRig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Panel Assistant's Dashboard and Back buttons, through the production state core and command dispatcher:
 * Dashboard is offered on every panel and brings the dashboard forward, also under kiosk lock; Back is
 * offered only while a route can perform it (a connected accessibility service, or su) and is stated
 * unsupported otherwise, so Panel Assistant removes the button.
 */
internal class PanelNavigationButtonsTest : PanelRuntimeRig() {
    private class Accessibility(private val connected: Boolean) : AccessibilityActions {
        val calls = mutableListOf<String>()
        override fun connected() = connected
        override fun back() = connected.also { calls += "back" }
        override fun recents() = false
        override fun tap(x: Int, y: Int) = false
    }

    private fun navigation(accessibility: Accessibility, root: FakeRootShell = FakeRootShell(available = false, runResult = false), canSu: Boolean = false) =
        InteractiveController(canSu = canSu, root = root, accessibility = accessibility)

    @Test(timeout = 60_000)
    fun theDashboardButtonBringsTheDashboardForwardEvenUnderKioskLock() {
        val env = FakeSystemEnv()
        // A rootless panel: neither the helper nor su starts it, so the app's own start brings it forward.
        val rig = rig(systemEnv = env, rebootSucceeds = false, configure = { it.commitKioskLock(true) })
        try {
            rig.start()
            assertTrue("dashboard" in rig.core.nativeChannelShape().served)
            assertEquals(PanelAssistantCommandResult.Applied, submitNative(rig, "dashboard", "PRESS"))
            assertEquals(listOf(AppIdentity.component(env.ownPackage, ".DashboardActivity")), env.directStarts)
        } finally {
            rig.close()
        }
    }

    @Test(timeout = 60_000)
    fun theDashboardButtonEndsARunningScreensaverFirst() {
        val env = FakeSystemEnv()
        val screensaver = FakeDreamAccessibility(dreaming = true)
        val rig = rig(systemEnv = env, systemAccessibility = screensaver, rebootSucceeds = false)
        try {
            rig.start()
            assertEquals(PanelAssistantCommandResult.Applied, submitNative(rig, "dashboard", "PRESS"))
            assertEquals(listOf("back"), screensaver.events)
            assertEquals(listOf(AppIdentity.component(env.ownPackage, ".DashboardActivity")), env.directStarts)
        } finally {
            rig.close()
        }
    }

    @Test(timeout = 60_000)
    fun navigateAndReloadEndARunningScreensaverFirst() {
        for (channel in listOf("navigate", "reload")) {
            val env = FakeSystemEnv()
            val screensaver = FakeDreamAccessibility(dreaming = true)
            val rig = rig(systemEnv = env, systemAccessibility = screensaver, rebootSucceeds = false)
            try {
                rig.start()
                val payload = if (channel == "navigate") "/lovelace/cameras" else "PRESS"
                assertEquals(channel, PanelAssistantCommandResult.Applied, submitNative(rig, channel, payload))
                assertEquals(channel, listOf("back"), screensaver.events)
                assertEquals(channel, listOf(AppIdentity.component(env.ownPackage, ".DashboardActivity")), env.directStarts)
            } finally {
                rig.close()
                io.panelassistant.android.control.BuiltinDashboard.navPath = null
                io.panelassistant.android.control.BuiltinDashboard.consumeSupersededReload()
            }
        }
    }

    @Test(timeout = 60_000)
    fun backGoesThroughTheAccessibilityServiceWhenItIsConnected() {
        val accessibility = Accessibility(connected = true)
        val rig = rig(navigation = navigation(accessibility))
        try {
            rig.start()
            val shape = rig.core.nativeChannelShape()
            assertTrue("back" in shape.served)
            assertFalse("back" in shape.unsupported)
            assertEquals(PanelAssistantCommandResult.Applied, submitNative(rig, "back", "PRESS"))
            assertEquals(listOf("back"), accessibility.calls)
        } finally {
            rig.close()
        }
    }

    @Test(timeout = 60_000)
    fun aRootedPanelWithoutAccessibilityGoesBackThroughSu() {
        val root = FakeRootShell(available = true, runResult = true)
        val rig = rig(navigation = navigation(Accessibility(connected = false), root, canSu = true))
        try {
            rig.start()
            assertTrue("back" in rig.core.nativeChannelShape().served)
            assertEquals(PanelAssistantCommandResult.Applied, submitNative(rig, "back", "PRESS"))
            assertEquals(listOf("input keyevent 4"), root.ran)
        } finally {
            rig.close()
        }
    }

    @Test(timeout = 60_000)
    fun aPanelWithNoBackRouteStatesBackUnsupportedAndRefusesAPress() {
        // A Companion-rendered panel: the built-in dashboard's own Back is not there either.
        val rig = rig(navigation = navigation(Accessibility(connected = false)), configure = {
            it.setRaw(requireNotNull(io.panelassistant.android.config.SettingsRegistry.spec("dashboard_package")), "io.homeassistant.companion.android")
        })
        try {
            rig.start()
            val shape = rig.core.nativeChannelShape()
            assertFalse("back" in shape.served)
            assertTrue("stated unsupported, so Panel Assistant removes the button", "back" in shape.unsupported)
            assertEquals(
                PanelAssistantCommandResult.Failed("hardware_unavailable"),
                submitNative(rig, "back", "PRESS"),
            )
        } finally {
            rig.close()
        }
    }
}
