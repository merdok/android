package io.panelassistant.android

import io.panelassistant.android.control.FakeRootShell
import io.panelassistant.android.control.networkAdbEnableCommand
import io.panelassistant.android.testsupport.PanelRuntimeRig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Network ADB ON from the panel's own doors on a panel where no root process can be created (rootless, or
 * root lost since start-up). The value stays journalled and replayed, but once two boots have found no path
 * the Configure page must stop saying it is about to apply. A root manager that refuses is a different
 * thing: it can say yes next time, so it stays an ordinary pending retry.
 */
internal class NetworkAdbUnavailableTest : PanelRuntimeRig() {
    private class Journal : LiveSettingAuthority.Journal {
        val values = linkedMapOf<String, LiveSettingAuthority.Pending>()
        override fun load() = values.toMap()
        override fun put(key: String, value: LiveSettingAuthority.Pending): Boolean { values[key] = value; return true }
        override fun remove(key: String): Boolean { values.remove(key); return true }
    }

    private fun authority(journal: Journal, boot: String) = LiveSettingAuthority(setOf(KEY), journal, bootIdentity = { boot })

    /** The HTTP config door: journal first, then the dispatcher's settings entry. */
    private fun Rig.post(authority: LiveSettingAuthority) = requestLiveSetting(
        authority, config, { commands }, KEY, "true", unlockKiosk = { true }, refreshAutoSleep = {},
    )

    /** The service's start-up replay of journalled values. */
    private fun Rig.replay(authority: LiveSettingAuthority) =
        authority.replayKeysObserved(setOf(KEY)) { key, value, previous, _ ->
            applyLiveSetting(config, commands, key, value, previous) {}
        }

    @Test fun networkAdbOnWithNoRootProcessStopsWaitingToApplyAfterTwoBoots() {
        val root = FakeRootShell(available = false, runResult = false, suLaunchable = false)
        val rig = rig(adbRoot = root)
        try {
            val journal = Journal()
            assertEquals(LiveSettingRequestOutcome.FAILED_PENDING, rig.post(authority(journal, "boot-a")))
            assertFalse("no port was opened, so no ownership may be recorded", rig.config.networkAdbEnabled)
            assertFalse(root.ran.contains(networkAdbEnableCommand()))

            val second = authority(journal, "boot-b")
            rig.replay(second)
            assertEquals(
                "two boots with no root process must present network_adb as stalled, not waiting",
                setOf(KEY),
                second.pendingStalledSnapshot(),
            )
            assertEquals("the intent is never dropped", mapOf(KEY to "true"), second.pendingSnapshot())
        } finally { rig.close() }
    }

    @Test fun aRootManagerThatRefusesStaysAnOrdinaryPendingRetry() {
        val root = FakeRootShell(available = true, runResult = false, suLaunchable = true)
        val rig = rig(adbRoot = root)
        try {
            val journal = Journal()
            assertEquals(LiveSettingRequestOutcome.FAILED_PENDING, rig.post(authority(journal, "boot-a")))
            val second = authority(journal, "boot-b")
            rig.replay(second)
            assertEquals(emptySet<String>(), second.pendingStalledSnapshot())
            assertEquals(mapOf(KEY to "true"), second.pendingSnapshot())
        } finally { rig.close() }
    }

    private companion object { const val KEY = "network_adb" }
}
