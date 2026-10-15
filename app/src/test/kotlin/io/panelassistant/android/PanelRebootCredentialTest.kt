package io.panelassistant.android

import io.panelassistant.android.control.FakeDaemon
import io.panelassistant.android.control.FakeRootShell
import io.panelassistant.android.control.FakeScreenPower
import io.panelassistant.android.control.FakeSystemEnv
import io.panelassistant.android.control.SystemController
import io.panelassistant.android.panelassistant.PanelAssistantCommandProcessor
import io.panelassistant.android.panelassistant.PanelAssistantCommandResult
import io.panelassistant.android.security.LocalApprovalBroker
import io.panelassistant.android.testsupport.PanelRuntimeRig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

internal class PanelRebootCredentialTest : PanelRuntimeRig() {
    @Test fun aCredentialedPanelRefusesBeforeAnnouncingOrRebootingThroughEitherRoute() {
        for (helper in listOf(true, false)) for (hardened in listOf(true, false)) {
            checkReboot(secure = true, helper = helper, hardened = hardened)
        }
    }

    @Test fun anUnsecuredPanelKeepsHelperAndSuReboot() {
        for (helper in listOf(true, false)) checkReboot(secure = false, helper = helper)
    }

    private fun checkReboot(secure: Boolean, helper: Boolean, hardened: Boolean = false) {
        val power = FakeScreenPower(deviceSecure = secure)
        val daemon = FakeDaemon(replies = if (helper) mapOf("REBOOT AWAIT" to "OK") else emptyMap(), available = helper)
        val root = FakeRootShell()
        val notices = mutableListOf<String>()
        val system = SystemController(
            FakeSystemEnv(), root, daemon,
            beforeReboot = { notices += "reboot" }, isDeviceSecure = power::isDeviceSecure,
        )
        val rig = rig(configure = {
            if (hardened) assertTrue(it.setSecurityMode(Config.SecurityMode.HARDENED))
        })
        val broker = LocalApprovalBroker.instance
        val approvalsBefore = broker.pending().map { it.id }.toSet()
        try {
            // The fixture deliberately exposes hardware through fields; the caller remains native submit.
            rig.commands.javaClass.getDeclaredField("system").apply { isAccessible = true }.set(rig.commands, system)
            assertTrue(system.canReboot())
            val result = submitNative(rig, "reboot", "PRESS")
            if (secure) {
                assertEquals(PanelAssistantCommandResult.Refused(PanelAssistantCommandProcessor.CODE_NOT_COMMANDABLE), result)
                assertTrue("no restart notice for refused reboot", notices.isEmpty())
                assertTrue("no helper reboot", daemon.sent.isEmpty())
                assertTrue("no su reboot", root.ran.isEmpty())
                assertEquals(approvalsBefore, broker.pending().map { it.id }.toSet())
            } else {
                assertEquals(PanelAssistantCommandResult.Applied, result)
                assertEquals(listOf("reboot"), notices)
                assertEquals(helper, "REBOOT AWAIT" in daemon.sent && "reboot" !in root.ran)
                assertEquals(!helper, "reboot" in root.ran)
            }
        } finally {
            broker.pending().filter { it.id !in approvalsBefore }.forEach { broker.deny(it.id) }
            rig.close()
        }
    }
}
