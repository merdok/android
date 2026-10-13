package io.panelassistant.android

import io.panelassistant.android.control.ControlApplyOutcome
import io.panelassistant.android.panelassistant.PanelAssistantCommandProcessor
import io.panelassistant.android.panelassistant.PanelAssistantCommandResult
import io.panelassistant.android.testsupport.PanelRuntimeRig
import java.util.concurrent.CopyOnWriteArrayList
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
                submitNative(rig, "prevent_idle_dim", "maybe"),
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
}
