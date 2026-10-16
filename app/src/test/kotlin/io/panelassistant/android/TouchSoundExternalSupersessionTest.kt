package io.panelassistant.android

import io.panelassistant.android.control.BootChimeHardware
import io.panelassistant.android.control.BootChimeState
import io.panelassistant.android.control.ControlApplyOutcome
import io.panelassistant.android.control.NEVER_SCREEN_TIMEOUT_MS
import io.panelassistant.android.control.ScreenTimeoutController
import io.panelassistant.android.panelassistant.PanelAssistantCommandProcessor
import io.panelassistant.android.panelassistant.PanelAssistantCommandResult
import io.panelassistant.android.testsupport.PanelRuntimeRig
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A failed external actuation must not resurrect the value it replaced.
 *
 * Touch sound persists intent before it actuates, so an external command leaves newer durable truth behind
 * even when the actuation that follows fails. That failure throws, and the throw skips the supersession
 * step that normally retires the older queued HTTP value — so the stale value survives in the journal
 * and startup replay commits it back over the newer one. These cases drive the real
 * [LiveSettingAuthority] through that exact sequence.
 */
internal class TouchSoundExternalSupersessionTest : PanelRuntimeRig() {

    /** The inversion itself, end to end on the real authority. */
    @Test fun anUnavailableExternalChangeStillRetiresTheOlderQueuedValue() {
        val config = FakeConfig()
        val authority = LiveSettingAuthority(setOf(KEY))

        // A user turned touch sound off from the panel. WRITE_SETTINGS is refused, so the value is
        // durable and queued rather than applied.
        val http = authority.applyOrQueueOutcome(KEY, "false", null) { _, value, _ ->
            config.commit(value)
            LiveSettingApplyResult.UNAVAILABLE
        }
        assertEquals(LiveSettingRequestOutcome.FAILED_PENDING, http)
        assertEquals("false", config.value)
        assertEquals(mapOf(KEY to "false"), authority.pendingSnapshot())

        // Home Assistant then turns it on. The handler commits first and the actuation fails.
        val committed = dispatchExternal(config, actuation = ControlApplyOutcome.UNAVAILABLE, value = "true")
        assertEquals(KEY, committed)
        assertEquals("true", config.value)

        // The failure path must still retire the older queued value.
        supersededKeyAfterFailedDispatch(commandKey = KEY, committedKey = committed)
            ?.let { assertTrue(authority.discard(it)) }
        assertTrue("the stale queued value survived", authority.pendingSnapshot().isEmpty())

        // And a restart must not be able to put it back.
        authority.replay { _, value, _ -> config.commit(value); LiveSettingApplyResult.APPLIED }
        assertEquals("the older HTTP value was replayed over the newer external one", "true", config.value)
    }

    /**
     * The other half of the rule, and the one that keeps it honest: a command that threw *before*
     * committing anything must leave the queued HTTP value exactly where it was. An unauthorized or
     * refused external command is not a newer truth.
     */
    @Test fun aCommandThatCommittedNothingLeavesTheQueuedValueAlone() {
        val config = FakeConfig()
        val authority = LiveSettingAuthority(setOf(KEY))

        authority.applyOrQueueOutcome(KEY, "false", null) { _, value, _ ->
            config.commit(value)
            LiveSettingApplyResult.UNAVAILABLE
        }
        assertEquals(mapOf(KEY to "false"), authority.pendingSnapshot())

        // Refused before the commit: nothing durable moved.
        val committed = dispatchExternal(config, actuation = null, value = "true")
        assertNull(committed)
        assertEquals("false", config.value)

        assertNull(supersededKeyAfterFailedDispatch(commandKey = KEY, committedKey = committed))
        assertEquals(mapOf(KEY to "false"), authority.pendingSnapshot())
    }

    /** A key committed by an earlier dispatch cannot retire a value the current command never named. */
    @Test fun aKeyFromAnotherCommandIsNeverSuperseded() {
        assertNull(supersededKeyAfterFailedDispatch(commandKey = KEY, committedKey = "silence_boot_chime"))
        assertNull(supersededKeyAfterFailedDispatch(commandKey = null, committedKey = KEY))
        assertEquals(KEY, supersededKeyAfterFailedDispatch(commandKey = KEY, committedKey = KEY))
    }

