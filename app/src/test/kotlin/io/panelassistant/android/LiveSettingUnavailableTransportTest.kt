package io.panelassistant.android

import io.panelassistant.android.control.ControlApplyOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The classification has to cross the command dispatcher, which reports one boolean per command and
 * discards the throwable. It travels as a typed exception caught at the dispatch site, so only work that
 * actually reached a handler can carry it — coalesced, superseded and never-admitted work must stay an
 * ordinary retryable failure or a value would be presented as unappliable on evidence it never gathered.
 */
class LiveSettingUnavailableTransportTest {
    @Test fun `only a handler that ran can report unavailability`() {
        val failed = CommandQueue.RunResult(
            CommandQueue.Admission.ACCEPTED,
            CommandQueue.Execution.FAILED,
        )
        assertEquals(
            LiveSettingApplyResult.UNAVAILABLE,
            liveSettingApplyResult(failed) { true },
        )
        assertEquals(
            LiveSettingApplyResult.FAILED,
            liveSettingApplyResult(failed) { false },
        )
    }

    @Test fun `work that never reached a handler is never unavailable`() {
        // The flag belongs to one dispatch. If that dispatch was replaced or refused, nothing observed
        // the hardware. A replaced value was overtaken by a newer one for the same setting, so nothing of it
        // remains to apply.
        listOf(
            CommandQueue.RunResult(
                CommandQueue.Admission.ACCEPTED,
                CommandQueue.Execution.SUPERSEDED,
            ) to LiveSettingApplyResult.APPLIED,
            CommandQueue.RunResult(
                CommandQueue.Admission.REJECTED,
                CommandQueue.Execution.NOT_ADMITTED,
            ) to LiveSettingApplyResult.DEFERRED,
            CommandQueue.RunResult(
                CommandQueue.Admission.CLOSED,
                CommandQueue.Execution.NOT_ADMITTED,
            ) to LiveSettingApplyResult.DEFERRED,
            CommandQueue.RunResult(
                CommandQueue.Admission.ACCEPTED,
                CommandQueue.Execution.PENDING,
            ) to LiveSettingApplyResult.DEFERRED,
            CommandQueue.RunResult(
                CommandQueue.Admission.ACCEPTED,
                CommandQueue.Execution.SUCCEEDED,
            ) to LiveSettingApplyResult.APPLIED,
        ).forEach { (result, expected) ->
            assertEquals(
                "${result.admission}/${result.execution} must not depend on the unavailable flag",
                expected,
                liveSettingApplyResult(result) { true },
            )
        }
    }

    @Test fun `a handler raises the typed failure only for a structural absence`() {
        val unavailable = assertThrows(LiveSettingUnavailableException::class.java) {
            requireControlApplied("silence_boot_chime", ControlApplyOutcome.UNAVAILABLE) { "boom" }
        }
        assertEquals("silence_boot_chime", unavailable.key)

        val failed = assertThrows(IllegalStateException::class.java) {
            requireControlApplied("silence_boot_chime", ControlApplyOutcome.FAILED) { "boom" }
        }
        assertTrue(
            "an ordinary failure must not be mistaken for a missing capability",
            failed !is LiveSettingUnavailableException,
        )
        assertEquals("boom", failed.message)

        requireControlApplied("silence_boot_chime", ControlApplyOutcome.APPLIED) { "boom" }
    }
}
