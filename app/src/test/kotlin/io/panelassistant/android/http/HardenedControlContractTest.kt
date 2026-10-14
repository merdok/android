package io.panelassistant.android.http

import io.panelassistant.android.Config.SecurityMode
import io.panelassistant.android.control.FakeDaemon
import io.panelassistant.android.control.FakeRootShell
import io.panelassistant.android.control.FakeSystemEnv
import io.panelassistant.android.control.SystemController
import io.panelassistant.android.panelassistant.PanelAssistantCommandProcessor
import io.panelassistant.android.panelassistant.PanelAssistantCommandResult
import io.panelassistant.android.security.LocalApprovalBroker
import io.panelassistant.android.testsupport.PanelRuntimeRig
import io.panelassistant.android.testsupport.TestSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Remote commands under Hardened mode, through the production command dispatcher. */
internal class HardenedControlContractTest : PanelRuntimeRig() {
    // Source-text reason: user-visible English labels for the security modes and remote-ADB refusals.
    private val englishStrings by lazy {
        TestSources.appFile("src/main/res/values/strings.xml").readText()
    }

    private fun englishString(name: String): String {
        val marker = "<string name=\"$name\">"
        require(englishStrings.contains(marker)) { "missing English string resource $name" }
        return englishStrings.substringAfter(marker).substringBefore("</string>")
    }

    /** Runs [block] on a Hardened panel, denying every approval it leaves pending. */
    private fun hardened(systemDaemon: FakeDaemon = FakeDaemon(), block: (Rig, Set<String>) -> Unit) {
        val broker = LocalApprovalBroker.instance
        val before = broker.pending().map { it.id }.toSet()
        val rig = rig(systemDaemon = systemDaemon, configure = { assertTrue(it.setSecurityMode(SecurityMode.HARDENED)) })
        try {
            val system = SystemController(FakeSystemEnv(), FakeRootShell(), systemDaemon, isDeviceSecure = { false })
            rig.commands.javaClass.getDeclaredField("system").apply { isAccessible = true }.set(rig.commands, system)
            rig.start()
            block(rig, before)
        } finally {
            broker.pending().filter { it.id !in before }.forEach { broker.deny(it.id) }
            rig.close()
        }
    }

    @Test fun remoteRebootWaitsForApprovalBeforeItReboots() {
        val daemon = FakeDaemon(replies = mapOf("REBOOT AWAIT" to "OK"))
        hardened(daemon) { rig, before ->
            assertTrue(submitNative(rig, "reboot", "PRESS") is PanelAssistantCommandResult.ApprovalPending)
            assertFalse("rebooted before approval", "REBOOT AWAIT" in daemon.sent)
            val approval = LocalApprovalBroker.instance.pending().single { it.id !in before }
            assertTrue(LocalApprovalBroker.instance.approve(approval.id))
            assertEquals(PanelAssistantCommandResult.Applied, submitNative(rig, "reboot", "PRESS"))
            assertTrue("REBOOT AWAIT" in daemon.sent)
        }
    }

    // Hardened mode publishes a closed list of operations it protects, and a dashboard reload is on it. A
    // remote command arm once shipped ungated while the HTTP route was gated.
    @Test fun remoteReloadWaitsForApproval() {
        hardened { rig, before ->
            assertTrue(submitNative(rig, "reload", "PRESS") is PanelAssistantCommandResult.ApprovalPending)
            val approval = LocalApprovalBroker.instance.pending().single { it.id !in before }
            assertTrue(LocalApprovalBroker.instance.approve(approval.id))
            assertFalse(submitNative(rig, "reload", "PRESS") is PanelAssistantCommandResult.ApprovalPending)
        }
    }

    @Test fun networkAdbCannotBeEnabledRemotelyUnderHardenedMode() {
        hardened { rig, before ->
            assertEquals(
                PanelAssistantCommandResult.Refused(PanelAssistantCommandProcessor.CODE_REFUSED_HARDENED),
                submitNative(rig, "network_adb", "ON"),
            )
            assertFalse(rig.config.networkAdbEnabled)
            assertTrue("no approval can allow it", LocalApprovalBroker.instance.pending().none { it.id !in before })
        }
    }

    @Test fun remotePowerSafetyReductionsCannotBypassApproval() {
        hardened { rig, _ ->
            rig.config.setPreventIdleDim(true)
            assertTrue(submitNative(rig, "prevent_idle_dim", "OFF") is PanelAssistantCommandResult.ApprovalPending)
            assertTrue("nothing changed before approval", rig.config.preventIdleDim)
        }
    }

    @Test fun securityModeAndRemoteAdbLabelCatalogueContract() {
        listOf("unable_verify_remote_adb_detail", "turn_off_remote_adb_first_detail").forEach { name ->
            val message = englishString(name)
            assertTrue(message.contains("classic network ADB"))
            assertTrue(message.contains("Android Wireless debugging"))
        }
        assertTrue(englishString("security_mode_relaxed_summary").startsWith("Relaxed mode is on."))
        assertTrue(englishString("use_relaxed_mode") == "Use Relaxed mode")
        assertTrue(englishString("enable_hardened_mode") == "Enable Hardened mode")
        assertFalse(englishStrings.contains("Convenience mode"))
    }
}
