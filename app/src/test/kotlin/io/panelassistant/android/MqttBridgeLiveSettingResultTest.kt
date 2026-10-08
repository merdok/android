package io.panelassistant.android

import org.junit.Assert.assertEquals
import org.junit.Test

class MqttBridgeLiveSettingResultTest {
    @Test fun `successful execution maps to applied`() {
        assertEquals(
            LiveSettingApplyResult.APPLIED,
            liveSettingApplyResult(
                CommandQueue.RunResult(
                    CommandQueue.Admission.ACCEPTED,
                    CommandQueue.Execution.SUCCEEDED,
                ),
            ),
        )
    }

    @Test fun `closed or rejected admission maps to deferred`() {
        listOf(
            CommandQueue.Admission.CLOSED,
            CommandQueue.Admission.REJECTED,
        ).forEach { admission ->
            assertEquals(
                LiveSettingApplyResult.DEFERRED,
                liveSettingApplyResult(
                    CommandQueue.RunResult(
                        admission,
                        CommandQueue.Execution.NOT_ADMITTED,
                    ),
                ),
            )
        }
    }

    @Test fun `admitted work still pending at the response deadline maps to deferred`() {
        assertEquals(
            LiveSettingApplyResult.DEFERRED,
            liveSettingApplyResult(
                CommandQueue.RunResult(
                    CommandQueue.Admission.ACCEPTED,
                    CommandQueue.Execution.PENDING,
                ),
            ),
        )
    }

    @Test fun `admitted execution failure is not misclassified as deferred`() {
        assertEquals(
            LiveSettingApplyResult.FAILED,
            liveSettingApplyResult(
                CommandQueue.RunResult(
                    CommandQueue.Admission.ACCEPTED,
                    CommandQueue.Execution.FAILED,
                ),
            ),
        )
    }

    @Test fun `admitted work cancelled after admission is a failure`() {
        assertEquals(
            LiveSettingApplyResult.FAILED,
            liveSettingApplyResult(
                CommandQueue.RunResult(
                    CommandQueue.Admission.ACCEPTED,
                    CommandQueue.Execution.NOT_ADMITTED,
                ),
            ),
        )
    }
}
