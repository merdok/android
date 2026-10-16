package io.panelassistant.android.http

import io.panelassistant.android.config.Capabilities
import io.panelassistant.android.config.SettingsRegistry
import io.panelassistant.android.util.InstallProgress
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.server.application.call
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import sun.misc.Unsafe

/** Caller refusal parity without helper provisioning or approval in preview. */
class CompanionRestoreReadinessTest {
    private val archiveBody = """{"kind":"ha-paneld-backup","schema":15,"config":{"friendly_name":"Restored"},"companion":{"pkg":"io.homeassistant.companion.android.minimal","files":[{"rel":"databases/HomeAssistantDB","b64":"c3FsaXRlLWNvbnRlbnQ="}]}}"""

    @Test fun unavailableCompanionRefusesPreviewAndApplyBeforeApprovalOrProvisioning() {
        withRoute(directSu = false, helperReady = false) { fixture, route, helperCalls, approvalCalls ->
            testApplication {
                application { routing { post("/api/v1/restore") { route.handle(call.op()) } } }
                for (suffix in listOf("?dry_run=1", "")) {
                    val response = client.post("/api/v1/restore$suffix") { setBody(archiveBody) }
                    val text = response.bodyAsText()
                    assertEquals(text, HttpStatusCode.ServiceUnavailable, response.status)
                    assertEquals("Companion restore needs the current Panel Assistant helper", JSONObject(text).getString("error"))
                    assertEquals("Before", fixture.config.friendlyName)
                    assertFalse(InstallProgress.running)
                }
                assertEquals(0, helperCalls.get())
                assertEquals(0, approvalCalls.get())
            }
        }
    }

    @Test fun currentHelperWithoutRootAndRootRepairRouteBothPermitReadOnlyPreview() {
        for ((directSu, helperReady) in listOf(false to true, true to false)) {
            withRoute(directSu, helperReady) { fixture, route, helperCalls, approvalCalls ->
                testApplication {
                    application { routing { post("/api/v1/restore") { route.handle(call.op()) } } }
                    val response = client.post("/api/v1/restore?dry_run=1") { setBody(archiveBody) }
                    val text = response.bodyAsText()
                    assertEquals(text, HttpStatusCode.OK, response.status)
                    assertTrue(JSONObject(text).getBoolean("ok"))
                    assertEquals(1, JSONObject(text).getInt("companion_files"))
                    assertEquals(0, helperCalls.get())
                    assertEquals(0, approvalCalls.get())
                    assertEquals("Before", fixture.config.friendlyName)
                    assertFalse(InstallProgress.running)
                }
            }
        }
    }

    @Test fun rootRepairStillPreparesHelperAfterApprovalAndReportsDynamicPreparationFailure() {
        withRoute(directSu = true, helperReady = false) { fixture, route, helperCalls, approvalCalls ->
            testApplication {
                application { routing { post("/api/v1/restore") { route.handle(call.op()) } } }
                val response = client.post("/api/v1/restore") { setBody(archiveBody) }
                assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
                assertEquals(1, helperCalls.get())
                assertEquals(1, approvalCalls.get())
                assertEquals("Before", fixture.config.friendlyName)
                assertFalse(InstallProgress.running)
            }
        }
    }

    private fun withRoute(
        directSu: Boolean,
        helperReady: Boolean,
        block: (PaneldServerHttpFixture, RestoreRoutes, java.util.concurrent.atomic.AtomicInteger, java.util.concurrent.atomic.AtomicInteger) -> Unit,
    ) {
        PaneldServerHttpFixture().use { fixture ->
            fixture.config.setRaw(requireNotNull(SettingsRegistry.spec("friendly_name")), "Before")
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val helperCalls = java.util.concurrent.atomic.AtomicInteger()
            val approvalCalls = java.util.concurrent.atomic.AtomicInteger()
            // The helper refusal must finish before the executor is reached. Allocate the unused
            // collaborator so any accidental execution fails loudly rather than simulating a restore.
            val unsafe = Unsafe::class.java.getDeclaredField("theUnsafe").run {
                isAccessible = true
                get(null) as Unsafe
            }
            val route = RestoreRoutes(
                cacheDir = fixture.directory,
                config = fixture.config,
                reader = BackupArchiveReader(fixture.directory) {
                    setOf("io.homeassistant.companion.android.minimal")
                },
                profileAdmin = null,
                ensureCompanionHelper = { helperCalls.incrementAndGet(); false },
                authorizeSensitive = { _, _, _, _ -> approvalCalls.incrementAndGet(); true },
                rejectHardenedNetworkAdb = { _, _ -> false },
                capabilities = { Capabilities(networkAdb = directSu) },
                scope = scope,
                executor = unsafe.allocateInstance(RestoreExecutor::class.java) as RestoreExecutor,
                wakeWords = null,
            )
            // Fixture setup remains compatible with the untouched base for red-before-fix runs.
            route.javaClass.declaredFields.firstOrNull { it.name == "companionHelperReady" }?.apply {
                isAccessible = true
                set(route, { helperReady })
            }
            try { block(fixture, route, helperCalls, approvalCalls) } finally { scope.cancel() }
        }
    }
}
