package io.panelassistant.android

import io.panelassistant.android.control.ControlApplyOutcome
import io.panelassistant.android.control.FakeRootShell
import io.panelassistant.android.control.TouchSoundHardware
import io.panelassistant.android.control.TouchSoundState
import io.panelassistant.android.control.networkAdbEnableCommand
import io.panelassistant.android.panelassistant.CommandValue
import io.panelassistant.android.panelassistant.PanelAssistantCommandProcessor
import io.panelassistant.android.panelassistant.PanelAssistantCommandResult
import io.panelassistant.android.testsupport.PanelRuntimeRig
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A setting's newest value wins whichever door it came through. A value the panel's pages asked for that could
 * not be applied stays journalled and is retried; a newer value Home Assistant applies retires it at once, even
 * one that restates what is stored or is never stored at all, while an older page value landing late never
 * retires a newer page value. These drive the panel as the service wires it: the HTTP door
 * ([requestLiveSetting]), the journal with the dispatcher's applied-command hook, the service's replay and Panel
 * Assistant's commands.
 */
internal class NewerSettingWinsTest : PanelRuntimeRig() {

    private val authority = LiveSettingAuthority(PanelCommandDispatcher.APPLY_SETTING_KEYS, bootIdentity = { "boot" })
    private val touchSound = SwitchableTouchSound()

    private fun http(rig: Rig, key: String, value: String): LiveSettingRequestOutcome =
        requestLiveSetting(authority, rig.config, { rig.commands }, key, value, { true }) {}

    /** The service's retry and start-up replay of every journalled value. */
    private fun replay(rig: Rig) =
        authority.replayKeysObserved(PanelCommandDispatcher.APPLY_SETTING_KEYS) { key, value, previous, _ ->
            applyLiveSetting(rig.config, rig.commands, key, value, previous) {}
        }

    private fun touchSoundRig(commitsSucceed: () -> Boolean = { true }): Rig = rig(
        touchSoundHardware = touchSound,
        commitsSucceed = commitsSucceed,
        onExternalSettingApplied = authority::discard,
    ).also {
        it.start()
        assertEquals(PanelAssistantCommandResult.Applied, submitNative(it, KEY, "ON"))
        assertEquals(1, touchSound.effects)
    }

    /** The PMARCH-09 acceptance: HTTP OFF, then Panel Assistant ON, leaves ON stored and actuated. */
    @Test fun anOlderPageValueThatFailedNeverReplaysOverANewerPanelAssistantValue() {
        val rig = touchSoundRig()
        try {
            touchSound.refuse = true
            assertEquals(LiveSettingRequestOutcome.FAILED_PENDING, http(rig, KEY, "false"))
            assertEquals(mapOf(KEY to "false"), authority.pendingSnapshot())

            touchSound.refuse = false
            assertEquals(PanelAssistantCommandResult.Applied, submitNative(rig, KEY, "ON"))
            assertEquals("the page stops showing the older value as waiting", emptyMap<String, String>(), authority.pendingSnapshot())

            replay(rig)
            assertTrue("the newer value is stored", rig.config.touchSound)
            assertEquals("the newer value is actuated", 1, touchSound.effects)
        } finally { rig.close() }
    }

    /** Panel Assistant's newer value is stored before its actuation fails: still newer, so the older one goes. */
    @Test fun aNewerPanelAssistantValueStoredBeforeItsActuationFailedStillSupersedes() {
        val rig = touchSoundRig()
        try {
            touchSound.refuse = true
            assertEquals(LiveSettingRequestOutcome.FAILED_PENDING, http(rig, KEY, "false"))

            val result = submitNative(rig, KEY, "ON")
            assertTrue("$result", result is PanelAssistantCommandResult.Failed)
            assertTrue("the newer intent is durable", rig.config.touchSound)
            assertEquals(emptyMap<String, String>(), authority.pendingSnapshot())

            touchSound.refuse = false
            replay(rig)
            assertTrue("the older value was replayed over the newer one", rig.config.touchSound)
        } finally { rig.close() }
    }

