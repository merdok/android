package io.panelassistant.android.http

import io.panelassistant.android.Config
import io.panelassistant.android.LiveSettingRequestOutcome
import io.panelassistant.android.applyAcknowledgedNavbarMode
import io.panelassistant.android.control.NavbarModeApplyOutcome
import io.panelassistant.android.control.NavbarModeApplyStatus
import io.panelassistant.android.control.fakeProfile
import io.panelassistant.android.device.DeviceProfile
import java.util.concurrent.CompletableFuture
import io.panelassistant.android.LiveSettingAuthority
import io.panelassistant.android.LiveSettingApplyResult
import io.panelassistant.android.hasNativeNavbar
import io.panelassistant.android.navbarMode
import io.panelassistant.android.config.Capabilities
import io.panelassistant.android.config.SettingsRegistry
import io.panelassistant.android.control.SystemController
import io.panelassistant.android.platform.ActivityRef
import io.panelassistant.android.platform.SystemEnv
import io.panelassistant.android.testsupport.PanelRuntimeRig
import io.panelassistant.android.util.InstallProgress
import io.panelassistant.android.util.RendererPreparationCoordinator
import io.panelassistant.android.util.RendererPreparationState
import io.ktor.http.Parameters
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

internal class RestoreNavbarCapabilitiesTest : PanelRuntimeRig() {
    @Test fun previewAndRealRestoreMustSkipUnsupportedNativeAndReportPartial() = runBlocking {
        PaneldServerHttpFixture().use { fixture ->
            val rig = rig()
            try {
                rig.start()
                val config = rig.config
                config.setRaw(requireNotNull(SettingsRegistry.spec("panel_id")), "navbar-probe")
                config.setRaw(requireNotNull(SettingsRegistry.spec("navbar_mode")), "Off")
                config.setRaw(requireNotNull(SettingsRegistry.spec("friendly_name")), "Before")
                assertFalse(config.hasNativeNavbar)
                val caps = Capabilities(hasNativeNavbar = false)
                val source = mapOf("friendly_name" to "Restored", "navbar_mode" to "Native")
                val direct = normalizeConfigPostParameters(Parameters.build {
                    append("navbar_mode", "Native")
                }, caps)
                assertTrue(direct is ConfigPostParameters.Bad)
                val decision = planRestoreSettings(source, null)
                assertTrue(decision.errors.isEmpty())
                val archivePlan = RestoreConfigPlan(decision.accepted, emptyList(), emptyList())
                val preview = archivePlan.withAvailableLiveSettings(caps)
                val authority = LiveSettingAuthority(SettingsRegistry.liveApplyKeys().toSet(), bootIdentity = { "test-boot" })
                val executor = executor(fixture, config, caps) { key, value ->
                    authority.applyOrQueueOutcome(key, value, null) { appliedKey, appliedValue, previous ->
                        rig.commands.applySetting(appliedKey, appliedValue, previous)
                    }
                }
                val ticket = requireNotNull(InstallProgress.start("restore"))
                try {
                    executor.execute(archivePlan, null, null, null, null, emptyList(), false,
                        emptyList(), null, ticket)
                    val progress = JSONObject(InstallProgress.json())
                    val result = progress.getJSONObject("result")
                    assertEquals("Restored", config.friendlyName)
                    assertEquals("Off", config.navbarMode)
                    assertEquals(listOf("navbar_mode"), preview.skipped)
                    assertEquals("partial", result.getString("status"))
                    assertTrue(progress.getString("message").contains("Navbar mode"))
                } finally { InstallProgress.finish(ticket, "test cleanup") }
            } finally { rig.close() }
        }
    }
    @Test fun capableNativeRestoreAcknowledgesAndStoresTheRequestedMode() = runBlocking {
        PaneldServerHttpFixture().use { fixture ->
            val config = fixture.config
            config.setRaw(requireNotNull(SettingsRegistry.spec("friendly_name")), "Before")
            config.attachProfile(object : DeviceProfile by fakeProfile() { override val hasNativeNavbar = true })
            val spec = requireNotNull(SettingsRegistry.spec("navbar_mode"))
            config.setRaw(spec, "Off")
            val caps = Capabilities(hasNativeNavbar = true)
            val plan = RestoreConfigPlan(mapOf("friendly_name" to "Restored", "navbar_mode" to "Native"), emptyList(), emptyList())
            assertTrue(plan.withAvailableLiveSettings(caps).skipped.isEmpty())
            val executor = executor(fixture, config, caps) { key, value ->
                check(key == "navbar_mode")
                applyAcknowledgedNavbarMode(
                    value, config.navbarMode,
                    actuate = { CompletableFuture.completedFuture(NavbarModeApplyOutcome(it, NavbarModeApplyStatus.APPLIED)) },
                    rollback = { _, previous -> CompletableFuture.completedFuture(NavbarModeApplyOutcome(previous, NavbarModeApplyStatus.APPLIED)) },
                    persist = { config.commitRaw(spec, it) }, reconcile = {},
                )
                LiveSettingRequestOutcome.APPLIED
            }
            val ticket = requireNotNull(InstallProgress.start("restore"))
            try {
                executor.execute(plan, null, null, null, null, emptyList(), false,
                    emptyList(), null, ticket)
                val result = JSONObject(InstallProgress.json()).getJSONObject("result")
                assertEquals("succeeded", result.getString("status"))
                assertEquals("Native", config.getRaw(spec))
                assertEquals("Restored", config.friendlyName)
            } finally { InstallProgress.finish(ticket, "test cleanup") }
        }
    }

    private fun executor(
        fixture: PaneldServerHttpFixture,
        config: Config,
        caps: Capabilities,
        applySetting: (String, String) -> LiveSettingRequestOutcome,
    ): RestoreExecutor {
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
            appContext = fixture.context, config = config, capabilities = { caps },
            transaction = {
                AcceptedConfigTransaction(
                    config = config, revisions = RevisionStore(fixture.directory), rendererPreparation = renderer,
                    system = system, values = projection,
                    applySetting = applySetting,
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
