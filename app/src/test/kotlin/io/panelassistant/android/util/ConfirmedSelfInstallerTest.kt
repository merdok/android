package io.panelassistant.android.util

import android.content.pm.PackageInstaller
import io.panelassistant.android.util.ConfirmedSelfInstaller.SessionReport
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfirmedSelfInstallerTest {
    private fun reports(vararg items: SessionReport) = Channel<SessionReport>(Channel.UNLIMITED).apply {
        items.forEach { trySend(it) }
    }

    @Test fun aConfirmedSessionShowsAndroidsConfirmationAnnouncesItOnceAndSucceeds() = runTest {
        var shown = 0
        var announced = 0
        val outcome = ConfirmedSelfInstaller.follow(
            reports(
                SessionReport(PackageInstaller.STATUS_PENDING_USER_ACTION, null),
                SessionReport(PackageInstaller.STATUS_PENDING_USER_ACTION, null),
                SessionReport(PackageInstaller.STATUS_SUCCESS, null),
            ),
            showConfirmation = { shown++; true },
            onAwaitingConfirmation = { announced++ },
        )
        assertEquals(InstallOutcome.Succeeded, outcome)
        assertEquals(2, shown)
        assertEquals(1, announced)
    }

    @Test fun anAbortedSessionIsRetryableAndNamesBothCauses() = runTest {
        val outcome = ConfirmedSelfInstaller.follow(
            reports(
                SessionReport(PackageInstaller.STATUS_PENDING_USER_ACTION, null),
                SessionReport(PackageInstaller.STATUS_FAILURE_ABORTED, "user"),
            ),
            showConfirmation = { true },
            onAwaitingConfirmation = {},
        )
        assertTrue(outcome is InstallOutcome.Retryable)
        assertEquals(
            "update not installed: cancelled on the panel or refused by its package verifier (user)",
            (outcome as InstallOutcome.Failure).message,
        )
    }

    @Test fun aConfirmationThePanelCannotShowEndsTheAttempt() = runTest {
        var announced = false
        val outcome = ConfirmedSelfInstaller.follow(
            reports(SessionReport(PackageInstaller.STATUS_PENDING_USER_ACTION, null)),
            showConfirmation = { false },
            onAwaitingConfirmation = { announced = true },
        )
        assertTrue(outcome is InstallOutcome.Retryable)
        assertEquals(false, announced)
    }

    @Test fun nobodyTappingEndsAsRetryableAfterTheTimeout() = runTest {
        val outcome = ConfirmedSelfInstaller.follow(
            reports(SessionReport(PackageInstaller.STATUS_PENDING_USER_ACTION, null)),
            showConfirmation = { true },
            onAwaitingConfirmation = {},
            timeoutMs = 1_000L,
        )
        assertEquals("update not confirmed on the panel in time", (outcome as InstallOutcome.Retryable).message)
    }

    @Test fun anIncompatiblePackageIsADurableRejection() {
        val step = ConfirmedSelfInstaller.classify(SessionReport(PackageInstaller.STATUS_FAILURE_INCOMPATIBLE, "signatures"))
        assertTrue((step as ConfirmedSelfInstaller.Step.Done).outcome is InstallOutcome.Rejected)
    }
}
