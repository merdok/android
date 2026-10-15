package io.panelassistant.android.dashboard

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class EntityLearningSyncPolicyTest {
    @Test fun disabledAutomaticLearningIsStartupInertUntilExplicitDemand() {
        assertFalse(shouldInitializeEntityLearningOnStart(enabled = false))
        assertTrue(shouldInitializeEntityLearningOnStart(enabled = true))
    }

    private fun state(
        origin: String = "https://ha.example",
        instanceKey: String = "instance-a",
        targetKey: String = "instance-a:dashboard-a",
        dashboardPath: String = "/dashboard-a",
        credentialFingerprint: String = "credential-a",
        learningEnabled: Boolean = true,
        applied: Boolean = true,
        filterEnabled: Boolean = true,
        filterIds: List<String> = listOf("light.a"),
        autoStatic: Boolean = true,
        autoRuntime: Boolean = true,
        overrides: Map<String, String> = emptyMap(),
        forceBootstrap: Boolean = false,
    ) = EntityLearningEffectState(
        origin, instanceKey, targetKey, dashboardPath, credentialFingerprint,
        learningEnabled, applied, filterEnabled, filterIds, autoStatic, autoRuntime,
        overrides, forceBootstrap,
    )

    @Test fun syncSnapshotRejectsEveryTargetPolicyAndCredentialChange() {
        val original = state()
        val snapshot = EntityLearningSyncSnapshot(
            7,
            "https://ha.example",
            "token",
            original.dashboardPath,
            HomeDashboardResolutionAuthority.Key(
                "https://ha.example",
                io.panelassistant.android.HaAuthOwner("https://ha.example", "refresh", "client", ""),
                original.dashboardPath,
            ),
            original,
        )

        assertTrue(snapshot.matchesCurrent(7, original))
        assertFalse(snapshot.matchesCurrent(8, original))
        assertFalse(snapshot.matchesCurrent(7, original.copy(routeEpoch = 1)))
        assertFalse(snapshot.matchesCurrent(7, original.copy(origin = "https://other.example")))
        assertFalse(snapshot.matchesCurrent(7, original.copy(instanceKey = "instance-b")))
        assertFalse(snapshot.matchesCurrent(7, original.copy(targetKey = "instance-a:dashboard-b")))
        assertFalse(snapshot.matchesCurrent(7, original.copy(dashboardPath = "/dashboard-b")))
        assertFalse(snapshot.matchesCurrent(7, original.copy(credentialFingerprint = "credential-b")))
        assertFalse(snapshot.matchesCurrent(7, original.copy(autoRuntime = false)))
        assertFalse(snapshot.matchesCurrent(7, original.copy(filterIds = listOf("light.b"))))
        assertFalse(snapshot.matchesCurrent(7, original.copy(forceBootstrap = true)))
        assertFalse(snapshot.matchesCurrent(7, original.copy(initialActivationPending = true)))
    }

    @Test fun effectMutationCancelsReleasesTheActiveSyncSlotAndClearsItsQueuedRerun() {
        val active = Job()
        var queuedRerun: String? = "enable-bootstrap"

        val replacementSlot = supersedeEntityLearningSync(active) { queuedRerun = null }

        assertTrue(active.isCancelled)
        assertEquals(null, replacementSlot)
        assertEquals(null, queuedRerun)
    }

    @Test fun supersessionClearsAStaleRerunAfterTheActiveSlotWasAlreadyReleased() {
        var queuedRerun: String? = "target-refresh"

        val replacementSlot = supersedeEntityLearningSync(null) { queuedRerun = null }

        assertEquals(null, replacementSlot)
        assertEquals(null, queuedRerun)
    }

    @Test fun firstEnableInvalidatesOldEffectsBeforeStartingExactlyOneBootstrapSync() {
        val events = mutableListOf<String>()
        val obsolete = Job()
        var ownedSync: Job? = obsolete
        var initializationCalls = 0
        var bootstrapStarts = 0

        val newlyInitialized = initializeEntityLearningAfterEffectInvalidation(
            invalidate = {
                events += "invalidate"
                ownedSync = supersedeEntityLearningSync(ownedSync)
            },
            initialize = {
                initializationCalls++
                events += "initialize"
                ownedSync = Job()
                bootstrapStarts++
                true
            },
        )
        if (!newlyInitialized) bootstrapStarts++

        assertTrue(newlyInitialized)
        assertEquals(1, initializationCalls)
        assertEquals("lazy initialization owns the only required bootstrap start", 1, bootstrapStarts)
        assertEquals(listOf("invalidate", "initialize"), events)
        assertTrue(obsolete.isCancelled)
        assertTrue("the initialization-owned bootstrap sync must remain active", ownedSync?.isActive == true)
    }

    @Test fun supersededScanCannotPersistFailureOntoCurrentGeneration() {
        assertTrue(shouldPersistEntityLearningFailure(7, 7, "instance-a", "/dash", "instance-a", "/dash"))
        assertFalse(shouldPersistEntityLearningFailure(7, 8, "instance-a", "/dash", "instance-a", "/dash"))
        assertFalse(shouldPersistEntityLearningFailure(7, 7, "instance-a", "/dash", "instance-b", "/dash"))
        assertFalse(shouldPersistEntityLearningFailure(7, 7, "instance-a", "/dash", "instance-a", "/other"))
    }

    @Test fun aRouteChangeMidScanRerunsOnceInsteadOfPersistingDegraded() {
        assertEquals(ROUTE_CHANGED_RERUN_REASON, entityLearningRerunAfterFailure(EntityLearningRouteChanged(), "target-change"))
        // The rerun that loses its route again persists, so a flapping route cannot loop.
        assertNull(entityLearningRerunAfterFailure(EntityLearningRouteChanged(), ROUTE_CHANGED_RERUN_REASON))
        // Any other failure stands, including one whose text merely mentions a route.
        assertNull(entityLearningRerunAfterFailure(IllegalStateException("Home Assistant route changed"), "manual"))
        assertNull(entityLearningRerunAfterFailure(IllegalStateException("Home Assistant credential changed"), "manual"))
    }

    @Test fun concurrentDisableCannotBeOvertakenByAnAlreadyRunningActivation() {
        data class DurableState(
            var learningEnabled: Boolean = true,
            var filterEnabled: Boolean = false,
            var applied: Boolean = false,
        )

        val owner = Any()
        val state = DurableState()
        val activationEntered = CountDownLatch(1)
        val releaseActivation = CountDownLatch(1)
        val disableAttempted = CountDownLatch(1)
        val disableFinished = CountDownLatch(1)

        val activation = thread(start = true) {
            withEntityLearningMutationLock(owner) {
                activationEntered.countDown()
                releaseActivation.await()
                state.filterEnabled = true
                state.applied = true
            }
        }
        assertTrue(activationEntered.await(2, TimeUnit.SECONDS))
        val disable = thread(start = true) {
            disableAttempted.countDown()
            withEntityLearningMutationLock(owner) {
                state.learningEnabled = false
                state.filterEnabled = false
                state.applied = false
            }
            disableFinished.countDown()
        }

        assertTrue(disableAttempted.await(2, TimeUnit.SECONDS))
        assertFalse("disable must wait for the active mutation", disableFinished.await(50, TimeUnit.MILLISECONDS))
        releaseActivation.countDown()
        activation.join(2_000)
        disable.join(2_000)

        assertFalse(activation.isAlive)
        assertFalse(disable.isAlive)
        assertFalse(state.learningEnabled)
        assertFalse(state.filterEnabled)
        assertFalse(state.applied)
    }

    @Test fun resetAndScanAdmissionShareTheManagerMonitorInBothDirections() {
        val owner = Any()
        val resetEntered = CountDownLatch(1)
        val releaseReset = CountDownLatch(1)
        val scanAttempted = CountDownLatch(1)
        val scanEntered = CountDownLatch(1)
        val resetter = thread(start = true) {
            withEntityLearningMutationLock(owner) {
                resetEntered.countDown()
                releaseReset.await()
            }
        }
        assertTrue(resetEntered.await(2, TimeUnit.SECONDS))
        val scanner = thread(start = true) {
            scanAttempted.countDown()
            synchronized(owner) { scanEntered.countDown() }
        }
        assertTrue(scanAttempted.await(2, TimeUnit.SECONDS))
        assertFalse("a scan cannot be admitted during reset", scanEntered.await(50, TimeUnit.MILLISECONDS))
        releaseReset.countDown()
        resetter.join(2_000)
        scanner.join(2_000)
        assertFalse(resetter.isAlive)
        assertFalse(scanner.isAlive)
        assertEquals(0L, scanEntered.count)

        val scanHolding = CountDownLatch(1)
        val releaseScan = CountDownLatch(1)
        val resetAttempted = CountDownLatch(1)
        val queuedResetEntered = CountDownLatch(1)
        val holdingScanner = thread(start = true) {
            synchronized(owner) {
                scanHolding.countDown()
                releaseScan.await()
            }
        }
        assertTrue(scanHolding.await(2, TimeUnit.SECONDS))
        val queuedReset = thread(start = true) {
            resetAttempted.countDown()
            withEntityLearningMutationLock(owner) { queuedResetEntered.countDown() }
        }
        assertTrue(resetAttempted.await(2, TimeUnit.SECONDS))
        assertFalse("reset cannot inspect admission while a scan is being admitted",
            queuedResetEntered.await(50, TimeUnit.MILLISECONDS))
        releaseScan.countDown()
        holdingScanner.join(2_000)
        queuedReset.join(2_000)
        assertFalse(holdingScanner.isAlive)
        assertFalse(queuedReset.isAlive)
        assertEquals(0L, queuedResetEntered.count)
    }

    @Test fun cancelledScanCannotRepopulateOverridesAfterResetClearsEvidence() {
        val owner = Any()
        val evidence = mutableListOf<String>()
        val scanCommitted = CountDownLatch(1)
        val releaseScanCommit = CountDownLatch(1)
        val resetAttempted = CountDownLatch(1)
        val resetFinished = CountDownLatch(1)

        val cancelledScan = thread(start = true, isDaemon = true) {
            commitEntityLearningSyncEvidence(
                owner = owner,
                ensureCurrent = {},
                commitSync = {
                    evidence += "scan"
                    scanCommitted.countDown()
                    releaseScanCommit.await()
                },
                applyStoredOverrides = { evidence += "override" },
                publishDiagnostics = {},
            )
        }
        assertTrue(scanCommitted.await(2, TimeUnit.SECONDS))
        val reset = thread(start = true, isDaemon = true) {
            resetAttempted.countDown()
            withEntityLearningMutationLock(owner) { evidence.clear() }
            resetFinished.countDown()
        }
        assertTrue(resetAttempted.await(2, TimeUnit.SECONDS))
        assertFalse(
            "reset must not split scan evidence from its stored overrides",
            resetFinished.await(50, TimeUnit.MILLISECONDS),
        )

        releaseScanCommit.countDown()
        cancelledScan.join(2_000)
        reset.join(2_000)
        assertFalse(cancelledScan.isAlive)
        assertFalse(reset.isAlive)
        assertTrue("the later reset must win over every scan evidence write", evidence.isEmpty())
    }

    @Test fun queuedPromotionRequiresUnchangedEligiblePolicyAndNoBlockers() {
        val original = state()
        val queued = EntityLearningPromotionSnapshot(11, original)

        assertTrue(queued.isEligible(11, original, blockingIssues = 0))
        assertFalse(queued.isEligible(12, original, blockingIssues = 0))
        assertFalse(queued.isEligible(11, original.copy(learningEnabled = false), blockingIssues = 0))
        assertFalse(queued.isEligible(11, original.copy(applied = false), blockingIssues = 0))
        assertFalse(queued.isEligible(11, original.copy(autoRuntime = false), blockingIssues = 0))
        assertFalse(queued.isEligible(11, original.copy(targetKey = "instance-b:dashboard-a"), blockingIssues = 0))
        assertFalse(queued.isEligible(11, original, blockingIssues = 1))
    }

    @Test fun aPromotionThatChangesNothingIsNeverReportedAsHeldBack() {
        val applied = listOf("light.a", "light.b")
        assertEquals(RuntimePromotionDecision.UNCHANGED,
            runtimePromotionDecision(listOf("light.b", "light.a"), applied, promotionsInWindow = 2, maxPromotions = 2))
        assertEquals(RuntimePromotionDecision.UNCHANGED,
            runtimePromotionDecision(emptyList(), applied, promotionsInWindow = 0, maxPromotions = 2))
        assertEquals(RuntimePromotionDecision.CAPPED,
            runtimePromotionDecision(applied + "light.c", applied, promotionsInWindow = 2, maxPromotions = 2))
        assertEquals(RuntimePromotionDecision.PROMOTE,
            runtimePromotionDecision(applied + "light.c", applied, promotionsInWindow = 1, maxPromotions = 2))
    }

    @Test fun quietSuspendingOperationCannotOutliveWallClockDeadline() = runTest {
        var timedOut = false
        try {
            withEntityLearningDeadline(1_000) { awaitCancellation() }
        } catch (_: TimeoutCancellationException) {
            timedOut = true
        }
        assertTrue(timedOut)
    }
}
