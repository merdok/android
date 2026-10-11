package io.panelassistant.android.http

import io.panelassistant.android.LiveSettingApplyResult
import io.panelassistant.android.LiveSettingAuthority
import io.panelassistant.android.config.Capabilities
import io.panelassistant.android.config.SettingsRegistry
import io.panelassistant.android.control.SystemController
import io.panelassistant.android.migration.RestoreAttempt
import io.panelassistant.android.platform.ActivityRef
import io.panelassistant.android.platform.SystemEnv
import io.panelassistant.android.util.InstallProgress
import io.panelassistant.android.util.RendererPreparationCoordinator
import io.panelassistant.android.util.RendererPreparationState
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RestoreSettingsAvailabilityTest {
    @Test fun rootlessRestorePersistsSupportedValuesWithoutAdbIntentAndLeavesMigrationOpen() = runBlocking {
        PaneldServerHttpFixture().use { fixture ->
            fixture.config.setRaw(requireNotNull(SettingsRegistry.spec("friendly_name")), "Before")
            val calls = mutableListOf<Pair<String, String>>()
            val authority = LiveSettingAuthority(SettingsRegistry.liveApplyKeys().toSet(), bootIdentity = { "test-boot" })
            val executor = executor(fixture, authority, calls)
            val ticket = requireNotNull(InstallProgress.start("restore"))
            val answers = mutableListOf<Boolean>()
            try {
                executor.execute(
                    RestoreConfigPlan(mapOf("friendly_name" to "Restored panel", "ha_area" to "Kitchen", "network_adb" to "true"), emptyList(), emptyList()),
                    null, null, null, null, emptyMap(), emptyList(), false, true,
                    emptyList(), null, ticket, RestoreAttempt { answers += it },
                )
                assertEquals("Restored panel", fixture.config.friendlyName)
                assertEquals("Kitchen", fixture.config.haArea)
                assertEquals(listOf("ha_area" to "Kitchen"), calls)
                assertTrue(authority.pendingSnapshot().isEmpty())
                val status = JSONObject(InstallProgress.json())
                val result = status.getJSONObject("result")
                assertEquals("partial", result.getString("status"))
                assertEquals("partial", result.getJSONObject("config").getString("status"))
                assertEquals(2, result.getJSONObject("config").getInt("items"))
                assertTrue(result.getJSONObject("config").getString("detail").contains("network_adb"))
                assertEquals(listOf(false), answers)
                assertFalse(InstallProgress.running)
            } finally {
                InstallProgress.finish(ticket, "test cleanup")
            }
        }
    }

    @Test fun supportedLiveFailureRollsBackPersistedValuesWithoutTryingUnavailableAdb() = runBlocking {
        PaneldServerHttpFixture().use { fixture ->
            fixture.config.setRaw(requireNotNull(SettingsRegistry.spec("friendly_name")), "Before")
            fixture.config.setRaw(requireNotNull(SettingsRegistry.spec("ha_area")), "Hall")
            val calls = mutableListOf<Pair<String, String>>()
            val authority = LiveSettingAuthority(SettingsRegistry.liveApplyKeys().toSet(), bootIdentity = { "test-boot" })
            val executor = executor(fixture, authority, calls, refusedArea = "Kitchen")
            val ticket = requireNotNull(InstallProgress.start("restore"))
            try {
                executor.execute(
                    RestoreConfigPlan(mapOf("friendly_name" to "Restored panel", "ha_area" to "Kitchen", "network_adb" to "true"), emptyList(), emptyList()),
                    null, null, null, null, emptyMap(), emptyList(), false, false,
                    emptyList(), null, ticket, RestoreAttempt {},
                )
                assertEquals("Before", fixture.config.friendlyName)
                assertEquals("Hall", fixture.config.haArea)
                assertTrue(calls.contains("ha_area" to "Kitchen"))
                assertTrue(calls.contains("ha_area" to "Hall"))
                assertFalse(calls.any { it.first == "network_adb" })
                assertFalse(authority.pendingSnapshot().containsKey("network_adb"))
                val result = JSONObject(InstallProgress.json()).getJSONObject("result")
                assertEquals("failed", result.getString("status"))
                assertEquals("rolled_back", result.getJSONObject("config").getString("status"))
            } finally {
                InstallProgress.finish(ticket, "test cleanup")
            }
        }
    }

    private fun executor(
        fixture: PaneldServerHttpFixture,
        authority: LiveSettingAuthority,
        calls: MutableList<Pair<String, String>>,
        refusedArea: String? = null,
    ): RestoreExecutor {
        val config = fixture.config
        val projection = ConfigValueProjection(
            config = config, configLiveValues = { emptyMap() }, renderedLiveValues = { emptyMap() },
            pendingLiveSettings = authority::pendingSnapshot, stalledLiveSettings = { emptySet() },
            proximityJson = { error("no page") }, powerSafetyJson = { error("no page") }, haAreaCatalogJson = { error("no page") },
        )
        val renderer = RendererPreparationCoordinator(
            builtinPackage = "builtin", state = { RendererPreparationState("external", "") },
            borrow = { null }, persist = { true },
        )
        val system = SystemController(object : SystemEnv {
            override val ownPackage = "io.panelassistant.android"
            override fun isInstalled(pkg: String) = false
            override fun launchComponent(pkg: String): String? = null
            override fun homeActivities(): List<ActivityRef> = emptyList()
            override fun defaultHome(): ActivityRef? = null
            override fun directStart(component: String) = true
        })
        return AcceptedConfigTransaction.restoreExecutor(
            appContext = fixture.context, config = config, capabilities = { Capabilities(networkAdb = false) },
            transaction = {
                AcceptedConfigTransaction(
                    config = config, revisions = RevisionStore(fixture.directory), rendererPreparation = renderer,
                    system = system, values = projection,
                    applySetting = { key, value ->
                        authority.applyOrQueueOutcome(key, value, null) { appliedKey, appliedValue, _ ->
                            calls += appliedKey to appliedValue
                            when {
                                appliedKey == "network_adb" -> LiveSettingApplyResult.UNAVAILABLE
                                appliedKey == "ha_area" && appliedValue == refusedArea -> LiveSettingApplyResult.FAILED
                                else -> {
                                    SettingsRegistry.spec(appliedKey)?.let { config.setRaw(it, appliedValue) }
                                    LiveSettingApplyResult.APPLIED
                                }
                            }
                        }
                    },
                    onEntityTargetChanged = {}, setEntityLearningEnabled = { true }, effectiveDashboardIsBuiltin = { false },
                    requestTameReconcileAfterCommit = { true }, snapInvalidate = {}, onReconfigure = {},
                )
            },
            restoreCompanion = { error("no Companion") }, profileAdmin = null,
            onProfileRestart = { error("no profiles") }, onProfileRestartAbort = { error("no profiles") },
            onDurableStateRestored = { error("no state") }, onWakeWordsChanged = { error("no wake words") },
        )
    }
}
