package io.panelassistant.android.dashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EntityLearningManagerSafetyTest {
    @Test fun overridePreferenceFailureNeverTouchesTheCatalog() {
        val events = mutableListOf<String>()
        val failed = runCatching {
            runEntityOverrideTransaction(
                commitOverridePreferences = { events += "prefs-failed"; false },
                applyStoreOverride = { events += "store" },
                commitActiveFilter = { events += "filter"; true },
                restoreStoreOverride = { events += "store-rollback" },
                restoreOverridePreferences = { events += "prefs-rollback"; true },
            )
        }.isFailure

        assertTrue(failed)
        assertEquals(listOf("prefs-failed"), events)
    }

    @Test fun activeFilterFailureRollsBackBothOverrideStoresAndCannotReportSuccess() {
        val events = mutableListOf<String>()
        val failed = runCatching {
            runEntityOverrideTransaction(
                commitOverridePreferences = { events += "prefs"; true },
                applyStoreOverride = { events += "store" },
                commitActiveFilter = { events += "filter-failed"; false },
                restoreStoreOverride = { events += "store-rollback" },
                restoreOverridePreferences = { events += "prefs-rollback"; true },
            )
        }.isFailure

        assertTrue(failed)
        assertEquals(
            listOf("prefs", "store", "filter-failed", "store-rollback", "prefs-rollback"),
            events,
        )
    }

    @Test fun successfulOverrideTransactionDoesNotRunRollback() {
        val events = mutableListOf<String>()
        runEntityOverrideTransaction(
            commitOverridePreferences = { events += "prefs"; true },
            applyStoreOverride = { events += "store" },
            commitActiveFilter = { events += "filter"; true },
            restoreStoreOverride = { events += "store-rollback" },
            restoreOverridePreferences = { events += "prefs-rollback"; true },
        )
        assertEquals(listOf("prefs", "store", "filter"), events)
    }

    @Test fun failedResetPreferenceCommitNeverTouchesTheCatalog() {
        val events = mutableListOf<String>()
        val failed = runCatching {
            runEntityEvidenceResetTransaction(
                commitPreferences = { events += "prefs-failed"; false },
                resetStore = { events += "store" },
                restorePreferences = { events += "prefs-rollback"; true },
                afterSuccess = { events += "cache-clear" },
            )
        }.isFailure

        assertTrue(failed)
        assertEquals(listOf("prefs-failed"), events)
    }

    @Test fun failedCatalogResetRestoresPreferencesBeforeReturningTheStoreFailure() {
        val events = mutableListOf<String>()
        var publishedState = "original"
        var blockingIssues = 3
        var dynamicExpressions = "[\"template\"]"
        val storeFailure = IllegalStateException("store failed")

        val failure = runCatching {
            runEntityEvidenceResetTransaction(
                commitPreferences = { events += "prefs-reset"; publishedState = "reset"; true },
                resetStore = { events += "store-failed"; throw storeFailure },
                restorePreferences = { events += "prefs-rollback"; publishedState = "original"; true },
                afterSuccess = {
                    events += "cache-clear"
                    blockingIssues = 0
                    dynamicExpressions = "[]"
                },
            )
        }.exceptionOrNull()

        assertTrue(failure === storeFailure)
        assertEquals("original", publishedState)
        assertEquals(3, blockingIssues)
        assertEquals("[\"template\"]", dynamicExpressions)
        assertEquals(listOf("prefs-reset", "store-failed", "prefs-rollback"), events)
        assertTrue(storeFailure.suppressed.isEmpty())
    }

    @Test fun failedResetRollbackIsAttachedWithoutMaskingTheStoreFailure() {
        val storeFailure = IllegalStateException("store failed")

        val failure = runCatching {
            runEntityEvidenceResetTransaction(
                commitPreferences = { true },
                resetStore = { throw storeFailure },
                restorePreferences = { false },
                afterSuccess = {},
            )
        }.exceptionOrNull()

        assertTrue(failure === storeFailure)
        assertEquals(1, storeFailure.suppressed.size)
        assertEquals("entity evidence reset preference rollback failed", storeFailure.suppressed.single().message)
    }

    @Test fun successfulResetTransactionDoesNotRunRollback() {
        val events = mutableListOf<String>()
        var blockingIssues = 3
        var dynamicExpressions = "[\"template\"]"

        runEntityEvidenceResetTransaction(
            commitPreferences = { events += "prefs-reset"; true },
            resetStore = { events += "store-reset" },
            restorePreferences = { events += "prefs-rollback"; true },
            afterSuccess = {
                events += "cache-clear"
                blockingIssues = 0
                dynamicExpressions = "[]"
            },
        )

        assertEquals(listOf("prefs-reset", "store-reset", "cache-clear"), events)
        assertEquals(0, blockingIssues)
        assertEquals("[]", dynamicExpressions)
    }

    @Test fun authenticatedHaConfigAddsBoundedValidIdentityAliases() {
        assertEquals(
            listOf(
                "https://configured.example/ha",
                "http://homeassistant.local:8123",
                "https://public.example/ha",
            ),
            haInstanceCandidateUrls(
                "https://configured.example/ha",
                """{
                  "internal_url":"http://homeassistant.local:8123",
                  "external_url":"https://public.example/ha",
                  "base_url":"javascript:alert(1)"
                }""",
            ),
        )
    }

    @Test fun configuredIdentityCandidateSurvivesMissingMalformedOrDuplicateConfig() {
        val configured = "https://configured.example"
        assertEquals(listOf(configured), haInstanceCandidateUrls(configured, null))
        assertEquals(listOf(configured), haInstanceCandidateUrls(configured, "not-json"))
        assertEquals(
            listOf(configured),
            haInstanceCandidateUrls(
                configured,
                """{"internal_url":"$configured","external_url":"","base_url":"ftp://invalid.example"}""",
            ),
        )
        assertEquals(
            listOf(configured),
            haInstanceCandidateUrls(
                configured,
                """{"internal_url":"https://oversized.example/${"a".repeat(3_000)}"}""",
            ),
        )
    }
}