    /** Through the production dispatcher: the actuation throws after the intent is durable. */
    @Test fun aFailedExternalTouchSoundStillSupersedesTheQueuedValue() {
        val superseded = CopyOnWriteArrayList<String>()
        val rig = rig(onExternalSettingApplied = { superseded += it; true })
        try {
            rig.start()
            assertTrue(rig.config.commitTouchSound(false))
            val result = submitNative(rig, KEY, "ON")
            assertTrue("$result", result is PanelAssistantCommandResult.Failed)
            assertTrue("the newer intent is durable", rig.config.touchSound)
            assertEquals(listOf(KEY), superseded.toList())

            // A command refused before it committed anything leaves the queued value alone.
            superseded.clear()
            assertEquals(
                PanelAssistantCommandResult.Refused(PanelAssistantCommandProcessor.CODE_INVALID_VALUE),
                submitNative(rig, "prevent_idle_dim", io.panelassistant.android.panelassistant.CommandValue.Text("maybe")),
            )
            assertTrue(superseded.isEmpty())
        } finally { rig.close() }
    }

    private fun dispatchExternal(
        config: FakeConfig,
        actuation: ControlApplyOutcome?,
        value: String,
    ): String? {
        // Mirrors handleTouchSound: refuse before committing, or commit, mark, then actuate.
        if (actuation == null) return null
        config.commit(value)
        val committed = KEY
        if (!actuation.applied) return committed
        return committed
    }

    private class FakeConfig {
        var value: String = "true"
        fun commit(next: String) {
            value = next
        }
    }

    private companion object {
        const val KEY = "touch_sound"
    }

    /**
     * The panel refused the screen-timeout write, so the guard is not in effect. Panel Assistant must hear
     * Failed, not Applied. The intent stays durable; the `superseded` assertion pins that the older queued
     * HTTP value is still retired (it is not what makes this red on the unfixed code).
     */
    @Test fun aRefusedScreenTimeoutWriteFailsThePreventIdleDimCommand() {
        val timeout = AtomicInteger(30_000)
        val superseded = CopyOnWriteArrayList<String>()
        val rig = rig(
            screenOffTimeout = ScreenTimeoutController(read = timeout::get, write = { false }),
            onExternalSettingApplied = { superseded += it; true },
            configure = { it.setPreventIdleDim(false) },
        )
        try {
            rig.start()
            // ON is not a safety reduction, so no local approval is involved.
            val result = submitNative(rig, "prevent_idle_dim", "ON")
            assertTrue("a refused timeout write answered $result", result is PanelAssistantCommandResult.Failed)
            assertEquals("the panel's timeout never moved", 30_000, timeout.get())
            assertTrue("the newer intent is durable", rig.config.preventIdleDim)
            assertEquals(listOf("prevent_idle_dim"), superseded.toList())
        } finally { rig.close() }
    }

    /** The same command on a panel that accepts the write: applied, and the timeout really is "never". */
    @Test fun anAcceptedScreenTimeoutWriteAppliesThePreventIdleDimCommand() {
        val timeout = AtomicInteger(30_000)
        val rig = rig(
            screenOffTimeout = ScreenTimeoutController(read = timeout::get, write = { timeout.set(it); true }),
            configure = { it.setPreventIdleDim(false) },
        )
        try {
            rig.start()
            assertEquals(PanelAssistantCommandResult.Applied, submitNative(rig, "prevent_idle_dim", "ON"))
            assertEquals(NEVER_SCREEN_TIMEOUT_MS, timeout.get())
            assertTrue(rig.config.preventIdleDim)
        } finally { rig.close() }
    }

    /**
     * Boot chime persists ON before it writes the hardware, like touch sound, so a failed silence from Home
     * Assistant leaves newer durable intent behind. An older queued Configure-page value must be retired, or
     * its retry commits that older value back over the one the owner just chose.
     */
    @Test fun aFailedExternalBootChimeSilenceStillSupersedesTheQueuedValue() {
        val superseded = CopyOnWriteArrayList<String>()
        val silenceFails = object : BootChimeHardware {
            override fun capture() = BootChimeState(5, 5, 5, 5, 5)
            override fun silence() = ControlApplyOutcome.FAILED
            override fun restore(state: BootChimeState) = ControlApplyOutcome.APPLIED
        }
        val rig = rig(bootChimeHardware = silenceFails, onExternalSettingApplied = { superseded += it; true })
        try {
            rig.start()
            val result = submitNative(rig, "silence_boot_chime", "ON")
            assertTrue("$result", result is PanelAssistantCommandResult.Failed)
            assertTrue("the newer intent is durable", rig.config.silenceBootChime)
            assertEquals(
                "a failed silence left an older queued value alive to be replayed over the newer ON",
                listOf("silence_boot_chime"),
                superseded.toList(),
            )
        } finally { rig.close() }
    }
}
