package io.panelassistant.android.panelassistant

import io.panelassistant.android.control.BuiltinDashboard
import io.panelassistant.android.control.FakeDaemon
import io.panelassistant.android.control.FakeSystemEnv
import io.panelassistant.android.platform.ActivityRef
import io.panelassistant.android.testsupport.PanelRuntimeRig
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The channels Panel Assistant's voice commands send, through the production command dispatcher: Android
 * settings and the launcher open over the dashboard unless kiosk lock holds the panel there, and showing
 * a page that was shown before navigates to it again.
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
