package io.panelassistant.android.util

import android.content.pm.PackageInstaller
import io.panelassistant.android.Config
import io.panelassistant.android.persistence.SqliteStatePreferences
import io.panelassistant.android.persistence.StateMutation
import io.panelassistant.android.persistence.StateMutationAdmission
import io.panelassistant.android.persistence.StateNamespacePersistence
import io.panelassistant.android.persistence.StateQuiescence
import io.panelassistant.android.persistence.quiesceStateWrites
import io.panelassistant.android.util.ConfirmedSelfInstaller.SessionReport
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SelfInstallQuiescenceTest {
    @Test fun confirmedSessionDefersWritesDuringCommitButSavesDurablyWhileAwaitingTheTap() {
        Fixture().use { fixture ->
            val reports = Channel<SessionReport>(Channel.UNLIMITED)
            val awaitingTap = CountDownLatch(1)
            val installing = fixture.install(AppInstaller.InstallRoute.CONFIRMED) { submit ->
                submit {
                    fixture.installEntered.countDown()
                    assertTrue(fixture.finishInstall.await(5, TimeUnit.SECONDS))
                    reports.trySend(SessionReport(PackageInstaller.STATUS_PENDING_USER_ACTION, null))
                }?.let { return@install it }
                ConfirmedSelfInstaller.follow(
                    reports,
                    showConfirmation = { true },
                    onAwaitingConfirmation = { awaitingTap.countDown() },
                    successConfirmed = { true },
                    timeoutMs = 10_000,
                )
            }
            try {
                assertTrue(fixture.installEntered.await(5, TimeUnit.SECONDS))
                val writing = fixture.saveConfig()
                assertBlocked(writing)
                fixture.finishInstall.countDown()
                assertTrue(awaitingTap.await(5, TimeUnit.SECONDS))
                assertFalse("the Android confirmation must still be waiting", installing.isDone)
                assertTrue("Configure save must finish while waiting for the tap", awaitCompletion(writing, 2))
                fixture.assertDurable()
                reports.trySend(SessionReport(PackageInstaller.STATUS_SUCCESS, null))
                assertEquals(InstallOutcome.Succeeded, awaitCompletion(installing))
                fixture.assertDurable()
            } finally {
                reports.close()
            }
        }
    }

    @Test fun privilegedReplacementDefersWritesUntilTheFailedInstallReturns() {
        for (route in listOf(AppInstaller.InstallRoute.SU, AppInstaller.InstallRoute.DAEMON)) {
            Fixture().use { fixture ->
                val installing = fixture.install(route) {
                    fixture.installEntered.countDown()
                    assertTrue(fixture.finishInstall.await(5, TimeUnit.SECONDS))
                    InstallOutcome.Retryable("not installed")
                }
                assertTrue(fixture.installEntered.await(5, TimeUnit.SECONDS))
                val writing = fixture.saveConfig()
                assertBlocked(writing)
                fixture.finishInstall.countDown()
                assertTrue(awaitCompletion(installing) is InstallOutcome.Retryable)
                assertTrue(awaitCompletion(writing))
                fixture.assertDurable()
            }
        }
    }

    @Test fun privilegedSuccessKeepsTheWriteFenceUntilProcessReplacement() {
        Fixture().use { fixture ->
            val installing = fixture.install(AppInstaller.InstallRoute.SU) { InstallOutcome.Succeeded }
            assertEquals(InstallOutcome.Succeeded, awaitCompletion(installing))
            val writing = fixture.saveConfig()
            assertBlocked(writing)
            fixture.lease?.close()
            assertTrue(awaitCompletion(writing))
        }
    }

    @Test fun confirmedSessionWithUndrainedStateRefusesBeforeSubmission() = runBlocking {
        var submitted = false
        val refusal = InstallOutcome.Retryable("state is still being saved")
        val outcome = AppInstaller.installWithStateQuiescence(
            route = AppInstaller.InstallRoute.CONFIRMED,
            prepare = { null },
            onDeferred = { refusal },
        ) { submit ->
            submit { submitted = true } ?: InstallOutcome.Succeeded
        }
        assertEquals(refusal, outcome)
        assertFalse(submitted)
    }

    private fun <T> awaitCompletion(future: Future<T>, timeoutSeconds: Long = 5): T = try {
        future.get(timeoutSeconds, TimeUnit.SECONDS)
    } catch (timeout: TimeoutException) {
        throw AssertionError("operation did not finish within its normal bound", timeout)
    }

    private fun assertBlocked(writing: Future<Boolean>) {
        try {
            writing.get(100, TimeUnit.MILLISECONDS)
            throw AssertionError("state mutation must wait while package submission owns its fence")
        } catch (_: TimeoutException) {
            // Only the held package mutation may delay this write.
        }
    }

    private class Fixture : AutoCloseable {
        private val disk = Executors.newSingleThreadExecutor()
        private val callers = Executors.newFixedThreadPool(2)
        private val admission = StateMutationAdmission()
        private val persistence = RecordingPersistence()
        private val preferences = SqliteStatePreferences(persistence, disk, admission)
        private val config = Config(preferences)
        val installEntered = CountDownLatch(1)
        val finishInstall = CountDownLatch(1)
        @Volatile var lease: StateQuiescence? = null

        fun install(
            route: AppInstaller.InstallRoute,
            install: suspend ((() -> Unit) -> InstallOutcome.Failure?) -> InstallOutcome,
        ): Future<InstallOutcome> = callers.submit<InstallOutcome> {
            runBlocking {
                AppInstaller.installWithStateQuiescence(
                    route = route,
                    prepare = {
                        AppInstaller.prepareSelfReplace(
                            snapshot = { false }, // Optional revision failure must not prevent installation.
                            quiesce = { quiesceStateWrites(admission) { preferences.flush(5_000) } },
                            warn = {},
                        ).also { lease = it }
                    },
                    onDeferred = { InstallOutcome.Retryable("state is still being saved") },
                    install = install,
                )
            }
        }

        fun saveConfig(): Future<Boolean> = callers.submit<Boolean> {
            config.commit(config.editor().putString("home_dashboard", SAVED_DASHBOARD))
        }

        fun assertDurable() {
            assertEquals(SAVED_DASHBOARD, preferences.getString("home_dashboard", null))
            assertEquals(SAVED_DASHBOARD, persistence.snapshot()["home_dashboard"])
        }

        override fun close() {
            finishInstall.countDown()
            lease?.close()
            callers.shutdownNow()
            callers.awaitTermination(5, TimeUnit.SECONDS)
            disk.shutdownNow()
        }
    }

    private class RecordingPersistence : StateNamespacePersistence {
        private val values = linkedMapOf<String, Any>()
        @Synchronized override fun initialize(): Map<String, Any> = values.toMap()
        @Synchronized override fun persist(mutation: StateMutation): Boolean {
            if (mutation.clear) values.clear()
            mutation.changes.forEach { (key, value) ->
                if (value == null) values.remove(key) else values[key] = value
            }
            return true
        }
        @Synchronized override fun replace(snapshot: Map<String, Any>): Boolean {
            values.clear()
            values.putAll(snapshot)
            return true
        }
        @Synchronized fun snapshot(): Map<String, Any> = values.toMap()
    }

    companion object {
        private const val SAVED_DASHBOARD = "http://homeassistant.local:8123/dashboard-owner"
    }
}
