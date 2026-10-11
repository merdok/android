package io.panelassistant.android.http

import android.content.ContextWrapper
import io.panelassistant.android.config.Capabilities
import io.panelassistant.android.DashboardEntityBackupState
import io.panelassistant.android.migration.RestoreAttempt
import io.panelassistant.android.util.InstallProgress
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RestoreExecutorTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun importsPrecedeConfigRearmingAndRemainAfterRollback() = runBlocking {
        val catalog = wakeWordCatalog(folder.newFolder())
        PaneldServerHttpFixture().use { fixture ->
            val ticket = requireNotNull(InstallProgress.start("restore"))
            var notified = false
            var rolledBack = false
            val answers = mutableListOf<Boolean>()
            try {
                val executor = executor(
                    fixture,
                    wakeWordsChanged = {
                        assertEquals(listOf("porch"), catalog.available().map { it.id })
                        notified = true
                    },
                    commit = RestoreConfigCommit { _, _, _, _, publish, _, _ ->
                        assertTrue(notified)
                        assertEquals(listOf("porch"), catalog.available().map { it.id })
                        publish("durable")
                        error("configuration failed after rearming")
                    },
                    rollback = { _, _, _, _ -> rolledBack = true; true },
                )
                executor.execute(
                    RestoreConfigPlan(emptyMap(), emptyList(), emptyList()),
                    null, null, null, null, emptyMap(), emptyList(), false, true,
                    listOf(importedWakeWord("porch")), catalog, ticket, RestoreAttempt { answers += it },
                )
                val result = JSONObject(InstallProgress.json()).getJSONObject("result")
                assertTrue(rolledBack)
                assertEquals("failed", result.getString("status"))
                assertEquals("succeeded", result.getJSONObject("wake_words").getString("status"))
                assertEquals(listOf("porch"), catalog.exportImported().map { it.id })
                assertEquals(listOf(false), answers)
            } finally {
                InstallProgress.finish(ticket, "test cleanup")
            }
        }
    }

    @Test fun refusedModelsMakeSuccessfulConfigPartialAndDoNotCompleteMigration() = runBlocking {
        val catalog = wakeWordCatalog(folder.newFolder(), accepts = false)
        PaneldServerHttpFixture().use { fixture ->
            val ticket = requireNotNull(InstallProgress.start("restore"))
            var committed = false
            val answers = mutableListOf<Boolean>()
            try {
                val executor = executor(
                    fixture,
                    commit = RestoreConfigCommit { _, _, _, _, publish, _, _ ->
                        committed = true
                        publish("durable")
                        1
                    },
                    rollback = { _, _, _, _ -> error("partial import does not roll back configuration") },
                )
                executor.execute(
                    RestoreConfigPlan(emptyMap(), emptyList(), emptyList()),
                    null, null, null, null, emptyMap(), emptyList(), false, true,
                    listOf(importedWakeWord("porch")), catalog, ticket, RestoreAttempt { answers += it },
                )
                val status = JSONObject(InstallProgress.json())
                val result = status.getJSONObject("result")
                assertTrue(committed)
                assertEquals("partial", result.getString("status"))
                assertEquals("partial", result.getJSONObject("wake_words").getString("status"))
                assertEquals(0, result.getJSONObject("wake_words").getInt("items"))
                assertTrue(status.getString("message").contains("1 wake word not restored (porch)"))
                assertEquals(listOf(false), answers)
                assertTrue(catalog.exportImported().isEmpty())
            } finally {
                InstallProgress.finish(ticket, "test cleanup")
            }
        }
    }

    @Test fun rollbackUsesLatestDurableRevisionAndSameTicket() = runBlocking {
        for (rollbackSucceeds in listOf(true, false)) {
            PaneldServerHttpFixture().use { fixture ->
                val ticket = requireNotNull(InstallProgress.start("restore"))
                val before = mapOf("panel_id" to "prior-panel")
                var rollbackCount = 0
                val answers = mutableListOf<Boolean>()
                try {
                    val executor = executor(
                        fixture,
                        currentValues = { before },
                        commit = RestoreConfigCommit { _, _, _, receivedTicket, publish, _, _ ->
                            assertSame(ticket, receivedTicket)
                            publish("first-durable-revision")
                            publish("latest-durable-revision")
                            error("failure after live settings were persisted")
                        },
                        rollback = { values, _, expected, receivedTicket ->
                            assertEquals(before, values)
                            assertEquals("latest-durable-revision", expected)
                            assertSame(ticket, receivedTicket)
                            rollbackCount++
                            rollbackSucceeds
                        },
                    )
                    executor.execute(
                        RestoreConfigPlan(mapOf("panel_id" to "new-panel"), emptyList(), emptyList()),
                        null, null, null, null, emptyMap(), emptyList(), false, false,
                        emptyList(), null,
                        ticket, RestoreAttempt { answers += it },
                    )
                    val result = JSONObject(InstallProgress.json()).getJSONObject("result")
                    assertEquals(1, rollbackCount)
                    assertEquals(if (rollbackSucceeds) "failed" else "partial", result.getString("status"))
                    assertEquals(
                        if (rollbackSucceeds) "rolled_back" else "rollback_failed",
                        result.getJSONObject("config").getString("status"),
                    )
                    assertEquals(listOf(false), answers)
                    assertFalse(InstallProgress.running)
                } finally {
                    InstallProgress.finish(ticket, "test cleanup")
                }
            }
        }
    }

    @Test fun incompleteArchiveReportsItsMissingStateAfterSuccessfulApply() = runBlocking {
        PaneldServerHttpFixture().use { fixture ->
            val ticket = requireNotNull(InstallProgress.start("restore"))
            val answers = mutableListOf<Boolean>()
            var committed = false
            try {
                val executor = executor(
                    fixture,
                    commit = RestoreConfigCommit { values, _, _, receivedTicket, publish, _, afterApply ->
                        assertSame(ticket, receivedTicket)
                        committed = true
                        publish("committed")
                        afterApply()
                        values.size
                    },
                    rollback = { _, _, _, _ -> error("successful restore must not roll back") },
                )
                executor.execute(
                    RestoreConfigPlan(mapOf("panel_id" to "new-panel"), emptyList(), emptyList()),
                    null, null, null, null, emptyMap(), emptyList(), true, false,
                    emptyList(), null,
                    ticket, RestoreAttempt { answers += it },
                )
                val status = JSONObject(InstallProgress.json())
                assertEquals(true, committed)
                assertEquals("succeeded", status.getJSONObject("result").getString("status"))
                assertEquals("Restore completed; this backup carried no panel state", status.getString("message"))
                assertEquals(listOf(true), answers)
                assertFalse(InstallProgress.running)
            } finally {
                InstallProgress.finish(ticket, "test cleanup")
            }
        }
    }

    @Test fun executionRechecksAvailabilityInsteadOfTrustingThePreview() = runBlocking {
        PaneldServerHttpFixture().use { fixture ->
            var caps = Capabilities(networkAdb = true)
            val requested = RestoreConfigPlan(mapOf("panel_id" to "restored", "network_adb" to "true"), emptyList(), emptyList())
            val preview = requested.withAvailableLiveSettings(caps)
            assertTrue(preview.values.containsKey("network_adb"))
            caps = Capabilities(networkAdb = false)
            val ticket = requireNotNull(InstallProgress.start("restore"))
            val answers = mutableListOf<Boolean>()
            try {
                var applied: Map<String, String>? = null
                executor(
                    fixture, capabilities = { caps },
                    commit = RestoreConfigCommit { values, _, _, _, publish, _, _ ->
                        applied = values
                        publish("durable")
                        values.size
                    },
                    rollback = { _, _, _, _ -> error("known skips do not roll back supported values") },
                ).execute(
                    preview, null, null, null, null, emptyMap(), emptyList(), false, true,
                    emptyList(), null, ticket, RestoreAttempt { answers += it },
                )
                assertEquals(mapOf("panel_id" to "restored"), applied)
                val status = JSONObject(InstallProgress.json())
                val result = status.getJSONObject("result")
                assertEquals("partial", result.getString("status"))
                assertEquals("partial", result.getJSONObject("config").getString("status"))
                assertTrue(result.getJSONObject("config").getString("detail").contains("network_adb"))
                assertEquals("restore-partial", status.getJSONObject("presentation").getString("code"))
                assertEquals(listOf(false), answers)
            } finally {
                InstallProgress.finish(ticket, "test cleanup")
            }
        }
    }

    @Test fun initiallyUnavailableLiveValuesAreNotRetriedWhenSupportedApplyRollsBack() = runBlocking {
        PaneldServerHttpFixture().use { fixture ->
            val ticket = requireNotNull(InstallProgress.start("restore"))
            var rollbackValues: Map<String, String>? = null
            try {
                executor(
                    fixture,
                    capabilities = { Capabilities(networkAdb = false) },
                    currentValues = { mapOf("panel_id" to "before", "network_adb" to "false") },
                    commit = RestoreConfigCommit { _, _, _, _, publish, _, _ ->
                        publish("durable")
                        error("supported setting failed")
                    },
                    rollback = { values, _, _, _ -> rollbackValues = values; true },
                ).execute(
                    RestoreConfigPlan(mapOf("panel_id" to "restored", "network_adb" to "true"), emptyList(), emptyList()),
                    null, null, null, null, emptyMap(), emptyList(), false, false,
                    emptyList(), null, ticket, RestoreAttempt {},
                )
                assertEquals(mapOf("panel_id" to "before"), rollbackValues)
                val result = JSONObject(InstallProgress.json()).getJSONObject("result")
                assertEquals("failed", result.getString("status"))
                assertEquals("rolled_back", result.getJSONObject("config").getString("status"))
            } finally {
                InstallProgress.finish(ticket, "test cleanup")
            }
        }
    }

    @Test fun privilegeLostDuringApplyDoesNotHideAPreviouslySupportedRollbackFailure() = runBlocking {
        PaneldServerHttpFixture().use { fixture ->
            var caps = Capabilities(networkAdb = true)
            val ticket = requireNotNull(InstallProgress.start("restore"))
            var rollbackValues: Map<String, String>? = null
            try {
                executor(
                    fixture, capabilities = { caps },
                    currentValues = { mapOf("panel_id" to "before", "network_adb" to "false") },
                    commit = RestoreConfigCommit { values, _, _, _, publish, _, _ ->
                        assertEquals("true", values["network_adb"])
                        publish("durable")
                        caps = Capabilities(networkAdb = false)
                        error("network ADB apply failed after privilege was lost")
                    },
                    rollback = { values, _, _, _ -> rollbackValues = values; false },
                ).execute(
                    RestoreConfigPlan(mapOf("network_adb" to "true"), emptyList(), emptyList()),
                    null, null, null, null, emptyMap(), emptyList(), false, false,
                    emptyList(), null, ticket, RestoreAttempt {},
                )
                assertEquals("false", requireNotNull(rollbackValues)["network_adb"])
                val result = JSONObject(InstallProgress.json()).getJSONObject("result")
                assertEquals("partial", result.getString("status"))
                assertEquals("rollback_failed", result.getJSONObject("config").getString("status"))
            } finally {
                InstallProgress.finish(ticket, "test cleanup")
            }
        }
    }

    private fun executor(
        fixture: PaneldServerHttpFixture,
        currentValues: () -> Map<String, String> = { emptyMap() },
        capabilities: () -> Capabilities = { Capabilities(networkAdb = true) },
        wakeWordsChanged: () -> Unit = { error("no successful wake word import") },
        commit: RestoreConfigCommit,
        rollback: suspend (Map<String, String>, DashboardEntityBackupState, String, InstallProgress.Ticket) -> Boolean,
    ) = RestoreExecutor(
        appContext = object : ContextWrapper(null) {},
        config = fixture.config,
        currentValues = currentValues,
        capabilities = capabilities,
        revisionValues = { values, _ -> values },
        commitConfig = commit,
        rollbackConfig = rollback,
        restoreCompanion = { error("no Companion payload") },
        reconcileAfterCompanionRestore = {},
        profileAdmin = null,
        onProfileRestart = { error("no profile payload") },
        onProfileRestartAbort = { error("no profile payload") },
        onDurableStateRestored = { error("no state payload") },
        onWakeWordsChanged = wakeWordsChanged,
    )
}