    /** A Panel Assistant command refused before it stored anything is not newer intent: the page's value still applies. */
    @Test fun aRefusedPanelAssistantCommandLeavesThePageValueToApply() {
        val rig = touchSoundRig()
        try {
            touchSound.refuse = true
            assertEquals(LiveSettingRequestOutcome.FAILED_PENDING, http(rig, KEY, "false"))
            assertEquals(
                PanelAssistantCommandResult.Refused(PanelAssistantCommandProcessor.CODE_INVALID_VALUE),
                submitNative(rig, KEY, CommandValue.Text("maybe")),
            )
            assertEquals(mapOf(KEY to "false"), authority.pendingSnapshot())

            touchSound.refuse = false
            replay(rig)
            assertFalse(rig.config.touchSound)
            assertEquals("the page's value reached the hardware", 0, touchSound.effects)
            assertEquals(emptyMap<String, String>(), authority.pendingSnapshot())
        } finally { rig.close() }
    }

    /** A page store write that was refused is stored and applied by its retry once the store accepts writes. */
    @Test fun aPageValueTheStoreRefusedIsStoredAndAppliedOnRetry() {
        val commits = AtomicBoolean(true)
        val rig = touchSoundRig(commitsSucceed = commits::get)
        try {
            commits.set(false)
            assertEquals(LiveSettingRequestOutcome.FAILED_PENDING, http(rig, KEY, "false"))
            assertTrue("nothing was stored", rig.config.touchSound)

            commits.set(true)
            replay(rig)
            assertFalse("the retry stored the page's value", rig.config.touchSound)
            assertEquals("and applied it", 0, touchSound.effects)
            assertEquals(emptyMap<String, String>(), authority.pendingSnapshot())
        } finally { rig.close() }
    }

    /** A navigation bar mode that cannot apply here waits; a newer mode from Home Assistant retires it. */
    @Test fun aNavbarModeWaitingToApplyGivesWayToANewerPanelAssistantMode() {
        val rig = rig(navbarAcknowledges = true, onExternalSettingApplied = authority::discard)
        try {
            rig.start()
            val before = rig.config.navbarMode
            // This panel has no native bar, so "Native" cannot apply here: it waits.
            assertEquals(LiveSettingRequestOutcome.FAILED_PENDING, http(rig, "navbar_mode", "Native"))
            replay(rig)
            assertEquals("still waiting", mapOf("navbar_mode" to "Native"), authority.pendingSnapshot())
            assertEquals(before, rig.config.navbarMode)

            assertEquals(PanelAssistantCommandResult.Applied, submitNative(rig, "navbar", "swipe_reveal"))
            replay(rig)
            assertEquals("Swipe reveal", rig.config.navbarMode)
            assertEquals(emptyMap<String, String>(), authority.pendingSnapshot())
        } finally { rig.close() }
    }

    /**
     * Network ADB is stored only once applied. Home Assistant's OFF restates what is stored, yet it is newer than
     * the page's failed ON, so the ON must never turn the port on later.
     */
    @Test fun aPanelAssistantOffThatRestatesTheStoredValueStillRetiresAFailedPageOn() {
        val adb = ScriptedAdbRoot().apply { refuseAll = true }
        val rig = rig(adbRoot = adb.shell, onExternalSettingApplied = authority::discard)
        try {
            rig.start()
            assertEquals(LiveSettingRequestOutcome.FAILED_PENDING, http(rig, "network_adb", "true"))
            assertFalse(rig.config.networkAdbEnabled)

            adb.refuseAll = false
            assertEquals(PanelAssistantCommandResult.Applied, submitNative(rig, "network_adb", "OFF"))
            assertEquals(emptyMap<String, String>(), authority.pendingSnapshot())

            replay(rig)
            assertFalse("the page's older ON opened the port", rig.config.networkAdbEnabled)
            assertFalse(adb.shell.ran.contains(networkAdbEnableCommand()))
        } finally { rig.close() }
    }

