package io.panelassistant.android.http

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

class RestoreKioskCapabilitiesTest {
    @Test fun rootlessPreviewAndRestoreRejectTheSameUnsupportedSetting() = runBlocking {
        PaneldServerHttpFixture().use { fixture ->
            fixture.config.setRaw(requireNotNull(SettingsRegistry.spec("friendly_name")), "Before")
            fixture.config.commitKioskLock(false)
            val plan = RestoreConfigPlan(mapOf("friendly_name" to "Restored", "kiosk_lock" to "true"), emptyList(), emptyList())
            val preview = plan.withAvailableLiveSettings(Capabilities(appCanSu = false))
            val ticket = requireNotNull(InstallProgress.start("restore"))
            try {
                executor(fixture, canEnable = false).execute(
                    plan, null, null, null, null, emptyMap(), emptyList(), false, false,
                    emptyList(), null, ticket, RestoreAttempt {},
                )
                val status = JSONObject(InstallProgress.json())
                val result = status.getJSONObject("result")
                assertEquals("caller must report partial instead of rolling back supported settings", "partial", result.getString("status"))
                assertEquals(listOf("kiosk_lock"), preview.skipped)
                assertEquals("Restored", fixture.config.friendlyName)
                assertFalse(fixture.config.kioskLock)
                assertTrue(result.getJSONObject("config").getString("detail").contains("kiosk_lock"))
            } finally { InstallProgress.finish(ticket, "test cleanup") }
        }
    }

    @Test fun rootedControlRestoresKioskAndOrdinarySettings() = runBlocking {
        PaneldServerHttpFixture().use { fixture ->
            fixture.config.setRaw(requireNotNull(SettingsRegistry.spec("friendly_name")), "Before")
            fixture.config.commitKioskLock(false)
            val plan = RestoreConfigPlan(mapOf("friendly_name" to "Restored", "kiosk_lock" to "true"), emptyList(), emptyList())
            val ticket = requireNotNull(InstallProgress.start("restore"))
            try {
                executor(fixture, canEnable = true).execute(
                    plan, null, null, null, null, emptyMap(), emptyList(), false, false,
                    emptyList(), null, ticket, RestoreAttempt {},
                )
                val result = JSONObject(InstallProgress.json()).getJSONObject("result")
                assertEquals("succeeded", result.getString("status"))
                assertEquals("Restored", fixture.config.friendlyName)
                assertTrue(fixture.config.kioskLock)
            } finally { InstallProgress.finish(ticket, "test cleanup") }
        }
    }

    @Test fun supportedKioskActuationFailureRollsBackInsteadOfBeingSkipped() = runBlocking {
        PaneldServerHttpFixture().use { fixture ->
            fixture.config.setRaw(requireNotNull(SettingsRegistry.spec("friendly_name")), "Before")
            fixture.config.commitKioskLock(false)
            val plan = RestoreConfigPlan(mapOf("friendly_name" to "Restored", "kiosk_lock" to "true"), emptyList(), emptyList())
            val ticket = requireNotNull(InstallProgress.start("restore"))
            try {
                executor(fixture, canEnable = true, actuatorSucceeds = false).execute(
                    plan, null, null, null, null, emptyMap(), emptyList(), false, false,
                    emptyList(), null, ticket, RestoreAttempt {},
                )
                val result = JSONObject(InstallProgress.json()).getJSONObject("result")
                assertEquals("failed", result.getString("status"))
                assertEquals("rolled_back", result.getJSONObject("config").getString("status"))
                assertEquals("Before", fixture.config.friendlyName)
                assertFalse(fixture.config.kioskLock)
            } finally { InstallProgress.finish(ticket, "test cleanup") }
        }
    }

    private fun executor(
        fixture: PaneldServerHttpFixture,
        canEnable: Boolean,
        actuatorSucceeds: Boolean = true,
    ): RestoreExecutor {
        val config = fixture.config
        val capabilities = Capabilities(appCanSu = canEnable, networkAdb = canEnable)
        val kiosk = io.panelassistant.android.kioskSetting(
            canEnable = { canEnable }, actuate = { actuatorSucceeds }, persist = config::commitKioskLock,
        )
        val projection = ConfigValueProjection(
            config = config, configLiveValues = { emptyMap() }, renderedLiveValues = { emptyMap() },
            pendingLiveSettings = { emptyMap() }, stalledLiveSettings = { emptySet() },
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
            appContext = fixture.context, config = config, capabilities = { capabilities },
            transaction = {
                AcceptedConfigTransaction(
                    config = config, revisions = RevisionStore(fixture.directory), rendererPreparation = renderer,
                    system = system, values = projection,
                    applySetting = { key, value ->
                        if (key == "kiosk_lock") {
                            if (kiosk(value.toBooleanStrict())) io.panelassistant.android.LiveSettingRequestOutcome.APPLIED
                            else io.panelassistant.android.LiveSettingRequestOutcome.REJECTED
                        } else {
                            SettingsRegistry.spec(key)?.let { config.setRaw(it, value) }
                            io.panelassistant.android.LiveSettingRequestOutcome.APPLIED
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