    /** The CPU governor is never stored, so only the applied command can say Home Assistant's governor is newer. */
    @Test fun aNewerPanelAssistantGovernorRetiresAFailedPageGovernor() {
        val rig = rig(cpuGovernor = "schedutil", onExternalSettingApplied = authority::discard)
        try {
            rig.start()
            rig.cpuShell.runResult = false
            assertEquals(LiveSettingRequestOutcome.FAILED_PENDING, http(rig, "cpu_governor", "Performance"))

            rig.cpuShell.runResult = true
            assertEquals(PanelAssistantCommandResult.Applied, submitNative(rig, "cpu_governor", "efficiency"))
            assertEquals(emptyMap<String, String>(), authority.pendingSnapshot())

            replay(rig)
            assertEquals("the page's older governor replaced the newer one", "powersave", rig.metrics.governor)
        } finally { rig.close() }
    }

    /**
     * Two page values for the navigation bar: Swipe reveal is still being drawn when Native is asked for. Swipe
     * reveal then lands, and Native cannot apply on this panel. The older value landing is not newer intent, so
     * Native keeps waiting rather than being answered applied and cleared.
     */
    @Test fun anOlderPageValueLandingLateDoesNotRetireANewerPageValue() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val holding = AtomicBoolean(true)
        val rig = rig(
            navbarAcknowledges = true,
            beforeNavbarAcknowledged = {
                if (holding.get()) {
                    entered.countDown()
                    check(release.await(20, TimeUnit.SECONDS)) { "navbar change was never released" }
                }
            },
            onExternalSettingApplied = authority::discard,
        )
        try {
            rig.start()
            val older = thread { http(rig, "navbar_mode", "Swipe reveal") }
            assertTrue("the older page value is being drawn", entered.await(10, TimeUnit.SECONDS))

            // Queued behind the running change: the request answers before it runs.
            assertEquals(LiveSettingRequestOutcome.DEFERRED, http(rig, "navbar_mode", "Native"))
            holding.set(false)
            release.countDown()
            older.join(20_000)
            barrier(rig, "navbar")

            assertEquals("the older value landed", "Swipe reveal", rig.config.navbarMode)
            assertEquals("the newer value still waits", mapOf("navbar_mode" to "Native"), authority.pendingSnapshot())

            replay(rig)
            assertEquals("a value that never applied was answered applied and cleared", mapOf("navbar_mode" to "Native"), authority.pendingSnapshot())
            assertEquals("Swipe reveal", rig.config.navbarMode)
        } finally {
            release.countDown()
            rig.close()
        }
    }

    /**
     * SOUND_EFFECTS_ENABLED, off at first and writable unless [refuse] stands in for a denied WRITE_SETTINGS.
     */
    private class SwitchableTouchSound : TouchSoundHardware {
        @Volatile var refuse = false
        @Volatile var effects = 0

        override fun capture() = TouchSoundState(effects)
        override fun enable() = write(1)
        override fun restore(state: TouchSoundState) = write(state.effectsSetting ?: 0)
        override fun disableConservatively() = write(0)

        private fun write(next: Int): ControlApplyOutcome {
            if (refuse) return ControlApplyOutcome.UNAVAILABLE
            effects = next
            return ControlApplyOutcome.APPLIED
        }
    }

    /** Root for network ADB that a test can refuse outright. */
    private class ScriptedAdbRoot {
        @Volatile var refuseAll = false

        val shell: FakeRootShell = run {
            lateinit var shell: FakeRootShell
            shell = FakeRootShell(onRun = { shell.runResult = !refuseAll })
            shell
        }
    }

    private companion object {
        const val KEY = "touch_sound"
    }
}
